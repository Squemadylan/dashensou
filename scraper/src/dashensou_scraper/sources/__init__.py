"""Source registry — each new source just register() once."""

from __future__ import annotations

from typing import Type

from .base import DetailInfo, FetchPlan, SearchSource, SourceFailure_
from .pansou_cc import PansouCcSource
from .aiqu225 import AiQu225Source
from .wanzhan import WanzhanApiSource
from .haisou import HaiSouSource


_REGISTRY: dict[str, Type[SearchSource]] = {
    cls.id: cls  # type: ignore[attr-defined]
    for cls in (PansouCcSource, AiQu225Source, WanzhanApiSource, HaiSouSource)
}


def all_sources() -> list[SearchSource]:
    """Return a live instance per registered source."""
    return [
        WanzhanApiSource(),
        PansouCcSource(),
        AiQu225Source(),
        HaiSouSource(),
    ]


def get_source(source_id: str) -> SearchSource:
    if source_id not in _REGISTRY:
        raise KeyError(f"unknown source: {source_id}")
    inst: SearchSource = _REGISTRY[source_id]()  # type: ignore[call-arg]
    return inst


__all__ = [
    "all_sources",
    "get_source",
    "FetchPlan",
    "DetailInfo",
    "HaiSouSource",
    "PansouCcSource",
    "AiQu225Source",
    "WanzhanApiSource",
]