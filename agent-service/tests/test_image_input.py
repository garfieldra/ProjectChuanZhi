from uuid import uuid4
from unittest.mock import AsyncMock
import json
import httpx
import pytest
from app.image_input import ImageInputBudgetError, multimodal_prompt
from app.schemas import ConversationAgentRequest
from app.conversation_graph import _request_with_interventions

def request(images=None):
    return ConversationAgentRequest(request_id=uuid4(),agent_run_id=uuid4(),conversation_id=uuid4(),guest_id=str(uuid4()),user_message="描述图片",image_attachments=images or [])

@pytest.mark.asyncio
async def test_text_only_input_needs_no_attachment_fetch(monkeypatch):
    def forbidden(*args,**kwargs):raise AssertionError("unexpected fetch")
    monkeypatch.setattr(httpx,"AsyncClient",forbidden)
    assert await multimodal_prompt("text",request())=="text"

@pytest.mark.asyncio
async def test_actual_image_blocks_are_private_and_owner_scoped(monkeypatch):
    attachment_id=str(uuid4());req=request([{"id":attachment_id,"fileName":"photo.png"}])
    response=httpx.Response(200,content=b"test-image-bytes",headers={"Content-Type":"image/jpeg"},request=httpx.Request("GET","http://java/image"))
    get_mock=AsyncMock(return_value=response)
    class Client:
        async def __aenter__(self):return self
        async def __aexit__(self,*args):pass
        get=get_mock
    monkeypatch.setattr(httpx,"AsyncClient",lambda **kwargs:Client())
    messages=await multimodal_prompt("read",req)
    assert messages[0].content[-1]["type"]=="image_url"
    image=messages[0].content[-1]["image_url"]["url"]
    assert image.startswith("data:image/jpeg;base64,")
    assert get_mock.call_args.kwargs["params"]=={"guestId":req.guest_id,"conversationId":str(req.conversation_id)}
    assert image not in req.model_dump_json()
    await multimodal_prompt("follow-up",req)
    assert get_mock.await_count==1

@pytest.mark.asyncio
async def test_foreign_or_missing_attachment_fails_instead_of_inventing_content(monkeypatch):
    response=httpx.Response(404,request=httpx.Request("GET","http://java/image"))
    class Client:
        async def __aenter__(self):return self
        async def __aexit__(self,*args):pass
        async def get(self,*args,**kwargs):return response
    monkeypatch.setattr(httpx,"AsyncClient",lambda **kwargs:Client())
    with pytest.raises(httpx.HTTPStatusError):await multimodal_prompt("read",request([{"id":str(uuid4())}]))

def test_image_intervention_preserves_old_and_new_context():
    old,new=str(uuid4()),str(uuid4());req=request([{"id":old}])
    changed=_request_with_interventions(req,["再看这张"],[new,old])
    assert [item["id"] for item in changed.image_attachments]==[old,new]
    assert changed.recent_messages[-1]["attachmentIds"]==[new,old]
    assert len(req.image_attachments)==1


@pytest.mark.asyncio
async def test_image_budget_never_silently_drops_old_images():
    req = request()
    req = req.model_copy(update={"image_attachments": [{"id": str(uuid4())} for _ in range(65)]})
    with pytest.raises(ImageInputBudgetError, match="64张"):
        await multimodal_prompt("read", req)


@pytest.mark.asyncio
async def test_encoded_image_budget_is_explicit():
    image_id = str(uuid4())
    req = request([{"id": image_id}])
    req._image_cache[image_id] = "x" * (32 * 1024 * 1024 + 1)
    with pytest.raises(ImageInputBudgetError, match="32MiB"):
        await multimodal_prompt("read", req)


def test_image_intervention_keeps_resumable_context_within_schema_budget():
    req = request()
    req.recent_messages = [{"role": "USER", "content": str(index)} for index in range(1000)]
    changed = _request_with_interventions(req, [], [str(uuid4())])
    restored = ConversationAgentRequest.model_validate(changed.model_dump())
    assert len(restored.recent_messages) == 1000
    assert restored.recent_messages[-1]["attachmentIds"]
