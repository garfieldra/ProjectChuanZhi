import asyncio
import json
from types import SimpleNamespace

import httpx
import pytest

from app.knowledge import KnowledgeRetriever, VersionedIndex, begin_evidence_session, finalize_evidence_session, evidence_source, fuse, row_id, stable_sources


class Index:
    def __init__(self, fail=False): self.fail = fail
    def search(self, request, config):
        if self.fail: raise OSError('vector store offline')
        return [{'entryId': 'old', 'revision': 1}, {'entryId': 'new', 'revision': 2}]


def backend(verify_available=True, exact_available=True):
    def handler(request):
        path = request.url.path
        if path.endswith('/scope'):
            return httpx.Response(200, json=json.loads(request.content))
        if path.endswith('/generation'):
            return httpx.Response(200, json={'id': 1, 'config': {}})
        if path.endswith('/exact'):
            return httpx.Response(200 if exact_available else 503, json={'hits': [{'entryId': 'new', 'revision': 2}]})
        if path.endswith('/verify'):
            return httpx.Response(200 if verify_available else 503, json={'evidence': [{'entryId': 'new', 'revision': 2, 'entities': []}]})
        raise AssertionError(path)
    return httpx.MockTransport(handler)


def test_only_authorized_current_revisions_returned():
    retriever = KnowledgeRetriever('http://java', 'token', '', transport=backend(), index=Index())
    result = asyncio.run(retriever.search({'query': '作品背景'}))
    assert result['status'] == 'hit'
    assert result['evidence'] == [{'entryId': 'new', 'revision': 2, 'entities': []}]


def test_vector_failure_preserves_authorized_exact_results():
    result = asyncio.run(KnowledgeRetriever('http://java', 'token', '', transport=backend(), index=Index(True)).search({'query': '背景'}))
    assert result['status'] == 'partial'
    assert result['evidence'][0]['entryId'] == 'new'


def test_authority_failure_never_exposes_raw_index_hits():
    result = asyncio.run(KnowledgeRetriever('http://java', 'token', '', transport=backend(False), index=Index()).search({'query': '背景'}))
    assert result['status'] == 'unavailable'
    assert result['evidence'] == []


def test_duplicate_hits_are_fused_by_revision_and_ranks():
    a, b = {'entryId': 'a', 'revision': 1}, {'entryId': 'b', 'revision': 1}
    assert fuse([[a, a, b], [b]])[0] == b
    assert row_id('a', 1, 2) != row_id('a', 2, 2)


def test_source_refs_remain_stable_across_reordering_and_local_sources():
    registry = {}
    original = stable_sources([{'sourceUrl': 'https://a'}, {'sourceUrl': 'https://b'}], registry)
    reordered = stable_sources([{'sourceUrl': 'https://b'}, {'sourceUrl': 'https://a'}], registry)
    assert original[0]['ref'] == reordered[1]['ref']
    local = evidence_source({'entryId': 'id', 'revision': 2, 'statement': '背景', 'support': [{'relation': 'SUPPORTS', 'excerpt': '依据'}]})
    assert stable_sources([local], registry)[0]['ref'] == 'S3'
    assert local['sourceUrl'] == ''  # no fabricated website for curator notes


def test_missing_entity_coverage_is_explicit():
    result = asyncio.run(KnowledgeRetriever('http://java', 'token', '', transport=backend(), index=Index()).search({'query': '比较', 'entities': [{'type': 'ARTIST', 'id': 'a'}]}))
    assert result['status'] == 'partial'
    assert result['coverage']['completeForRequestedEntities'] is False


def test_unrelated_vectors_are_not_automatically_evidence():
    class Client:
        def has_collection(self, _name): return True
        def search(self, **kwargs):
            return [[{'distance': .2, 'entity': {'entryId': 'irrelevant', 'revision': 1}},
                     {'distance': .9, 'entity': {'entryId': 'related', 'revision': 1}}]]
    index = VersionedIndex('unused')
    index.client = lambda: Client()
    index.vector = lambda *_args: [0.1]
    hits = index.search({'query': '背景', 'knowledgeTypes': ['FACT']}, {'factCollection': 'facts', 'themeCollection': 'themes'})
    assert hits == [{'entryId': 'related', 'revision': 1}]


def test_composite_tools_preserve_references_without_leaking_between_runs():
    async def exercise():
        begin_evidence_session()
        retriever = KnowledgeRetriever('http://java', 'token', '', transport=backend(), index=Index())
        await asyncio.gather(retriever.search({'query': '背景'}), retriever.search({'query': '作品'}))
        result = SimpleNamespace(knowledge_sources=[])
        await finalize_evidence_session(result, retriever)
        assert len(result.knowledge_sources) == 1
        assert result.knowledge_sources[0]['entryId'] == 'new'
        begin_evidence_session()
        following = SimpleNamespace(knowledge_sources=[])
        await finalize_evidence_session(following, retriever)
        assert following.knowledge_sources == []
    asyncio.run(exercise())


def test_document_preflight_uses_the_same_template_and_token_limit_as_indexing():
    from app.knowledge import document_text
    encoded = []
    class Tokenizer:
        def encode(self, text):
            encoded.append(text)
            return SimpleNamespace(ids=list(range(len(text))))
    class Model:
        model = SimpleNamespace(tokenizer=Tokenizer())
        def embed(self, texts):
            return iter([SimpleNamespace(tolist=lambda: [0.1])])
    index = VersionedIndex('unused')
    index.model = lambda _config: Model()
    content = {'summary': '短表述', 'entities': []}
    index.validate_document(content, {})
    assert encoded[-1] == document_text(content)
    index.vector(document_text(content), {'dimension': 1})
    with pytest.raises(ValueError, match='KNOWLEDGE_INPUT_TOO_LONG'):
        index.validate_document({**content, 'summary': '长' * 500}, {})
    with pytest.raises(ValueError, match='KNOWLEDGE_INPUT_TOO_LONG'):
        index.vector(document_text({**content, 'summary': '长' * 500}), {'dimension': 1})
