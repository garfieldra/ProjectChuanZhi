"""Versioned retrieval. Only Java-authorized revisions may leave this module."""
from __future__ import annotations

import asyncio
import hashlib
import json
import logging
import os
import re
import threading
from collections import defaultdict
from contextvars import ContextVar
from typing import Any

import httpx

log = logging.getLogger(__name__)
_session: ContextVar[dict | None] = ContextVar("knowledge_evidence_session", default=None)


def begin_evidence_session():
    _session.set({})


async def finalize_evidence_session(result, retriever):
    """Preserve references even when a composite tool only used theme explanations."""
    session = _session.get() or {}
    if not session:
        return
    used = list(session.values())
    verified = await retriever.revalidate(used)
    valid = {(e["entryId"], e["revision"]) for e in verified}
    if any(key not in valid for key in session):
        raise RuntimeError("KNOWLEDGE_EVIDENCE_CHANGED")
    sources = [{**item, "title": next((s.get("sourceTitle") for s in item.get("support", []) if s.get("relation") == "SUPPORTS"), "维护者整理")} for item in verified]
    if hasattr(result, "knowledge_sources"):
        result.knowledge_sources = sources
    elif getattr(result, "card_intent", None):
        result.card_intent.payload["knowledgeSources"] = sources


def row_id(entry_id: str, revision: int, generation: int) -> str:
    return f"{entry_id}:{revision}:g{generation}"


def fuse(groups: list[list[dict]], limit: int = 60) -> list[dict]:
    scores: dict[tuple, float] = defaultdict(float)
    values: dict[tuple, dict] = {}
    for group in groups:
        seen = set()
        for rank, hit in enumerate(group, 1):
            key = (hit["entryId"], int(hit["revision"]))
            if key in seen:
                continue
            seen.add(key)
            values[key] = {"entryId": key[0], "revision": key[1]}
            scores[key] += 1 / (60 + rank)
    return [values[key] for key in sorted(scores, key=lambda k: (-scores[k], k))[:limit]]


def document_text(content: dict) -> str:
    text = json.dumps({k: content.get(k) for k in ("knowledgeType", "summary", "attribution", "applicability", "boundary", "entities", "aliases", "theme")}, ensure_ascii=False)
    # The model's tokenizer is the final authority; never silently truncate a claim.
    return text


class VersionedIndex:
    def __init__(self, uri: str):
        self.uri = uri
        self.min_similarity = float(os.getenv("KNOWLEDGE_MIN_SIMILARITY", "0.5"))
        if not -1 <= self.min_similarity <= 1:
            raise ValueError("KNOWLEDGE_SIMILARITY_RANGE_INVALID")
        self._models: dict[tuple, Any] = {}
        self._model_lock = threading.Lock()
        self._clients: dict[str, Any] = {}

    def client(self):
        from pymilvus import MilvusClient
        if self.uri not in self._clients:
            self._clients[self.uri] = MilvusClient(uri=self.uri, timeout=3)
        return self._clients[self.uri]

    def model(self, config: dict):
        from fastembed import TextEmbedding
        key = (config["model"], config["modelRevision"], config["templateVersion"])
        with self._model_lock:
            if key not in self._models:
                if not re.fullmatch(r"[a-f0-9]{40}", config["modelRevision"]):
                    raise ValueError("MODEL_REVISION_MUST_BE_IMMUTABLE_COMMIT")
                from huggingface_hub import snapshot_download
                description = next(item for item in TextEmbedding.list_supported_models() if item["model"] == config["model"])
                repository = description["sources"].get("hf")
                if not repository:
                    raise ValueError("PINNED_MODEL_SOURCE_REQUIRED")
                path = snapshot_download(repo_id=repository, revision=config["modelRevision"],
                    cache_dir=os.getenv("FASTEMBED_CACHE_PATH"),
                    allow_patterns=[description["model_file"], "config.json", "tokenizer.json", "tokenizer_config.json", "special_tokens_map.json"])
                self._models[key] = TextEmbedding(model_name=config["model"], specific_model_path=path)
        return self._models[key]

    def validate_text(self, text: str, config: dict):
        model = self.model(config)
        tokenizer = getattr(getattr(model, "model", None), "tokenizer", None)
        if tokenizer is None:
            raise ValueError("KNOWLEDGE_TOKENIZER_UNAVAILABLE")
        if len(tokenizer.encode(text).ids) > 480:
            raise ValueError("KNOWLEDGE_INPUT_TOO_LONG")
        return model

    def validate_document(self, content: dict, config: dict):
        self.validate_text(document_text(content), config)

    def vector(self, text: str, config: dict) -> list[float]:
        model = self.validate_text(text, config)
        vector = next(iter(model.embed([text]))).tolist()
        if len(vector) != int(config["dimension"]):
            raise ValueError("EMBEDDING_DIMENSION_MISMATCH")
        return vector

    def collection(self, config: dict, theme: bool):
        client = self.client()
        name = config["themeCollection" if theme else "factCollection"]
        if not client.has_collection(name):
            from pymilvus import DataType
            schema = client.create_schema(auto_id=False, enable_dynamic_field=True)
            schema.add_field("id", DataType.VARCHAR, is_primary=True, max_length=150)
            schema.add_field("vector", DataType.FLOAT_VECTOR, dim=int(config["dimension"]))
            params = client.prepare_index_params()
            params.add_index(field_name="vector", index_type="AUTOINDEX", metric_type="COSINE")
            try:
                client.create_collection(collection_name=name, schema=schema, index_params=params, consistency_level="Strong")
            except Exception:
                if not client.has_collection(name):
                    raise
        return name

    def apply(self, job: dict):
        config, content = job["config"], job["content"]
        key = row_id(job["entry_id"], int(job["revision"]), int(job["generation"]))
        collection = self.collection(config, content["knowledgeType"] == "THEME_CARD")
        if job["operation"] == "DELETE":
            self.client().delete(collection_name=collection, ids=[key])
            return
        text = document_text(content)
        self.client().upsert(collection_name=collection, data=[{
            "id": key, "vector": self.vector(text, config), "entryId": job["entry_id"],
            "revision": int(job["revision"]), "knowledgeType": content["knowledgeType"],
            "entityKeys": [e["type"] + ":" + e["id"] for e in content["entities"]],
            "contentHash": hashlib.sha256(text.encode()).hexdigest(),
        }])

    def search(self, request: dict, config: dict) -> list[dict]:
        client = self.client()
        vector = self.vector(request["query"], config)
        types = request.get("knowledgeTypes") or []
        groups = []
        for theme in (False, True):
            if types and ((theme and "THEME_CARD" not in types) or (not theme and types == ["THEME_CARD"])):
                continue
            name = config["themeCollection" if theme else "factCollection"]
            if not client.has_collection(name):
                continue
            clauses = []
            if types:
                clauses.append("knowledgeType in " + json.dumps(types))
            # Entity scope is rechecked by Java; exact entity filtering also avoids an unrelated top-k crowding out relevant hits.
            for_entity = [f'json_contains(entityKeys, {json.dumps(e["type"] + ":" + e["id"])})' for e in request.get("entities") or []]
            if for_entity:
                clauses.append("(" + " or ".join(for_entity) + ")")
            hits = client.search(collection_name=name, data=[vector], filter=" and ".join(clauses),
                                 limit=20, output_fields=["entryId", "revision"], consistency_level="Strong")[0]
            groups.append([hit["entity"] for hit in hits if float(hit.get("distance", -1)) >= self.min_similarity])
        return fuse(groups)


class KnowledgeRetriever:
    def __init__(self, base_url: str, token: str, uri: str, *, transport=None, index=None):
        self.base_url, self.token = base_url.rstrip("/"), token
        self.transport = transport
        self.index = index or VersionedIndex(uri)
        self._vector_slots = asyncio.Semaphore(2)

    def client(self, timeout: float = 4):
        return httpx.AsyncClient(base_url=self.base_url, transport=self.transport, timeout=timeout,
                                headers={"Authorization": "Bearer " + self.token})

    async def _post(self, client, path, body):
        response = await client.post("/internal/v1/knowledge/" + path, json=body)
        response.raise_for_status()
        return response.json()

    async def _vector_search(self, request: dict, config: dict):
        await self._vector_slots.acquire()
        task = asyncio.create_task(asyncio.to_thread(self.index.search, request, config))
        task.add_done_callback(lambda _task: self._vector_slots.release())
        # A timed-out HTTP caller must not release a slot while its CPU task is still running.
        return await asyncio.shield(task)

    async def _bounded_vector_search(self, request: dict, config: dict):
        async with asyncio.timeout(2.5):
            return await self._vector_search(request, config)

    async def search(self, request: dict) -> dict:
        try:
            async with asyncio.timeout(5):
                result = await self._search(request)
                session = _session.get()
                if session is not None:
                    for evidence in result.get("evidence", []):
                        session[(evidence["entryId"], evidence["revision"])] = evidence
                return result
        except Exception as exc:
            log.info("knowledge_retrieval_unavailable type=%s", type(exc).__name__)
            return {"status": "unavailable", "reason": "KNOWLEDGE_UNAVAILABLE", "evidence": []}

    async def _search(self, request: dict) -> dict:
        request = {"entities": [], "entityScope": "RELATED_CONTEXT", "knowledgeTypes": [], "limit": 6, **request}
        request["limit"] = max(1, min(20, int(request["limit"])))
        async with self.client() as client:
            generation_id = request.pop("generationId", None)
            request = await self._post(client, "scope", request)
            generation = await client.get("/internal/v1/knowledge/generation" if generation_id is None else f"/internal/v1/knowledge/generations/{int(generation_id)}")
            generation.raise_for_status()
            config = generation.json()["config"]
            async def exact():
                result = await self._post(client, "exact", {**request, "limit": 20})
                return result["hits"]
            groups = await asyncio.gather(exact(), self._bounded_vector_search(request, config), return_exceptions=True)
            degraded = any(isinstance(group, BaseException) for group in groups)
            diagnostics = {"exactCandidates": len(groups[0]) if isinstance(groups[0], list) else 0,
                           "vectorCandidates": len(groups[1]) if isinstance(groups[1], list) else 0,
                           "vectorAvailable": isinstance(groups[1], list)}
            candidates = fuse([group for group in groups if isinstance(group, list)])
            if not candidates:
                return {"status": "unavailable" if degraded else "no_match", "evidence": [], "diagnostics": diagnostics}
            verified = await self._post(client, "verify", {"hits": candidates, "search": request})
            evidence = verified["evidence"][:request["limit"]]
            covered = {(e["type"], e["id"]) for item in evidence for e in item.get("entities", [])}
            complete = all((e["type"], e["id"]) in covered for e in request["entities"])
            return {"status": ("partial" if degraded or not complete else "hit") if evidence else ("unavailable" if degraded else "no_match"),
                    "evidence": evidence, "coverage": {"completeForRequestedEntities": complete}, "generation": generation.json()["id"], "diagnostics": diagnostics}

    async def revalidate(self, evidence: list[dict]) -> list[dict]:
        hits = [{"entryId": e["entryId"], "revision": e["revision"]} for e in evidence if e.get("entryId")]
        if not hits:
            return []
        try:
            async with self.client() as client:
                return (await self._post(client, "verify", {"hits": hits, "search": None}))["evidence"]
        except Exception:
            return []


def evidence_source(item: dict) -> dict:
    support = next((s for s in item["support"] if s["relation"] == "SUPPORTS"), {})
    public_url = support.get("sourceUrl") or ""
    return {**item, "sourceTitle": support.get("sourceTitle") or "维护者整理", "sourceUrl": public_url,
            "summary": item["statement"], "pageExcerpt": support.get("excerpt", ""),
            "sourceLocator": support.get("locator"), "searchProvider": "knowledge",
            "evidenceKey": row_id(item["entryId"], int(item["revision"]), 0),
            "evidenceUrl": f'/api/v1/evidence/{item["entryId"]}/{item["revision"]}'}


def stable_sources(sources: list[dict], registry: dict[str, str]) -> list[dict]:
    output = []
    seen = set()
    for item in sources:
        key = item.get("evidenceKey") or item.get("sourceUrl")
        if not key or key in seen:
            continue
        seen.add(key)
        if key not in registry:
            proposed = item.get("ref")
            if isinstance(proposed, str) and proposed.startswith("S") and proposed[1:].isdigit() and proposed not in registry.values():
                registry[key] = proposed
            else:
                last = max((int(ref[1:]) for ref in registry.values()), default=0)
                registry[key] = "S" + str(last + 1)
        output.append({**item, "ref": registry[key]})
    return output


async def check_result_evidence(result, tool):
    """Fail closed before publishing a result whose retained evidence was withdrawn."""
    card = result.card_intent
    if not card or not hasattr(tool, "retriever"):
        return
    sources = [s for s in card.payload.get("sources", []) if s.get("entryId")]
    if not sources:
        return
    verified = await tool.retriever.revalidate(sources)
    valid = {(s["entryId"], s["revision"]) for s in verified}
    if any((s["entryId"], s["revision"]) not in valid for s in sources):
        raise RuntimeError("KNOWLEDGE_EVIDENCE_CHANGED")
