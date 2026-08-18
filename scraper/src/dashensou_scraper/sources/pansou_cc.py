"""pansou.cc — HTML aggregator, slow CDN, sometimes behind anti-bot.

参考 Kotlin 实现：PansouCcSource.kt
默认 fetcher：stealthy（CF 概率高）。
detail 二跳：详情页 → /goto/ + #pwd 提取码。
"""

from __future__ import annotations

import re
from typing import Optional
from urllib.parse import quote

from ..http import get as http_get
from ..models import NetDiskType, ResourceCategory, SearchResult
from .base import DetailInfo, FetchPlan, SearchSource

_GOTO_RE = re.compile(r"提取密码\s*</span>\s*<span[^>]*id=\"pwd\"[^>]*>([^<]+)</span>")

_NETDISK_HINTS = (
    ("百度", "baidu", NetDiskType.BAIDU),
    ("夸克", "quark", NetDiskType.QUARK),
    ("迅雷", "xunlei", NetDiskType.XUNLEI),
    ("阿里", "aliyun", NetDiskType.ALIYUN),
    ("ali", "", NetDiskType.ALIYUN),
    ("123", "", NetDiskType.YUNPAN123),
)


class PansouCcSource(SearchSource):
    id = "pansou_cc"
    display_name = "搜盘来源"
    enabled = True
    base_url = "https://pansou.cc"
    per_source_timeout_ms = 4_500

    def fetch_plan(self) -> FetchPlan:
        return FetchPlan(
            fetcher="stealthy",
            solve_cloudflare=True,
            charset="utf-8",
            timeout_s=15,
        )

    def build_url(self, keyword: str, page: int) -> str:
        return f"{self.base_url}/s/{quote(keyword.strip())}-{page}.html"

    def parse(self, body: str, category: ResourceCategory) -> list[SearchResult]:
        from scrapling.parser import Adaptor

        page = Adaptor(content=body)
        out: list[SearchResult] = []
        items = page.css("div.resource-item-wrap")
        for idx, item in enumerate(items):
            try:
                title_sel = item.css("h3.resource-title a")
                if not title_sel:
                    continue
                title_el = title_sel.first
                title_raw = title_el.text or ""
                title = title_raw.strip() if isinstance(title_raw, str) else str(title_raw)
                if not title:
                    continue
                href = (
                    title_el.attrib.get("href", "")
                    if hasattr(title_el, "attrib")
                    else ""
                )
                detail_url = href if href.startswith("http") else self.base_url + href

                size_sel = item.css(".resource-meta .em")
                size = size_sel.first.text.strip() if size_sel and size_sel.first else ""

                time_sel = item.css(".other-info .time")
                date = time_sel.first.text.strip() if time_sel and time_sel.first else ""

                out.append(
                    SearchResult(
                        id=f"pansou-{idx}-{hash(detail_url)}",
                        title=title,
                        description="",
                        url=detail_url,
                        netDiskType=NetDiskType.OTHER,  # filled in by fetch_detail
                        size=size,
                        date=date,
                        sourceUrl=detail_url,
                        sourceName=self.display_name,
                        sourceId=self.id,
                        category=category,
                        fileType="",
                        isValid=True,
                        requiresWebView=True,
                    )
                )
            except Exception:
                continue
        return out

    # --- detail (Kotlin: PansouCcSource.fetchDetail) -----------------------
    def supports_detail(self) -> bool:
        return True

    async def fetch_detail(self, detail_url: str) -> Optional[DetailInfo]:
        """One-step: load the detail page, grab /goto/ link + #pwd code."""
        import asyncio
        try:
            r = await asyncio.to_thread(
                http_get, detail_url, fetcher="stealthy", solve_cloudflare=True
            )
        except Exception:
            return None
        if not r.body:
            return None

        from scrapling.parser import Adaptor

        page = Adaptor(content=r.body)

        # /goto/ anchor (button or plain). Note: Scrapling's cssselect
        # requires attribute values to be quoted when they contain `/`.
        goto_href: Optional[str] = None
        goto_btn_text: str = ""
        for sel in ("a.button[href^=\"/goto/\"]", "a[href^=\"/goto/\"]"):
            s = page.css(sel)
            if s and s.first:
                goto_btn_text = s.first.text or ""
                href = s.first.attrib.get("href", "")
                if href:
                    goto_href = href if href.startswith("http") else self.base_url + href
                    break

        if not goto_href:
            return None

        password = self._extract_password(page)
        net_disk = self._detect_netdisk(goto_btn_text, page)

        return DetailInfo(
            netDiskType=net_disk,
            password=password,
            gotoUrl=goto_href,
        )

    @staticmethod
    def _extract_password(page) -> Optional[str]:
        """Pick the real extraction code; ignore UI chrome like "点击复制"."""
        for sel in ("#pwd", ".resource-meta #pwd", ".copy-item #pwd"):
            s = page.css(sel)
            if s and s.first:
                raw = s.first.text or ""
                v = raw.strip() if isinstance(raw, str) else str(raw).strip()
                if v and v != "点击复制" and len(v) <= 20:
                    return v
        # Regex fallback for layouts where the picker above misses it
        try:
            html = page.body.decode("utf-8", errors="replace") if isinstance(page.body, (bytes, bytearray)) else (page.body or "")
        except Exception:
            html = ""
        m = _GOTO_RE.search(html)
        if m:
            v = m.group(1).strip()
            if v and v != "点击复制":
                return v
        return None

    @staticmethod
    def _detect_netdisk(button_text: str, page) -> NetDiskType:
        title = ""
        try:
            t_sel = page.css("title")
            if t_sel and t_sel.first:
                title = t_sel.first.text or ""
        except Exception:
            pass
        source = (button_text or "") + " " + title
        for needle, latin, mapped in _NETDISK_HINTS:
            if needle in source or (latin and latin in source.lower()):
                return mapped
        return NetDiskType.OTHER


__all__ = ["PansouCcSource"]
