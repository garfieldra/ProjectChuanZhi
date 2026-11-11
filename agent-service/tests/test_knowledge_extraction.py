import asyncio
from types import SimpleNamespace

import pytest

from app.knowledge_extraction import KnowledgeCandidate, KnowledgeCandidates, extract_candidates


def test_extraction_without_model_requires_manual_editing():
    result = asyncio.run(extract_candidates(SimpleNamespace(model=None), '维护者选择的公开材料。'))
    assert result['status'] == 'manual_review_required'
    assert result['candidates'] == []


def test_extraction_cannot_fabricate_support_or_publish():
    text = '作者在这次正式采访中介绍了作品的创作背景。'
    async def invoke(schema, prompt):
        assert schema is KnowledgeCandidates
        assert text in prompt
        return KnowledgeCandidates(candidates=[
            KnowledgeCandidate(summary='采访介绍了作品创作背景', knowledge_type='FACT', excerpt=text, locator='访谈段落'),
            KnowledgeCandidate(summary='这一说法没有取得材料支持', knowledge_type='FACT', excerpt='这句话并不存在于提交的材料之中。', locator='未知'),
        ])
    result = asyncio.run(extract_candidates(SimpleNamespace(model=object(), _invoke_validated=invoke), text))
    assert result['status'] == 'candidate'
    assert len(result['candidates']) == 1
    assert 'reviewStatus' not in result['candidates'][0]
    assert 'entityIds' not in result['candidates'][0]


def test_material_limit_is_explicit_instead_of_truncation():
    with pytest.raises(ValueError):
        asyncio.run(extract_candidates(SimpleNamespace(model=None), '资料' * 7000))
