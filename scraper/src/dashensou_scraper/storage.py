"""Per-run isolated storage.

设计要点：
    - 每次 run 一个独立目录，永远不覆盖、不合并。
    - 落盘双写：原始 HTTP 响应 (raw.*) + 解析后的 results.json。
    - manifest.json 记录这次 run 的输入、源启用情况、wall-clock 与失败汇总。
    - 全部 UTF-8，写盘前验证 JSON 有效性（解析时崩了不会被静默吞掉）。

目录形态（相对于 runs/）::

    runs/
      2026-07-09T09-20-30_三国演义/         ← 一次运行一个目录
        manifest.json
        pansou_cc/
          request.json                      ← URL / headers / charset
          raw.html                          ← 原始响应
          raw.meta.json                     ← status_code / size / ts
          results.json                      ← 解析结果（可空）
          error.json                        ← 失败时存在
        aiqu225/
          ...
"""

from __future__ import annotations

import datetime as _dt
import json
import re
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Iterable

from .models import SearchResult, SourceFailure


# Windows / *nix 都不允许的字符：\ / : * ? " < > | + 控制字符
# 允许中文、空格、点（句号）、短横。
_UNSAFE_RE = re.compile(r'[\\/:\*\?"<>\|\x00-\x1f]+')
_WHITESPACE_RUN_RE = re.compile(r"\s+")


def _slugify(text: str, *, max_len: int = 48) -> str:
    """Sanitise a keyword into a directory-name fragment.

    - Keeps CJK characters and most punctuation (dot, dash, underscore).
    - Strips path-unsafe chars (\\\\ / : * ? " < > | and control chars).
    - Collapses whitespace runs to single underscore.
    - Trims to ``max_len``; falls back to ``run`` if all that remains
      is whitespace or unsafe characters.
    """
    cleaned = _UNSAFE_RE.sub("", text.strip())
    cleaned = _WHITESPACE_RUN_RE.sub("_", cleaned)
    cleaned = cleaned[:max_len].strip("_- ")
    return cleaned or "run"


def _now_iso() -> str:
    return _dt.datetime.now().isoformat(timespec="seconds")


@dataclass
class SourceDump:
    """Single (run × source) writer. Files land under <run_dir>/<source_id>/."""

    root: Path
    source_id: str
    _dir: Path = field(init=False)

    def __post_init__(self) -> None:
        self._dir = self.root / self.source_id
        self._dir.mkdir(parents=True, exist_ok=True)

    def sub(self, name: str) -> Path:
        """Return a subdirectory under this source dir, creating it on demand.

        Used by the detail post-processing pass to write one sub-directory
        per result (detail_00/, detail_01/, ...).
        """
        p = self._dir / name
        p.mkdir(parents=True, exist_ok=True)
        return p

    # --- request context -------------------------------------------------
    def write_request(self, *, url: str, headers: dict[str, str], charset: str) -> None:
        (self._dir / "request.json").write_text(
            json.dumps(
                {"url": url, "headers": headers, "charset": charset},
                ensure_ascii=False,
                indent=2,
            ),
            encoding="utf-8",
        )

    # --- raw artifact ----------------------------------------------------
    def write_raw(
        self,
        *,
        body: bytes | str,
        status_code: int,
        content_type: str,
        fetcher: str,
    ) -> None:
        if isinstance(body, str):
            body_bytes = body.encode("utf-8")
            (self._dir / "raw.html").write_text(body, encoding="utf-8")
        else:
            body_bytes = body
            (self._dir / "raw.bin").write_bytes(body)
        (self._dir / "raw.meta.json").write_text(
            json.dumps(
                {
                    "status_code": status_code,
                    "content_type": content_type,
                    "size_bytes": len(body_bytes),
                    "fetcher": fetcher,
                    "fetched_at": _now_iso(),
                },
                ensure_ascii=False,
                indent=2,
            ),
            encoding="utf-8",
        )

    # --- parsed results --------------------------------------------------
    def write_results(self, results: list[SearchResult]) -> None:
        payload = [r.model_dump(mode="json") for r in results]
        (self._dir / "results.json").write_text(
            json.dumps(payload, ensure_ascii=False, indent=2),
            encoding="utf-8",
        )

    def write_error(self, failure: SourceFailure) -> None:
        (self._dir / "error.json").write_text(
            json.dumps(failure.model_dump(), ensure_ascii=False, indent=2),
            encoding="utf-8",
        )


@dataclass
class RunWriter:
    """A single run. Owns the run directory + manifest."""

    keyword: str
    page: int
    base_dir: Path
    enabled: list[str]
    started_at: str = field(default_factory=_now_iso)
    _root: Path = field(init=False)

    def __post_init__(self) -> None:
        ts = _now_iso().replace(":", "-").replace("+08-00", "+0800")
        folder = f"{ts}_{_slugify(self.keyword)}"
        self._root = self.base_dir / folder
        self._root.mkdir(parents=True, exist_ok=True)
        self._write_initial_manifest()

    @property
    def root(self) -> Path:
        return self._root

    def source(self, source_id: str) -> SourceDump:
        return SourceDump(root=self._root, source_id=source_id)

    def subdir(self, *parts: str) -> Path:
        """Return a subdirectory under the run root, creating it on demand."""
        p = self._root.joinpath(*parts)
        p.mkdir(parents=True, exist_ok=True)
        return p

    def _manifest_path(self) -> Path:
        return self._root / "manifest.json"

    def _write_initial_manifest(self) -> None:
        self._manifest_path().write_text(
            json.dumps(
                {
                    "keyword": self.keyword,
                    "page": self.page,
                    "enabled_sources": self.enabled,
                    "started_at": self.started_at,
                    "status": "running",
                    "sources": {},
                },
                ensure_ascii=False,
                indent=2,
            ),
            encoding="utf-8",
        )

    def finalize(self, source_status: dict[str, dict[str, Any]]) -> None:
        """Patch the manifest with per-source statuses and total counts.

        Idempotent: rewrites manifest.json in place.
        """
        manifest = json.loads(self._manifest_path().read_text(encoding="utf-8"))
        manifest["finished_at"] = _now_iso()
        manifest["status"] = "done"
        manifest["sources"] = source_status
        total = sum(s.get("result_count", 0) for s in source_status.values())
        manifest["total_results"] = total
        self._manifest_path().write_text(
            json.dumps(manifest, ensure_ascii=False, indent=2),
            encoding="utf-8",
        )


__all__ = ["RunWriter", "SourceDump"]
