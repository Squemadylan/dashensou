"""haisou.cc — Vue/Nuxt SPA, search results rendered client-side.

参考 Kotlin 实现：HaiSouSource.kt
要点：
    - SPA，必须 DynamicFetcher 渲染（Playwright），等 network_idle
    - Kotlin 端的 4 套 selector 模式（Pattern A-D）保留
    - 解析 validity（绿勾/红 X）按 Vue 组件 class 推
    - 解析 netdisk 类型按 icon class / 文本 推
"""

from __future__ import annotations

from typing import Iterator

from ..models import NetDiskType, ResourceCategory, SearchResult
from .base import FetchPlan, SearchSource


# (class-substring, text-substring, mapped type) — first match wins
_DISK_HINTS: tuple[tuple[str, str, NetDiskType], ...] = (
    ("baidu", "百度", NetDiskType.BAIDU),
    ("quark", "夸克", NetDiskType.QUARK),
    ("pank", "夸克", NetDiskType.QUARK),  # legacy class name
    ("aliyun", "阿里", NetDiskType.ALIYUN),
    ("aliyund", "阿里", NetDiskType.ALIYUN),
    ("123pan", "123", NetDiskType.YUNPAN123),
    ("123", "123", NetDiskType.YUNPAN123),
    ("uc", "uc", NetDiskType.OTHER),
    ("tianyi", "天翼", NetDiskType.OTHER),
    ("xunlei", "迅雷", NetDiskType.XUNLEI),
    ("magnet", "magnet", NetDiskType.OTHER),
    ("pikpak", "pikpak", NetDiskType.OTHER),
)


class HaiSouSource(SearchSource):
    id = "haisou"
    display_name = "海搜"
    enabled = True
    base_url = "https://haisou.cc"
    per_source_timeout_ms = 10_000

    def fetch_plan(self) -> FetchPlan:
        return FetchPlan(
            fetcher="dynamic",
            charset="utf-8",
            timeout_s=15,
        )

    def build_url(self, keyword: str, page: int) -> str:
        from urllib.parse import quote
        return f"{self.base_url}/s/{quote(keyword.strip())}-{page}.html"

    # --- parser -----------------------------------------------------------
    def parse(self, body: str, category: ResourceCategory) -> list[SearchResult]:
        from scrapling.parser import Adaptor

        page = Adaptor(content=body)
        items = list(self._collect_item_nodes(page))

        out: list[SearchResult] = []
        seen: set[tuple[str, str]] = set()
        idx = 0
        for item in items:
            try:
                result = self._parse_one(item, idx, category)
                if result is None:
                    continue
                key = (result.title, result.url)
                if key in seen:
                    continue
                seen.add(key)
                out.append(result)
                idx += 1
            except Exception:
                continue
        return out

    def _collect_item_nodes(self, page) -> Iterator:
        """4 套 selector 模式，**首套命中即停**（避免同元素被多套重复匹配）。

        Pattern A: plain rows (`div.result-item` / `search-item` / `resource-item`)
        Pattern B: Vuetify list (`v-list-item` / `.v-list-item`)
        Pattern C: anchor rows inside a container (`div.row a[href]`, etc.)
        Pattern D: any div containing a netdisk-type icon

        We pick the first pattern that yields ≥ 1 node, in order A→D. This is
        simpler and more predictable than the Kotlin version's "merge all"
        approach, which can over-collect on flat sites.
        """
        _PATTERNS = (
            "div.result-item, v-list-item.result-item, div.search-item, div.resource-item",
            ".v-list-item",
            "div.row a[href], div.result a[href]",
        )

        for selector in _PATTERNS:
            nodes = list(page.css(selector))
            if not nodes:
                continue
            is_anchor_pattern = " a[" in selector  # only Pattern C ends in `a[href]`
            for n in nodes:
                if is_anchor_pattern:
                    # Pattern C yields <a> tags; promote to their parent <div>
                    parent = n.parent if hasattr(n, "parent") else None
                    yield parent or n
                else:
                    yield n
            return  # don't try next pattern

        # Pattern D fallback (rare): any div with a netdisk-type icon inside
        for div in page.css("div"):
            try:
                if list(div.css(
                    "span[class*=baidu], span[class*=quark], "
                    "span[class*=ali], span[class*=yun], "
                    "i.mdi[class*=baidu], i.mdi[class*=quark]"
                )):
                    yield div
            except Exception:
                continue

    def _parse_one(self, item, index: int, category: ResourceCategory):
        from scrapling.parser import Adaptor  # noqa: F401  # type hint for editor

        # Title
        title_el = None
        for sel in (
            "a.title",
            "a[href*=/goto/]",
            "h3 a",
            "h4 a",
            ".title a",
            "[class*=title] a",
            "a[href]",
        ):
            s = item.css(sel)
            if s and s.first:
                title_el = s.first
                break
        if title_el is None:
            return None
        title_raw = title_el.text or ""
        title = title_raw.strip() if isinstance(title_raw, str) else str(title_raw).strip()
        if not title:
            return None

        # Detail URL
        href = title_el.attrib.get("href", "") if hasattr(title_el, "attrib") else ""
        if not href:
            return None
        if href.startswith("http"):
            detail_url = href
        elif href.startswith("/"):
            detail_url = self.base_url + href
        else:
            detail_url = self.base_url + "/" + href

        net_disk = self._detect_netdisk(item)
        is_valid = self._parse_validity(item)
        size = self._first_text(item, (".size", "[class*=size]", ".file-size", ".meta-size"))
        date = self._first_text(item, (".date", "[class*=date]", ".time", "[class*=time]"))
        description = self._first_text(
            item, (".desc", ".description", "[class*=desc]", ".source", "[class*=source]")
        )

        return SearchResult(
            id=f"haisou-{index}-{hash(detail_url)}",
            title=title,
            description=description,
            url=detail_url,
            netDiskType=net_disk,
            size=size,
            date=date,
            sourceUrl=detail_url,
            sourceName=self.display_name,
            sourceId=self.id,
            category=category,
            fileType="",
            isValid=is_valid,
            requiresWebView=False,
        )

    @staticmethod
    def _first_text(item, selectors: tuple[str, ...]) -> str:
        for sel in selectors:
            s = item.css(sel)
            if s and s.first:
                raw = s.first.text or ""
                v = raw.strip() if isinstance(raw, str) else str(raw).strip()
                if v:
                    return v
        return ""

    @staticmethod
    def _detect_netdisk(item) -> NetDiskType:
        text = (item.text or "").lower() if isinstance(item.text, str) else ""
        classes = []
        for el in item.css("[class]"):
            cls = el.attrib.get("class", "") if hasattr(el, "attrib") else ""
            if cls:
                classes.append(cls)
        class_text = " ".join(classes).lower()

        for needle, chinese, mapped in _DISK_HINTS:
            if needle in class_text or chinese in text:
                return mapped
        return NetDiskType.OTHER

    @staticmethod
    def _parse_validity(item) -> bool:
        # success icon
        for sel in ("i.mdi-check", "i.mdi-check-circle", "i.mdi-check-circle-outline",
                    "span.success", ".valid", "[class*=valid]"):
            if list(item.css(sel)):
                return True
        # error icon
        for sel in ("i.mdi-close", "i.mdi-close-circle", "i.mdi-alert",
                    "span.error", ".invalid", "[class*=invalid]", "[class*=expired]"):
            if list(item.css(sel)):
                return False
        # text fallback
        text = item.text or ""
        if isinstance(text, str):
            if "有效" in text:
                return True
            if "失效" in text or "过期" in text or "无效" in text:
                return False
        return True  # default


__all__ = ["HaiSouSource"]
