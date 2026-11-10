"""Destructive-to-fixtures-only RAG integration check. Requires an explicitly isolated server.

Run: python Demos/verify_knowledge_api.py --isolated --base-url http://127.0.0.1:18080
The server must allow maintainer@rag.test. Never enable that fixture account in production.
"""
from __future__ import annotations

import argparse
import json
import time
from uuid import uuid4

import httpx


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--isolated', action='store_true')
    parser.add_argument('--base-url', default='http://127.0.0.1:18080')
    parser.add_argument('--timeout', type=int, default=360)
    args = parser.parse_args()
    if not args.isolated:
        parser.error('Explicit --isolated is required. This command creates synthetic fixture accounts and evidence.')
    prefix = '/api/v1/admin/knowledge'
    ready = time.monotonic() + 60
    while time.monotonic() < ready:
        try:
            if httpx.get(args.base_url + '/actuator/health', timeout=2).status_code == 200:
                break
        except httpx.HTTPError:
            pass
        time.sleep(1)
    else:
        raise AssertionError('Isolated Java server did not become ready')
    with httpx.Client(base_url=args.base_url, timeout=30) as client:
        def send(method, path, body=None, expected=200, key=None):
            headers = {'X-XSRF-TOKEN': client.cookies.get('XSRF-TOKEN', ''), 'Idempotency-Key': key or str(uuid4())}
            response = client.request(method, path, json=body, headers=headers)
            assert response.status_code == expected, (path, response.status_code, response.text[:300])
            return response.json() if response.content else None
        send('GET', '/api/v1/auth/session')
        send('GET', prefix + '/entries', expected=403)
        send('GET', '/internal/v1/knowledge/generation', expected=403)
        credentials = {'email': 'maintainer@rag.test', 'password': 'Rag-fixture-password-2026!', 'displayName': '隔离验收维护者'}
        response = client.post('/api/v1/auth/register', json=credentials, headers={'X-XSRF-TOKEN': client.cookies.get('XSRF-TOKEN', '')})
        if response.status_code == 409:
            send('POST', '/api/v1/auth/login', credentials)
        else:
            assert response.status_code == 201, response.text
        send('GET', prefix + '/access')
        identifier = str(uuid4())
        entity = {'type': 'WORK', 'id': str(uuid4()), 'role': 'SUBJECT'}
        item = {'knowledgeType': 'FACT', 'summary': '虚构测试作品的样本编制是木吉他与人声；此条仅用于软件验收。',
                'boundary': '合成测试材料，不能用于真实音乐事实。', 'aliases': ['RAG验收作品' + identifier],
                'entities': [entity], 'support': [{'source': {'identifier': 'rag-fixture:' + identifier, 'title': '合成测试档案 ' + identifier,
                    'url': '', 'publisher': '隔离测试', 'rightsNote': '本测试自行生成的虚构材料', 'materialType': 'TEST_FIXTURE'},
                    'excerpt': '虚构测试作品的样本编制是木吉他与人声；此条仅用于软件验收。', 'locator': '测试段落一', 'relation': 'SUPPORTS'}]}
        batch = {'origin': 'MANUAL_EDIT', 'candidateItems': [item]}
        key = str(uuid4())
        imported = send('POST', prefix + '/imports', batch, key=key)
        assert send('POST', prefix + '/imports', batch, key=key) == imported
        entry = imported['results'][0]['entryId']
        assert send('GET', f'/api/v1/evidence/{entry}/1')['status'] == 'unavailable'
        send('POST', prefix + f'/entries/{entry}/publish', {'revision': 1}, expected=409)
        send('POST', prefix + f'/entries/{entry}/review', {'revision': 1, 'status': 'APPROVED', 'note': '核对合成测试材料'})
        send('POST', prefix + f'/entries/{entry}/publish', {'revision': 1})
        print('PASS guest/internal authorization, draft isolation, review gate and idempotent import', flush=True)
        deadline = time.monotonic() + args.timeout
        last_status = None
        while time.monotonic() < deadline:
            jobs = [j for j in send('GET', prefix + '/jobs') if j['entry_id'] == entry and j['operation'] == 'UPSERT']
            status = jobs[0]['status'] if jobs else 'NOT_CREATED'
            if status != last_status:
                print('Index status:', status, flush=True); last_status = status
            if status == 'SUCCEEDED':
                break
            if status == 'DEAD':
                raise AssertionError('Index task exhausted retries')
            time.sleep(2)
        else:
            raise AssertionError('Index did not finish in time')
        query = {'query': 'RAG验收作品' + identifier, 'entities': [entity], 'entityScope': 'STRICT', 'knowledgeTypes': ['FACT'], 'limit': 6}
        result = send('POST', prefix + '/search', query)
        warm_deadline = time.monotonic() + 45
        while not result.get('diagnostics', {}).get('vectorAvailable') and time.monotonic() < warm_deadline:
            time.sleep(1)
            result = send('POST', prefix + '/search', query)
        assert any(e['entryId'] == entry and e['revision'] == 1 for e in result['evidence']), result
        assert result.get('diagnostics', {}).get('vectorCandidates', 0) > 0, result
        assert send('GET', f'/api/v1/evidence/{entry}/1')['status'] == 'active'
        print('PASS outbox, RabbitMQ worker, embedding, Milvus indexing and authorized retrieval', flush=True)
        generations = send('GET', prefix + '/generations')
        active = next(g for g in generations if g['status'] == 'ACTIVE')
        config = json.loads(active['payload_json']) if isinstance(active['payload_json'], str) else active['payload_json']
        rebuilt = send('POST', prefix + '/generations', {key: config[key] for key in ('model', 'dimension', 'modelRevision', 'templateVersion')})
        generation = rebuilt['id']
        deadline = time.monotonic() + args.timeout
        while time.monotonic() < deadline:
            pending = [j for j in send('GET', prefix + '/jobs') if j['generation'] == generation and j['operation'] == 'UPSERT' and j['status'] != 'SUCCEEDED']
            if not pending:
                break
            assert all(j['status'] != 'DEAD' for j in pending), pending
            time.sleep(2)
        else:
            raise AssertionError('New generation did not catch up')
        preview = send('POST', prefix + f'/search?generationId={generation}', query)
        assert any(e['entryId'] == entry for e in preview['evidence']), preview
        send('POST', prefix + f'/generations/{generation}/activate', {}, expected=409)
        send('POST', prefix + f'/generations/{generation}/validate', {'reason': '隔离合成检索验收通过，仅验证切换控制面'})
        send('POST', prefix + f'/generations/{generation}/activate', {})
        send('POST', prefix + f'/generations/{active["id"]}/validate', {'reason': '原代次合成检索回退检查'})
        send('POST', prefix + f'/generations/{active["id"]}/activate', {})
        print('PASS generation rebuild, preview, quality review gate, atomic switch and rollback', flush=True)
        item2 = {**item, 'summary': '修订后的虚构测试作品样本编制为钢琴与人声，仅用于软件验收。'}
        item2['support'] = [{**item['support'][0], 'excerpt': item2['summary']}]
        send('PUT', prefix + f'/entries/{entry}?expectedRevision=1', item2)
        send('POST', prefix + f'/entries/{entry}/review', {'revision': 2, 'status': 'APPROVED', 'note': '核对修订后的合成材料'})
        send('POST', prefix + f'/entries/{entry}/publish', {'revision': 2})
        assert send('GET', f'/api/v1/evidence/{entry}/1')['status'] == 'unavailable'
        result = send('POST', prefix + '/search', query)
        assert all(e['entryId'] != entry or e['revision'] == 2 for e in result['evidence'])
        assert any(e['entryId'] == entry for e in result['evidence'])
        send('POST', prefix + f'/entries/{entry}/retract', {'reason': '隔离测试结束'}, expected=200)
        assert send('GET', f'/api/v1/evidence/{entry}/2')['status'] == 'unavailable'
        result = send('POST', prefix + '/search', query)
        assert all(e['entryId'] != entry for e in result['evidence'])
        print('PASS immutable revisions, stale-vector rejection and immediate retraction', flush=True)
        print(json.dumps({'result': 'passed', 'fixtureEntryId': entry, 'boundary': 'Synthetic plumbing verification only; no claim about real music answer accuracy.'}))


if __name__ == '__main__':
    main()
