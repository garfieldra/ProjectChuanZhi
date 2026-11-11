import asyncio
from types import SimpleNamespace
from uuid import uuid4

import pytest

from app.conversation_graph import ConversationDecision, ConversationReActRuntime, MusicPreferenceProfile
from app.music_analysis import MusicAnalysisWorkspace
from app.schemas import ConversationAgentRequest


@pytest.mark.parametrize('entity_type,scope', [('work', 'RELATED_CONTEXT'), ('recording', 'STRICT')])
def test_react_research_then_knowledge_uses_verified_subject(entity_type, scope):
    subject_id, artist_id = str(uuid4()), str(uuid4())
    calls = []
    class Catalog:
        async def research_musicbrainz(self, *args):
            return [{'sourceTitle': '目录核验', 'sourceUrl': f'https://musicbrainz.org/{entity_type}/{subject_id}',
                     'searchProvider': 'musicbrainz', 'entityType': entity_type, 'musicbrainzMbid': subject_id,
                     'ambiguous': False, 'summary': '已核验具体作品或录音'}]
    class Knowledge:
        async def search(self, request):
            calls.append(request)
            return {'status': 'no_match', 'evidence': []}
    runtime = ConversationReActRuntime(SimpleNamespace(), Knowledge(), catalog=Catalog())
    async def preference(_request): return MusicPreferenceProfile(named_artists=['艺人'])
    async def resolve(*_args): return ([{'status': 'RESOLVED', 'mbid': artist_id}], [])
    async def plan(_request): return MusicAnalysisWorkspace(subject='测试作品', question='分析测试作品的背景')
    async def decide(state):
        previous = {a['action'] for a in state['action_history']}
        action = 'research_musicbrainz' if 'research_musicbrainz' not in previous else ('search_knowledge' if 'search_knowledge' not in previous else 'respond')
        return ConversationDecision(action=action, public_summary='自主选择下一项研究动作', entity_type=entity_type)
    async def answer(*_args): return '测试回答，仅验证工具契约。'
    runtime.analyze_preference, runtime.resolve_entities, runtime.plan_analysis_workspace = preference, resolve, plan
    runtime.decide, runtime.answer = decide, answer
    request = ConversationAgentRequest(request_id=uuid4(), agent_run_id=uuid4(), conversation_id=uuid4(), guest_id='test', user_message='深入分析《测试作品》的背景')
    asyncio.run(runtime.graph.ainvoke({'request': request}, {'configurable': {'thread_id': str(uuid4())}, 'recursion_limit': 20}))
    assert len(calls) == 1
    assert calls[0]['entities'] == [{'type': entity_type.upper(), 'id': subject_id, 'role': 'SUBJECT'}]
    assert calls[0]['entityScope'] == scope
