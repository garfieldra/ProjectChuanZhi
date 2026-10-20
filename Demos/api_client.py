"""Session-aware client for public Java API demo scripts.

Spring Security requires the CSRF cookie value in X-XSRF-TOKEN for writes.
The token is obtained through the public auth session endpoint, not hard-coded.
"""

from __future__ import annotations

from contextlib import contextmanager
from collections.abc import Iterator

import httpx


@contextmanager
def api_client(base_url: str = "http://127.0.0.1:8080", timeout: int = 30) -> Iterator[httpx.Client]:
    with httpx.Client(base_url=base_url, timeout=timeout) as client:
        client.get("/api/v1/auth/session").raise_for_status()
        token = client.cookies.get("XSRF-TOKEN")
        if not token:
            raise RuntimeError("The Java API did not issue an XSRF-TOKEN cookie")
        client.headers["X-XSRF-TOKEN"] = token
        yield client
