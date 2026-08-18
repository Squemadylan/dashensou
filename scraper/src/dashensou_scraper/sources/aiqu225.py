"""aiqu225.com — Chinese ebook aggregator, GBK-encoded pages.

参考 Kotlin 实现：AiQuSource.kt
要点：
    - 整站 GBK，需要在 storage 层按 GBK 解码
    - 普通 fetch 足够
    - detail 二跳：详情页 → /txt-xx/softdownfree.asp → .txt 镜像直链
"""

from __future__ import annotations

from typing import Optional
from urllib.parse import quote

from ..http import get as http_get
from ..models import NetDiskType, ResourceCategory, SearchResult
from .base import DetailInfo, FetchPlan, SearchSource

# Mirror labels from Kotlin: 5 candidates, "在线阅读" is excluded (HTML reader, not .txt).
_DOWNLOAD_LABELS = (
    "第一下载地址",
    "第二下载地址",
    "第三下载地址",
    "第四下载地址",
    "第五下载地址",
)


class AiQu225Source(SearchSource):
    id = "aiqu225"
    display_name = "电子书搜索"
    enabled = True
    base_url = "https://www.aiqu225.com"
    per_source_timeout_ms = 5_000

    def fetch_plan(self) -> FetchPlan:
        return FetchPlan(
            fetcher="fetch",
            impersonate="chrome",
            charset="gbk",
            timeout_s=10,
        )

    def build_url(self, keyword: str, page: int) -> str:
        encoded = quote(keyword.strip(), encoding="gbk", errors="strict")
        return f"{self.base_url}/search.asp?word={encoded}"

    def parse(self, body: str, category: ResourceCategory) -> list[SearchResult]:
        if category not in (ResourceCategory.ALL, ResourceCategory.EBOOK):
            return []

        from scrapling.parser import Adaptor

        page = Adaptor(content=body)
        out: list[SearchResult] = []
        for card in page.css("div.search-card"):
            try:
                title_sel = card.css("a.searchtitle")
                if not title_sel:
                    continue
                title_el = title_sel.first
                title_raw = title_el.text or ""
                title = title_raw.strip() if isinstance(title_raw, str) else str(title_raw)
                if not title:
                    continue
                href = title_el.attrib.get("href", "") if hasattr(title_el, "attrib") else ""
                detail_url = href if href.startswith("http") else self.base_url + href

                author_sel = card.css(".search-card-author")
                author = ""
                if author_sel and author_sel.first:
                    author = (author_sel.first.text or "").replace(
                        "作者：", ""
                    ).replace("作者:", "").strip()

                cat_sel = card.css(".search-card-category a")
                cat_el = cat_sel.first if cat_sel else None
                if not cat_el:
                    cat_sel2 = card.css(".search-card-category")
                    cat_el = cat_sel2.first if cat_sel2 else None
                cat = (cat_el.text or "").strip() if cat_el else ""

                date_sel = card.css(".oldDate")
                if not (date_sel and date_sel.first):
                    date_sel = card.css(".search-card-date")
                date = (
                    (date_sel.first.text or "").strip()
                    if date_sel and date_sel.first
                    else ""
                )

                content_sel = card.css(".search-card-content")
                content = (
                    (content_sel.first.text or "").strip()
                    if content_sel and content_sel.first
                    else ""
                )

                desc_parts = []
                if author:
                    desc_parts.append(f"作者：{author}")
                if cat:
                    desc_parts.append(cat)
                if content:
                    snippet = content[:80] + ("..." if len(content) > 80 else "")
                    desc_parts.append(snippet)
                description = " · ".join(desc_parts)

                out.append(
                    SearchResult(
                        id=f"aiqu-{hash(detail_url)}",
                        title=title,
                        description=description,
                        url=detail_url,
                        netDiskType=NetDiskType.DIRECT_URL,
                        size="",
                        date=date,
                        sourceUrl=detail_url,
                        sourceName=self.display_name,
                        sourceId=self.id,
                        category=ResourceCategory.EBOOK,
                        fileType="txt",
                        isValid=True,
                        requiresWebView=True,
                    )
                )
            except Exception:
                continue
        return out

    # --- detail (Kotlin: AiQuSource.fetchDetail) ---------------------------
    def supports_detail(self) -> bool:
        return True

    async def fetch_detail(self, detail_url: str) -> Optional[DetailInfo]:
        """Two-step: detail page → softdownfree.asp → .txt mirror.

        Returns:
            DetailInfo with gotoUrl = real .txt mirror URL on txt*.downbook* host.
        """
        softdown = await self._resolve_softdown_url(detail_url)
        if not softdown:
            return None
        mirror = await self._fetch_first_txt_mirror(softdown)
        if not mirror:
            return None
        return DetailInfo(
            netDiskType=NetDiskType.DIRECT_URL,
            password=None,
            gotoUrl=mirror,
        )

    async def _http_get_gbk(self, url: str) -> Optional[str]:
        import asyncio
        try:
            r = await asyncio.to_thread(
                http_get, url, fetcher="fetch", impersonate="chrome", timeout=8
            )
        except Exception:
            return None
        if not r.body:
            return None
        return r.body.decode("gbk", errors="replace")

    async def _resolve_softdown_url(self, detail_url: str) -> Optional[str]:
        html = await self._http_get_gbk(detail_url)
        if not html:
            return None
        from scrapling.parser import Adaptor

        page = Adaptor(content=html)
        # Use substring match that doesn't include `/`; the value `softdownfree.asp`
        # is enough to disambiguate from any other link on a book detail page.
        s = page.css("a[href*=\"softdownfree.asp\"]")
        if not s or not s.first:
            return None
        href = s.first.attrib.get("href", "")
        if not href:
            return None
        if href.startswith("http"):
            return href
        return self.base_url + href

    async def _fetch_first_txt_mirror(self, softdown_url: str) -> Optional[str]:
        html = await self._http_get_gbk(softdown_url)
        if not html:
            return None
        from scrapling.parser import Adaptor

        page = Adaptor(content=html)
        anchors = page.css("a[href]")

        def _href_of(el) -> str:
            href = el.attrib.get("href", "") if hasattr(el, "attrib") else ""
            return href if href.startswith("http") else self.base_url + href

        # 1) labelled "第N下载地址" (preferred: real .txt on txt*.downbook* CDN)
        for label in _DOWNLOAD_LABELS:
            for a in anchors:
                txt = a.text or ""
                if label in txt:
                    href = _href_of(a)
                    if href:
                        return href

        # 2) any absolute http(s) link ending in .txt
        for a in anchors:
            href = _href_of(a)
            if href.startswith("http") and href.lower().endswith(".txt"):
                return href

        return None


__all__ = ["AiQu225Source"]
