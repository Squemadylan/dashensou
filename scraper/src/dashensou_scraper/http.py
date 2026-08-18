"""Thin wrapper around Scrapling's three fetchers.

为什么自己再包一层：
    1. 源代码里只出现 `http.get(url, ...)`，不暴露 Scrapling 的类。
       后续要换成 requests / curl_cffi / 自研，调用点零改动。
    2. 每种 fetcher 都不强依赖：调用方按 (stealth, js) 两个开关选。
    3. 统一返回 FetchResult，里面同时带 raw bytes / str / status / content_type，
       让 storage 层直接落盘。
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Literal, Optional

from scrapling.fetchers import (
    DynamicFetcher,
    Fetcher,
    StealthyFetcher,
)
from scrapling.parser import Adaptor

FetcherKind = Literal["fetch", "stealthy", "dynamic"]


@dataclass(slots=True)
class FetchResult:
    body: bytes
    text: str
    status_code: int
    content_type: str
    fetcher: FetcherKind


class FetchError(RuntimeError):
    """Top-level failure (network, non-2xx, empty body, parse)."""


def _headers_to_dict(page) -> dict[str, str]:
    try:
        return {k: v for k, v in (page.headers or {}).items()}
    except Exception:
        return {}


def _to_result(page, fetcher: FetcherKind) -> FetchResult:
    body: bytes = page.body or b""
    status = getattr(page, "status", 0) or 0
    ctype = (page.headers or {}).get("Content-Type", "")
    # `page.text` is a Scrapling `TextHandler` (a lazy view), not a plain str.
    # Callers should pass `body` (or `selector(body)`) for parsing.
    # We still expose a `text` snapshot, decoded best-effort, for logs.
    try:
        text_snapshot: str = body.decode("utf-8", errors="replace")
    except Exception:
        text_snapshot = ""
    return FetchResult(
        body=body,
        text=text_snapshot,
        status_code=int(status),
        content_type=ctype,
        fetcher=fetcher,
    )


def get(
    url: str,
    *,
    fetcher: FetcherKind = "fetch",
    impersonate: str = "chrome",
    solve_cloudflare: bool = False,
    headless: bool = True,
    network_idle: bool = False,
    timeout: Optional[int] = None,
) -> FetchResult:
    """Fetch *url* and return a FetchResult.

    Args:
        url: target URL.
        fetcher: which Scrapling fetcher to use.
            - "fetch"    : plain HTTP (fast, no stealth). Default for JSON APIs.
            - "stealthy" : TLS-fingerprint / Cloudflare-aware.
            - "dynamic"  : full Chromium via Playwright. Use for JS-rendered pages.
        impersonate: TLS fingerprint preset (chrome / firefox / edge / ...).
        solve_cloudflare: only effective with fetcher="stealthy".
        headless: only effective with fetcher in {"stealthy", "dynamic"}.
        network_idle: wait for network idle after goto (dynamic only).
        timeout: seconds; None means "use library default".
    """
    common: dict = {"url": url}

    try:
        if fetcher == "fetch":
            # Fetcher.get / StealthyFetcher.fetch use **seconds** for timeout
            # (curl_cffi underlying). Default is None → library default.
            kwargs: dict = dict(common)
            if timeout is not None:
                kwargs["timeout"] = timeout
            page = Fetcher.get(**kwargs, impersonate=impersonate)
        elif fetcher == "stealthy":
            kwargs = dict(common)
            if timeout is not None:
                kwargs["timeout"] = timeout
            page = StealthyFetcher.fetch(
                **kwargs,
                headless=headless,
                solve_cloudflare=solve_cloudflare,
            )
        elif fetcher == "dynamic":
            # DynamicFetcher.fetch uses **milliseconds** for timeout.
            # Library default is 30,000 ms (30s).
            kwargs = dict(common)
            if timeout is not None:
                kwargs["timeout"] = int(timeout * 1000)
            page = DynamicFetcher.fetch(
                **kwargs,
                headless=headless,
                network_idle=network_idle,
            )
        else:  # pragma: no cover
            raise FetchError(f"unknown fetcher: {fetcher}")
    except Exception as exc:  # noqa: BLE001
        raise FetchError(f"{fetcher} {url} failed: {exc}") from exc

    return _to_result(page, fetcher)


def selector(text: str | bytes):
    """Build a Scrapling ``Adaptor`` over raw HTML.

    Returned object exposes ``.css()`` / ``.xpath()`` / ``.find_similar()``
    — same API the fetcher returns, so downstream parsers are
    interchangeable between live and cached runs.

    Raises:
        ValueError: with a clear hint when the input is not HTML
            (e.g. a JSON body that was passed in by mistake).
    """
    if isinstance(text, (bytes, bytearray)):
        text = text.decode("utf-8", errors="replace")
    head = text.lstrip()[:256].lower()
    looks_html = any(
        token in head
        for token in ("<html", "<div", "<a ", "<span", "<table", "<ul", "<li", "<p ")
    ) or head.startswith("<?xml")
    if not looks_html:
        is_json = head.startswith("{") or head.startswith("[")
        raise ValueError(
            "selector() expects HTML; got something that looks like "
            f"{'JSON' if is_json else 'non-HTML text'}. "
            "Use json.loads() for JSON sources."
        )
    return Adaptor(content=text)


__all__ = [
    "FetchResult",
    "FetchError",
    "FetcherKind",
    "get",
    "selector",
]
