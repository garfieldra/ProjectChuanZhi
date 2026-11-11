"""Read-only retrieval evaluation through the maintainer API.

Set RAG_EVAL_EMAIL and RAG_EVAL_PASSWORD. No fixtures are published by this command.
Cases: query, entities, expectedEntryIds, expectNoMatch, split (dev/acceptance).
"""
from __future__ import annotations

import argparse
import json
import os
import statistics
import time
from pathlib import Path

import httpx


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--base-url', default='http://localhost:8080')
    parser.add_argument('--cases', type=Path, required=True)
    parser.add_argument('--output', type=Path, default=Path('knowledge-eval.json'))
    parser.add_argument('--split', choices=['dev', 'acceptance'])
    args = parser.parse_args()
    email, password = os.getenv('RAG_EVAL_EMAIL'), os.getenv('RAG_EVAL_PASSWORD')
    if not email or not password:
        parser.error('Set RAG_EVAL_EMAIL and RAG_EVAL_PASSWORD; credentials are never written to results.')
    cases = [json.loads(line) for line in args.cases.read_text(encoding='utf-8').splitlines() if line.strip()]
    if args.split:
        cases = [case for case in cases if case.get('split') == args.split]
    results = []
    with httpx.Client(base_url=args.base_url, timeout=30) as client:
        client.get('/api/v1/auth/session').raise_for_status()
        client.post('/api/v1/auth/login', json={'email': email, 'password': password}, headers={'X-XSRF-TOKEN': client.cookies.get('XSRF-TOKEN', '')}).raise_for_status()
        for case in cases:
            request = {'query': case['query'], 'entities': case.get('entities', []), 'entityScope': case.get('entityScope', 'RELATED_CONTEXT'), 'knowledgeTypes': case.get('knowledgeTypes', []), 'limit': 10}
            started = time.monotonic()
            response = client.post('/api/v1/admin/knowledge/search', json=request, headers={'X-XSRF-TOKEN': client.cookies.get('XSRF-TOKEN', '')})
            elapsed = int((time.monotonic() - started) * 1000)
            response.raise_for_status()
            data = response.json()
            found = [entry['entryId'] for entry in data.get('evidence', [])]
            expected = set(case.get('expectedEntryIds', []))
            ranks = [index + 1 for index, key in enumerate(found) if key in expected]
            correct_no_match = not case.get('expectNoMatch') or (not found and data['status'] == 'no_match')
            results.append({'id': case.get('id'), 'query': case['query'], 'status': data['status'], 'elapsedMs': elapsed,
                            'entryIds': found, 'recallAt10': len(set(found) & expected) / len(expected) if expected else None,
                            'reciprocalRank': 1 / min(ranks) if ranks else 0, 'correctNoMatch': correct_no_match,
                            'evidence': data.get('evidence', [])})
    recalls = [r['recallAt10'] for r in results if r['recallAt10'] is not None]
    times = sorted(r['elapsedMs'] for r in results)
    output = {'cases': len(results), 'recallAt10': statistics.mean(recalls) if recalls else None,
              'mrr': statistics.mean(r['reciprocalRank'] for r in results) if results else None,
              'p95Ms': times[min(len(times) - 1, int(len(times) * .95))] if times else None,
              'results': results, 'boundary': 'Retrieval metrics do not prove claim truth or answer fidelity; review supporting material separately.'}
    args.output.write_text(json.dumps(output, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps({key: value for key, value in output.items() if key != 'results'}, ensure_ascii=False))


if __name__ == '__main__':
    main()
