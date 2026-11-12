from uuid import uuid4
from unittest.mock import AsyncMock

import pytest

from app import document_input
from app.document_input import DocumentInputError, document_context, read_document, validate_document_citations
from app.conversation_graph import ConversationDecision, ConversationReActRuntime, MusicPreferenceProfile, _request_with_interventions
from app.schemas import ConversationAgentRequest


def request(attachment_id=None):
    attachment_id = attachment_id or str(uuid4())
    return ConversationAgentRequest(request_id=uuid4(), agent_run_id=uuid4(), conversation_id=uuid4(),
        guest_id=str(uuid4()), user_message="根据资料回答并引用来源，不要联网。",
        document_attachments=[{"id": attachment_id, "kind": "PDF", "fileName": "notes.pdf"}])


def result(attachment_id, text="SNAPSHOT_PRIVATE_MARKER", page=2, long=False):
    return {"document": {"id": attachment_id, "kind": "PDF", "fileName": "notes.pdf", "pageCount": 50 if long else 2,
            "textLength": 100_000 if long else len(text), "warning": ""},
        "pages": [] if long else [{"number": page, "text": text}], "completeDocument": not long,
        "hasMore": long, "nextPage": 3, "nextOffset": 0}


@pytest.mark.asyncio
async def test_short_document_context_is_complete_but_never_serialized(monkeypatch):
    req = request(); attachment_id = req.document_attachments[0]["id"]
    fetch = AsyncMock(return_value=result(attachment_id))
    monkeypatch.setattr(document_input, "_fetch", fetch)
    context = await document_context(req)
    assert "SNAPSHOT_PRIVATE_MARKER" in context
    assert "SNAPSHOT_PRIVATE_MARKER" not in req.model_dump_json()
    assert 2 in req._document_sources[attachment_id]
    await document_context(req)
    assert fetch.await_count == 1


@pytest.mark.asyncio
async def test_long_document_is_unread_until_agent_selects_a_range(monkeypatch):
    req = request(); attachment_id = req.document_attachments[0]["id"]
    monkeypatch.setattr(document_input, "_fetch", AsyncMock(return_value=result(attachment_id, long=True)))
    context = await document_context(req)
    assert "尚未完整阅读" in context
    assert not req._document_sources


@pytest.mark.asyncio
async def test_reading_search_and_repeat_guard_remain_request_scoped(monkeypatch):
    req = request(); attachment_id = req.document_attachments[0]["id"]
    fetch = AsyncMock(return_value=result(attachment_id, "exact source excerpt", page=47))
    monkeypatch.setattr(document_input, "_fetch", fetch)
    found = await read_document(req, attachment_id, query="stage cue")
    assert found["pages"][0]["number"] == 47
    assert (await read_document(req, attachment_id, query="stage cue"))["status"] == "already_read"
    assert fetch.await_count == 1
    assert "exact source excerpt" not in req.model_dump_json()


@pytest.mark.asyncio
async def test_budget_failure_is_explicit_and_does_not_fetch(monkeypatch):
    req = request(); req._document_reads["old"] = {"pages": [{"text": "x" * 80_000}]}
    fetch = AsyncMock(); monkeypatch.setattr(document_input, "_fetch", fetch)
    assert (await read_document(req, req.document_attachments[0]["id"]))["status"] == "budget_exhausted"
    fetch.assert_not_called()


@pytest.mark.asyncio
async def test_unknown_document_cannot_be_fetched():
    with pytest.raises(DocumentInputError):
        await document_input._fetch(request(), str(uuid4()), page=1)


def test_only_actual_read_pages_produce_clickable_citations():
    req = request(); attachment_id = req.document_attachments[0]["id"]
    req._document_sources[attachment_id] = {2}
    valid = f"[文档:{attachment_id}:2]"
    assert validate_document_citations(valid, req) == valid
    assert "未能核验" in validate_document_citations(f"[文档:{attachment_id}:1]", req)
    assert "未能核验" in validate_document_citations(f"[文档:{uuid4()}:2]", req)


def test_typed_intervention_preserves_documents_and_resumable_metadata():
    req = request(); new, image = str(uuid4()), str(uuid4())
    changed = _request_with_interventions(req, ["再看资料"], [new, image], [{"id": new, "kind": "TEXT", "fileName": "new.txt"}, {"id": image, "kind": "IMAGE"}])
    assert [item["id"] for item in changed.document_attachments] == [req.document_attachments[0]["id"], new]
    assert changed.image_attachments == [{"id": image, "kind": "IMAGE"}]
    restored = ConversationAgentRequest.model_validate(changed.model_dump(by_alias=True))
    assert restored.document_attachments[-1]["fileName"] == "new.txt"


@pytest.mark.asyncio
async def test_unified_graph_executes_document_tools_and_validates_final_reference(monkeypatch):
    req = request(); attachment_id = req.document_attachments[0]["id"]
    runtime = ConversationReActRuntime(None, None)
    runtime.analyze_preference = AsyncMock(return_value=MusicPreferenceProfile())
    runtime.decide = AsyncMock(side_effect=[
        ConversationDecision(action="search_document", attachment_id=attachment_id, query="cue", public_summary="查找上传资料中的排练信息"),
        ConversationDecision(action="read_document", attachment_id=attachment_id, document_page=47, public_summary="阅读命中页以确认上下文"),
        ConversationDecision(action="respond", public_summary="根据上传资料整理回答"),
    ])
    runtime.answer = AsyncMock(return_value=f"代码是CEDAR-481。[文档:{attachment_id}:47]")
    monkeypatch.setattr(document_input, "_fetch", AsyncMock(return_value=result(attachment_id, "cue CEDAR-481", page=47)))
    state = await runtime.graph.ainvoke({"request": req}, {"configurable": {"thread_id": str(uuid4())}})
    assert "CEDAR-481" in state["result"].text
    assert f"[文档:{attachment_id}:47]" in state["result"].text
    assert [item["action"] for item in state["action_history"]][-3:] == ["search_document", "read_document", "respond"]


@pytest.mark.asyncio
async def test_document_answer_does_not_force_music_web_research(monkeypatch):
    from types import SimpleNamespace
    req = request(); req.user_message = "分析这份附件的观点，只根据资料回答，不要联网。"
    attachment_id = req.document_attachments[0]["id"]
    runtime = ConversationReActRuntime(None, None)
    call = AsyncMock(return_value=ConversationDecision(action="respond", public_summary="依据文件整理观点"))
    runtime.model = SimpleNamespace(with_structured_output=lambda *args, **kwargs: SimpleNamespace(ainvoke=call))
    monkeypatch.setattr(document_input, "_fetch", AsyncMock(return_value=result(attachment_id)))
    chosen = await runtime.decide({"request":req,"analysis_workspace":{"subject":"上传资料"},"evidence_coverage":{"blocking_gaps":["没有公开来源"]}})
    assert chosen.action == "respond"
