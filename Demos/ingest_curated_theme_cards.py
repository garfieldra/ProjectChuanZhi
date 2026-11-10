#!/usr/bin/env python3
"""Submit reviewed legacy cards as drafts through Java, never directly into Milvus."""
from __future__ import annotations
import argparse, hashlib, json, os
from pathlib import Path
from uuid import uuid4
import httpx

def main() -> None:
    parser = argparse.ArgumentParser(); parser.add_argument("--input", type=Path, default=Path("Demos/generated/local-curated-theme-cards.jsonl")); parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--metadata", type=Path, help="Fingerprint-keyed JSON: entities, source, reviewNote")
    parser.add_argument("--base-url", default="http://localhost:8080"); args = parser.parse_args()
    if not args.input.exists():
        if args.dry_run:
            print(json.dumps({"approvedCards": 0, "collection": os.getenv("KNOWLEDGE_COLLECTION", "isq_song_theme_cards_v1"), "reason": "no curated cards yet"}, ensure_ascii=False)); return
        raise SystemExit(f"curated input does not exist: {args.input}")
    cards = [json.loads(line) for line in args.input.read_text(encoding="utf-8").splitlines() if line.strip()]
    approved = [card for card in cards if card.get("sourceType") == "LOCAL_CURATED" and card.get("reviewStatus") == "APPROVED"]
    if args.dry_run: print(json.dumps({"approvedCards": len(approved), "target": "Java DRAFT candidates; no publication"}, ensure_ascii=False)); return
    if not approved or not args.metadata: parser.error("Approved cards and --metadata with confirmed entities and source rights are required")
    metadata = json.loads(args.metadata.read_text(encoding="utf-8")); candidates = []
    for card in approved:
        meta = metadata.get(card["sourceFingerprint"])
        if not meta or not meta.get("entities") or not meta.get("source") or not meta.get("reviewNote"): parser.error("Missing confirmed metadata for " + card["sourceFingerprint"])
        candidates.append({"knowledgeType": "THEME_CARD", "summary": card["summary"], "boundary": "维护者主题解释，不证明创作者意图或制作事实。", "entities": meta["entities"],
            "support": [{"source": meta["source"], "excerpt": meta["reviewNote"], "locator": "审核记录 " + card["sourceFingerprint"], "relation": "SUPPORTS"}],
            "theme": {key: card.get(key, [] if key in {"themes", "moods", "scenes"} else "") for key in ("title", "artist", "themes", "moods", "scenes")}})
    email, password = os.getenv("KNOWLEDGE_IMPORT_EMAIL"), os.getenv("KNOWLEDGE_IMPORT_PASSWORD")
    if not email or not password: parser.error("Set KNOWLEDGE_IMPORT_EMAIL and KNOWLEDGE_IMPORT_PASSWORD for a maintainer")
    with httpx.Client(base_url=args.base_url, timeout=30) as client:
        client.get("/api/v1/auth/session").raise_for_status()
        client.post("/api/v1/auth/login", json={"email": email, "password": password}, headers={"X-XSRF-TOKEN": client.cookies.get("XSRF-TOKEN", "")}).raise_for_status()
        for offset in range(0, len(candidates), 100):
            response = client.post("/api/v1/admin/knowledge/imports", json={"origin": "MANUAL_FILE", "candidateItems": candidates[offset:offset + 100]}, headers={"X-XSRF-TOKEN": client.cookies.get("XSRF-TOKEN", ""), "Idempotency-Key": "theme-import-" + hashlib.sha256(json.dumps(candidates[offset:offset + 100],ensure_ascii=False,sort_keys=True).encode()).hexdigest()})
            response.raise_for_status(); print(json.dumps(response.json(), ensure_ascii=False))
    print("Drafts submitted. Explicit review and publication are still required.")

if __name__ == "__main__": main()
