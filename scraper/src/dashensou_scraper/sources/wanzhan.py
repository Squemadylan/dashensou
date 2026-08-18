"""万站聚合 (wzapi.com) — JSON-only aggregator with API keys.

参考 Kotlin 实现：WanzhanApiSource.kt
要点：
    - 普通 JSON HTTP，纯 fetch 即可
    - 多 Key 重试 / 限流，按需实现（这里只演示最小骨架）
"""

from __future__ import annotations

import json
from typing import Optional
from urllib.parse import urlencode

from ..models import NetDiskType, ResourceCategory, SearchResult
from .base import FetchPlan, SearchSource


_CLOUD_MAP = {
    "baidu": NetDiskType.BAIDU,
    "quark": NetDiskType.QUARK,
    "xunlei": NetDiskType.XUNLEI,
    "aliyun": NetDiskType.ALIYUN,
    "aliyunpan": NetDiskType.ALIYUN,
    "123": NetDiskType.YUNPAN123,
    "123pan": NetDiskType.YUNPAN123,
}


class WanzhanApiSource(SearchSource):
    id = "wanzhan"
    display_name = "万站聚合"
    enabled = True
    base_url = "https://wzapi.com/api/jhsj"

    def __init__(self, api_keys: Optional[list[str]] = None) -> None:
        self.api_keys = list(api_keys or [])

    def fetch_plan(self) -> FetchPlan:
        return FetchPlan(
            fetcher="fetch",
            impersonate="chrome",
            charset="utf-8",
            timeout_s=12,
        )

    def build_url(self, keyword: str, page: int) -> str:
        params = {"kw": keyword.strip(), "page": str(page)}
        key = self.api_keys[0] if self.api_keys else None
        if key:
            params["apiKey"] = key
        return f"{self.base_url}?{urlencode(params)}"

    def parse(self, body: str, category: ResourceCategory) -> list[SearchResult]:
        try:
            root = json.loads(body)
        except Exception:
            return []

        code = root.get("code", -1)
        if code != 0:
            return []

        data = (root.get("data") or {}).get("merged_by_type") or {}
        out: list[SearchResult] = []
        for ctype, arr in data.items():
            net_disk = _CLOUD_MAP.get(ctype.lower(), NetDiskType.OTHER)
            for i, item in enumerate(arr or []):
                url = (item or {}).get("url") or ""
                if not url:
                    continue
                title = (item or {}).get("title") or ""
                note = (item or {}).get("note") or ""
                eff_title = title or (note or ctype)
                source = (item or {}).get("source") or ""
                full_title = (
                    f"{eff_title} · {source}" if source else eff_title
                )
                pwd = (item or {}).get("password") or ""
                out.append(
                    SearchResult(
                        id=f"wanzhan-{ctype}-{i}-{hash(url)}",
                        title=full_title,
                        description=f"{ctype.upper()} · {source}",
                        url=url,
                        netDiskType=net_disk,
                        size="",
                        date=(item or {}).get("datetime") or "",
                        sourceUrl=url,
                        sourceName=self.display_name,
                        sourceId=self.id,
                        category=category,
                        fileType="",
                        isValid=True,
                        requiresWebView=False,
                        extractionCode=pwd or None,
                    )
                )
        return out


__all__ = ["WanzhanApiSource"]
