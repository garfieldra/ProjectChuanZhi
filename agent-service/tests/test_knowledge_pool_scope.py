import asyncio

import pytest

from app.tools import KnowledgeSearchTool


def test_large_candidate_pools_are_batched_without_dropping_entity_constraints():
    tool = KnowledgeSearchTool('unused')
    requested = []
    async def search(request):
        ids = [entity['id'] for entity in request['entities']]
        assert len(ids) <= 20
        requested.extend(ids)
        return {'status': 'hit', 'evidence': [{'entryId': ids[0], 'revision': 1, 'statement': '审核主题解释', 'theme': {'themes': ['夜晚']}}]}
    tool.search = search
    ids = [f'recording-{index}' for index in range(64)]
    cards = asyncio.run(tool.search_verified('夜晚', ids))
    assert requested == ids
    assert len(cards) == 4
    assert all(card['themes'] == ['夜晚'] for card in cards)


def test_optional_composite_tools_observe_store_failures():
    tool = KnowledgeSearchTool('unused')
    async def search(_request): return {'status': 'unavailable', 'evidence': []}
    tool.search = search
    with pytest.raises(RuntimeError, match='KNOWLEDGE_UNAVAILABLE'):
        asyncio.run(tool.search_verified('夜晚', []))
