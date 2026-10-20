#!/usr/bin/env python3
"""Time the real analysis Agent and search provider without requiring Java/Docker.

Run from the project root with ``PYTHONPATH=agent-service python
Demos/benchmark_analysis_runtime.py``. Only safe timing and coverage summaries
are printed; credentials and raw model/tool content are never logged.
"""

from __future__ import annotations

import argparse
import asyncio
import json
import time
from uuid import uuid4

from app.conversation_graph import ConversationReActRuntime
from app.schemas import ConversationAgentRequest
from app.settings import settings
from app.tools import WebSearchTool


class NoLocalKnowledge:
    async def search_verified(self, _query: str, _recording_ids: list[str]) -> list[dict]:
        return []


async def benchmark(prompt: str) -> dict:
    runtime = ConversationReActRuntime(
        WebSearchTool(settings.tavily_api_key, settings.bocha_api_key),
        NoLocalKnowledge(),
    )
    if runtime.model is None:
        raise RuntimeError("DeepSeek is not configured")
    request_id = uuid4()
    request = ConversationAgentRequest(
        requestId=request_id, agentRunId=request_id, conversationId=uuid4(),
        guestId="analysis-benchmark", userMessage=prompt,
    )
    started = time.monotonic()
    state = await asyncio.wait_for(runtime.graph.ainvoke(
        {"request": request},
        {"configurable": {"thread_id": str(request_id)}, "recursion_limit": 28},
    ), timeout=240)
    elapsed = round(time.monotonic() - started, 2)
    result = state["result"]
    return {
        "elapsedSeconds": elapsed,
        "action": result.action,
        "answerCharacters": len(result.text),
        "actions": [item.get("action") for item in state.get("action_history", [])],
        "webSearches": state.get("search_attempts", 0),
        "sourceReads": len(state.get("read_source_refs", [])),
        "sourceCount": len(state.get("web_sources", [])),
        "cardType": result.card_intent.card_type if result.card_intent else None,
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--prompt", default="五月天的《拥抱》是一首什么样的歌曲？")
    args = parser.parse_args()
    print(json.dumps(asyncio.run(benchmark(args.prompt)), ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
