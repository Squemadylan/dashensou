"""SearchSource base class (Python mirror of app/.../SearchSource.kt)."""

from __future__ import annotations

import abc
from dataclasses import dataclass
from typing import Any, Optional

from ..http import FetcherKind
from ..models import NetDiskType, ResourceCategory, SearchResult


@dataclass(slots=True, frozen=True)
class FetchPlan:
    """How to fetch this source: which fetcher, what to ask Scrapling for."""

    fetcher: FetcherKind = "fetch"
    impersonate: str = "chrome"
    solve_cloudflare: bool = False
    charset: str = "utf-8"
    timeout_s: Optional[int] = None

    def as_kwargs(self) -> dict[str, Any]:
        kw: dict[str, Any] = {"fetcher": self.fetcher}
        if self.fetcher == "fetch":
            kw["impersonate"] = self.impersonate
        if self.fetcher == "stealthy":
            kw["solve_cloudflare"] = self.solve_cloudflare
        if self.fetcher == "dynamic":
            # DynamicFetcher doesn't impersonate; network_idle wait is opt-in.
            kw.setdefault("network_idle", True)
        if self.timeout_s is not None:
            kw["timeout"] = self.timeout_s
        return kw


@dataclass(slots=True)
class SourceOutcome:
    results: list[SearchResult]


@dataclass(slots=True)
class SourceFailure_:
    message: str
    kind: str = "unknown"  # one of models.FailureKind values


@dataclass(slots=True, frozen=True)
class DetailInfo:
    """Mirror of Kotlin's `PansouCcSource.DetailInfo` / `AiQuSource.DetailInfo`.

    `gotoUrl` is either:
      - a real share URL the user can open in the browser
      - a direct .txt file URL (aiqu225 mirrors)
    """

    netDiskType: NetDiskType
    password: Optional[str]
    gotoUrl: str


class SearchSource(abc.ABC):
    """Mirror of the Kotlin ``SearchSource`` interface."""

    id: str
    display_name: str
    enabled: bool = True
    per_source_timeout_ms: int = 0

    # --- knobs per source -------------------------------------------------
    @property
    @abc.abstractmethod
    def fetch_plan(self) -> FetchPlan:
        """默认抓取策略，子类可按站点特性覆盖。"""

    @property
    def base_url(self) -> str:
        return ""

    @abc.abstractmethod
    def build_url(self, keyword: str, page: int) -> str:
        """Compose the request URL."""

    @abc.abstractmethod
    def parse(
        self,
        body: str,
        category: ResourceCategory,
    ) -> list[SearchResult]:
        """Parse fetched HTML/JSON into SearchResult items."""

    # --- detail / two-step fetches ----------------------------------------
    def supports_detail(self) -> bool:
        """Override to True if this source has a fetchDetail() follow-up."""
        return False

    async def fetch_detail(self, detail_url: str) -> Optional[DetailInfo]:
        """Resolve a result's detail page to (netDiskType, password, gotoUrl).

        Default is a no-op: most JSON sources don't need it because the
        share URL is already in the search result. HTML aggregators like
        pansou_cc / aiqu225 override this.

        Subclasses may issue 0, 1, or 2 follow-up HTTP calls.
        """
        return None


__all__ = [
    "SearchSource",
    "SourceOutcome",
    "SourceFailure_",
    "FetchPlan",
    "DetailInfo",
]
