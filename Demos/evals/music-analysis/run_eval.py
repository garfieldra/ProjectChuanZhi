#!/usr/bin/env python3
"""Live, public-API evaluation of music research and multi-turn analysis."""

from __future__ import annotations

import argparse
import json
import sys
import time
from datetime import datetime, timezone
from pathlib import Path
from uuid import uuid4

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parents[1]))
from api_client import api_client  # noqa: E402

TERMINAL = {"COMPLETED", "FAILED", "CANCELLED", "EXPIRED", "WAITING_FOR_USER"}


def read_cases(path: Path) -> list[dict]:
    with path.open(encoding="utf-8") as handle:
        return [json.loads(line) for line in handle if line.strip()]


def evaluate_messages(case: dict, messages: list[dict], status: str) -> tuple[list[str], dict]:
    errors = []
    if status != "COMPLETED":
        errors.append(f"run_status:{status}")
    answers = [str(item.get("content") or "") for item in messages if item.get("type") == "AGENT_TEXT"]
    answer = answers[-1] if answers else ""
    if not answer.strip():
        errors.append("empty_answer")
    cards = [item for item in messages if item.get("cardType") == case.get("expectedCard")]
    if case.get("expectedCard") and not cards:
        errors.append(f"missing_card:{case['expectedCard']}")
    payload = {}
    if cards:
        try:
            payload = json.loads(cards[-1].get("cardPayloadJson") or "{}")
        except json.JSONDecodeError:
            errors.append("invalid_card_json")
    if case.get("subjectContains") and payload:
        if case["subjectContains"] not in str(payload.get("subject") or ""):
            errors.append("wrong_analysis_subject")
    if case.get("expectRefinement") and payload.get("contextMode") != "REFINED":
        errors.append("missing_refinement")
    if case.get("requiresFullText") and payload:
        coverage = payload.get("evidenceCoverage") or {}
        if not coverage.get("requires_full_text"):
            errors.append("fact_heavy_not_recognized")
    songs = payload.get("songs") or []
    if case.get("expectedSongCount") is not None and len(songs) != case["expectedSongCount"]:
        errors.append(f"wrong_song_count:{len(songs)}")
    excluded_mbids = set(case.get("excludedArtistMbids") or [])
    if any(str(song.get("artistMbid") or "") in excluded_mbids for song in songs):
        errors.append("excluded_artist_recommended")
    sources = payload.get("sources") or []
    refs = {str(source.get("ref")) for source in sources if source.get("ref")}
    claims = payload.get("claims") or []
    for index, claim in enumerate(claims):
        for ref in claim.get("evidenceRefs") or []:
            if str(ref) not in refs:
                errors.append(f"claim_{index}_unknown_ref")
        if claim.get("claimType") == "FACT" and claim.get("status") != "unsupported" and not claim.get("evidenceRefs"):
            errors.append(f"claim_{index}_unsupported_fact")
    metrics = {
        "answerCharacters": len(answer), "sourceCount": len(sources), "songCount": len(songs),
        "claimCount": len(claims), "supportedFactCount": sum(
            1 for item in claims if item.get("claimType") == "FACT" and item.get("evidenceRefs")
        ),
    }
    return errors, {"answer": answer, "card": payload, "metrics": metrics}


def wait_for_run(client, run_id: str, deadline_seconds: int) -> tuple[str, list[dict]]:
    deadline = time.monotonic() + deadline_seconds
    while time.monotonic() < deadline:
        snapshot = client.get(f"/api/v1/agent-runs/{run_id}/events").raise_for_status().json()
        status = snapshot.get("runStatus", "UNKNOWN")
        if status in TERMINAL:
            return status, snapshot.get("events") or []
        time.sleep(2)
    return "TIMEOUT", []


def run_case(client, case: dict, timeout: int) -> dict:
    started = time.monotonic()
    conversation_id = client.post("/api/v1/conversations").raise_for_status().json()["id"]
    turns = []
    for prompt in case["prompts"]:
        queued = client.post(
            f"/api/v1/conversations/{conversation_id}/messages",
            headers={"Idempotency-Key": str(uuid4())},
            json={"content": prompt},
        ).raise_for_status().json()
        status, events = wait_for_run(client, queued["runId"], timeout)
        turns.append({"runId": queued["runId"], "status": status, "events": events})
        if status != "COMPLETED":
            break
    messages = client.get(f"/api/v1/conversations/{conversation_id}/messages").raise_for_status().json()
    status = turns[-1]["status"] if turns else "NOT_STARTED"
    errors, evaluated = evaluate_messages(case, messages, status)
    tool_starts = []
    for turn in turns:
        for event in turn["events"]:
            if event.get("type") == "TOOL_STARTED":
                try:
                    tool_starts.append(json.loads(event.get("payloadJson") or "{}").get("toolKey"))
                except json.JSONDecodeError:
                    continue
    metrics = evaluated["metrics"]
    metrics.update({
        "durationSeconds": round(time.monotonic() - started, 2),
        "searchCalls": tool_starts.count("search_web"),
        "sourceReadCalls": tool_starts.count("read_source"),
        "workerRecoveryCount": sum(
            1 for turn in turns for event in turn["events"] if event.get("type") == "RETRY"
        ),
    })
    return {
        "caseId": case["caseId"], "category": case["category"], "conversationId": conversation_id,
        "turns": turns, "errors": errors, "passed": not errors,
        "answer": evaluated["answer"], "card": evaluated["card"], "metrics": metrics,
    }


def compare(results: list[dict], baseline: dict) -> list[dict]:
    previous = {item["caseId"]: item for item in baseline.get("results") or []}
    changes = []
    for item in results:
        old = previous.get(item["caseId"])
        if old:
            changes.append({
                "caseId": item["caseId"],
                "passedBefore": old.get("passed"), "passedNow": item["passed"],
                "durationDeltaSeconds": round(item["metrics"]["durationSeconds"] - old.get("metrics", {}).get("durationSeconds", 0), 2),
                "sourceDelta": item["metrics"]["sourceCount"] - old.get("metrics", {}).get("sourceCount", 0),
            })
    return changes


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://127.0.0.1:8080")
    parser.add_argument("--cases", type=Path, default=HERE / "cases.jsonl")
    parser.add_argument("--case", action="append", dest="selected")
    parser.add_argument("--timeout", type=int, default=720, help="Seconds allowed per Agent turn")
    parser.add_argument("--baseline", type=Path)
    parser.add_argument("--output-dir", type=Path, default=HERE / "reports")
    args = parser.parse_args()
    cases = read_cases(args.cases)
    if args.selected:
        cases = [case for case in cases if case["caseId"] in args.selected]
    if not cases:
        parser.error("No matching cases")
    results = []
    with api_client(args.base_url, timeout=30) as client:
        for case in cases:
            try:
                result = run_case(client, case, args.timeout)
            except Exception as error:
                result = {"caseId": case["caseId"], "category": case["category"],
                          "passed": False, "errors": [f"{type(error).__name__}: {error}"], "metrics": {}}
            results.append(result)
            print(json.dumps({"caseId": result["caseId"], "passed": result["passed"], "errors": result["errors"],
                              "metrics": result["metrics"]}, ensure_ascii=False), flush=True)
    durations = sorted(item.get("metrics", {}).get("durationSeconds", 0) for item in results)
    payload = {
        "generatedAt": datetime.now(timezone.utc).isoformat(), "baseUrl": args.base_url,
        "summary": {"caseCount": len(results), "passed": sum(item["passed"] for item in results),
                    "failed": sum(not item["passed"] for item in results),
                    "p95DurationSeconds": durations[max(0, (len(durations) * 95 + 99) // 100 - 1)]},
        "results": results,
    }
    if args.baseline:
        payload["comparison"] = compare(results, json.loads(args.baseline.read_text(encoding="utf-8")))
    args.output_dir.mkdir(parents=True, exist_ok=True)
    output = args.output_dir / f"analysis-eval-{datetime.now(timezone.utc):%Y%m%dT%H%M%SZ}.json"
    output.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({"report": str(output), "summary": payload["summary"]}, ensure_ascii=False), flush=True)
    return 0 if not payload["summary"]["failed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
