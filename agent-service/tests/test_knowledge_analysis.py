import asyncio
from types import SimpleNamespace
from uuid import uuid4

from app.conversation_graph import ConversationReActRuntime
from app.knowledge import stable_sources
from app.music_analysis import EvidenceClaim, EvidenceClaimReview, MusicAnalysisDraft, MusicAnalysisReview, MusicAnalysisWorkspace, apply_claim_reviews
from app.schemas import ConversationAgentRequest


def runtime_and_state(active=True):
    evidence = {'entryId': 'fixture', 'revision': 1, 'knowledgeType': 'FACT', 'origin': 'KNOWLEDGE',
                'statement': '测试作品的样本编制是木吉他和人声。', 'entities': [],
                'support': [{'relation': 'SUPPORTS', 'sourceTitle': '合成测试材料', 'sourceId': 'fixture-source',
                             'excerpt': '测试作品的样本编制是木吉他和人声。' * 10}]}
    async def revalidate(items): return items if active else []
    tool = SimpleNamespace(retriever=SimpleNamespace(revalidate=revalidate))
    runtime = ConversationReActRuntime(SimpleNamespace(), tool)
    runtime.model = object()
    answer = '依据提供的合成测试材料，这首测试作品采用木吉他与人声的样本编制。这里只验证资料引用链路，不将测试内容当作真实音乐事实。对于音色、情绪与表达效果，可以在这一有限材料范围内提出解释，但不能据此推断具体录音参数、创作者意图或发行历史。' * 3
    claim = EvidenceClaim(statement=evidence['statement'], claim_type='FACT', dimension='样本编制', evidence_refs=['S1'], status='supported')
    async def invoke(schema, prompt):
        if schema is MusicAnalysisDraft:
            return MusicAnalysisDraft(core_judgment='依据合成测试材料，可以确认测试作品的样本编制并保留解释边界。', answer=answer, claims=[claim])
        assert schema is MusicAnalysisReview
        return MusicAnalysisReview(revised_answer=answer, items=[EvidenceClaimReview(claim_index=0, verdict='supported', supported_refs=['S1'], rationale='提供的合成材料直接支持这一样本表述。')], review_summary='逐条核对样本表述与合成材料，未扩展到真实音乐事实。')
    runtime._invoke_validated = invoke
    request = ConversationAgentRequest(request_id=uuid4(), agent_run_id=uuid4(), conversation_id=uuid4(), guest_id='fixture', user_message='测试作品的样本编制是什么？')
    workspace = MusicAnalysisWorkspace(subject='测试作品', question=request.user_message)
    return runtime, {'request': request, 'web_sources': [], 'knowledge': [evidence], 'analysis_workspace': workspace.model_dump()}


def test_curated_evidence_can_support_analysis_without_web_url():
    runtime, state = runtime_and_state()
    result = asyncio.run(runtime.analyze_music(state))
    assert result.card_intent.payload['sources'][0]['entryId'] == 'fixture'
    assert result.card_intent.payload['sources'][0]['url'] == ''
    assert result.card_intent.payload['claims'][0]['evidenceRefs'] == ['S1']
    assert result.card_intent.payload['claimReview']['semanticReviewApplied'] is True


def test_withdrawn_material_never_enters_new_analysis():
    runtime, state = runtime_and_state(active=False)
    result = asyncio.run(runtime.analyze_music(state))
    assert result.card_intent.payload['sources'] == []
    assert '没有取得可核验资料' in result.text


def test_theme_card_cannot_become_fact_after_semantic_review():
    claim = EvidenceClaim(statement='该作品的创作者明确表示它采用某种制作方法。', claim_type='FACT', dimension='制作背景', evidence_refs=['S1'])
    reviewed, _ = apply_claim_reviews([claim], [EvidenceClaimReview(claim_index=0, verdict='supported', supported_refs=['S1'], rationale='模型错误地将主题卡当成事实材料。')], {'S1'}, {'S1': {'kind': 'reviewed_theme', 'score': .55}})
    assert reviewed[0].status == 'unsupported'
    assert reviewed[0].evidence_refs == []


def test_inherited_ref_numbers_are_reserved_before_new_sources():
    registry = {}
    restored = stable_sources([{'sourceUrl': 'https://original', 'ref': 'S17'}], registry)
    following = stable_sources([{'sourceUrl': 'https://new'}], registry)
    assert restored[0]['ref'] == 'S17'
    assert following[0]['ref'] == 'S18'
