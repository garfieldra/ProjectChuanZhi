import asyncio

import httpx
import pytest

from app.lease_heartbeat import maintain_lease


@pytest.mark.asyncio
async def test_transient_heartbeat_failure_is_retried_until_success():
    stop, lease_lost = asyncio.Event(), asyncio.Event()
    calls = 0

    async def heartbeat():
        nonlocal calls
        calls += 1
        if calls == 1:
            raise httpx.ConnectError("temporary connection loss")
        stop.set()

    await asyncio.wait_for(
        maintain_lease(stop, lease_lost, heartbeat, interval_seconds=0.001, retry_seconds=0.001),
        timeout=1,
    )
    assert calls == 2
    assert not lease_lost.is_set()


@pytest.mark.asyncio
async def test_fenced_heartbeat_stops_and_marks_lease_lost():
    stop, lease_lost = asyncio.Event(), asyncio.Event()
    calls = 0

    async def heartbeat():
        nonlocal calls
        calls += 1
        request = httpx.Request("POST", "http://java-service/internal/v1/agent-runs/test/heartbeat")
        response = httpx.Response(409, request=request)
        raise httpx.HTTPStatusError("lease rejected", request=request, response=response)

    await asyncio.wait_for(
        maintain_lease(stop, lease_lost, heartbeat, interval_seconds=0.001, retry_seconds=0.001),
        timeout=1,
    )
    assert calls == 1
    assert lease_lost.is_set()


@pytest.mark.asyncio
async def test_server_error_does_not_kill_heartbeat_task():
    stop, lease_lost = asyncio.Event(), asyncio.Event()
    calls = 0

    async def heartbeat():
        nonlocal calls
        calls += 1
        if calls == 1:
            request = httpx.Request("POST", "http://java-service/internal/v1/agent-runs/test/heartbeat")
            response = httpx.Response(503, request=request)
            raise httpx.HTTPStatusError("temporarily unavailable", request=request, response=response)
        stop.set()

    await asyncio.wait_for(
        maintain_lease(stop, lease_lost, heartbeat, interval_seconds=0.001, retry_seconds=0.001),
        timeout=1,
    )
    assert calls == 2
    assert not lease_lost.is_set()
