"""Keep a worker lease alive through transient callback failures."""

from __future__ import annotations

import asyncio
import logging
from collections.abc import Awaitable, Callable

import httpx

LOGGER = logging.getLogger(__name__)


async def maintain_lease(
    stop: asyncio.Event,
    lease_lost: asyncio.Event,
    heartbeat: Callable[[], Awaitable[None]],
    *,
    interval_seconds: float = 10,
    retry_seconds: float = 2,
) -> None:
    """Retry transient failures; never silently stop heartbeating.

    A 409/401/403 means the old worker no longer owns the attempt. Other
    network/server errors are retried until the worker stops or fencing rejects
    the lease. The caller keeps heartbeat HTTP on a separate client.
    """
    delay = interval_seconds
    while not stop.is_set():
        try:
            await asyncio.wait_for(stop.wait(), timeout=delay)
            return
        except TimeoutError:
            pass
        try:
            await heartbeat()
        except httpx.HTTPStatusError as exc:
            status = exc.response.status_code
            if status in {401, 403, 404, 409}:
                LOGGER.warning("Agent lease heartbeat rejected: HTTP %s", status)
                lease_lost.set()
                return
            LOGGER.warning("Agent lease heartbeat unavailable: HTTP %s", status)
            delay = retry_seconds
        except (httpx.RequestError, TimeoutError) as exc:
            LOGGER.warning("Agent lease heartbeat unavailable: %s", type(exc).__name__)
            delay = retry_seconds
        except Exception as exc:
            LOGGER.exception("Agent lease heartbeat failed: %s", type(exc).__name__)
            delay = retry_seconds
        else:
            delay = interval_seconds
