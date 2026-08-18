"""CLI entry.

用法：
    python -m dashensou_scraper "三国演义"                          # 全源
    python -m dashensou_scraper "三国演义" --only pansou_cc,aiqu225
    python -m dashensou_scraper "三国演义" --detail --max-detail 5  # 列表后再追详情
    python -m dashensou_scraper "三国演义" --runs-dir ./runs

每次调用都会在 runs/ 下创建一个新目录：

    runs/2026-07-09T09-20-30_三国演义/
        manifest.json
        <source_id>/
            request.json
            raw.html          (或 raw.bin)
            raw.meta.json
            results.json      (解析成功)
            details.json      (--detail 时存在)
            detail_<idx>/     (--detail 时存在，每条一个)
                request.json
                raw.html
                raw.meta.json
                detail.json
            error.json        (失败时存在)
"""

from __future__ import annotations

import argparse
import asyncio
import functools
import json as _json
import sys
from pathlib import Path
from typing import Any

from .http import FetchError, get as http_get
from .models import FailureKind, ResourceCategory, SearchResult, SourceFailure
from .sources import all_sources


def _filter_sources(only: str | None) -> list:
    sources = all_sources()
    if not only:
        return [s for s in sources if s.enabled]
    wanted = {x.strip() for x in only.split(",") if x.strip()}
    return [s for s in sources if s.id in wanted]


def _decode(body: bytes, charset: str) -> str:
    try:
        return body.decode(charset, errors="replace")
    except LookupError:
        return body.decode("utf-8", errors="replace")


def _do_http(url: str, **kwargs) -> Any:
    """Run a blocking http.get() call.

    Wrapped so we can call it via asyncio.to_thread() — Scrapling's
    StealthyFetcher / DynamicFetcher use Playwright Sync API, which
    crashes if invoked from inside an asyncio event loop.
    """
    return http_get(url, **kwargs)


async def _run_one(run, source, keyword: str, page: int, category: ResourceCategory,
                   *, do_detail: bool = False, max_detail: int = 0) -> dict:
    """Async: fetch + parse + dump. Returns a status dict."""
    dump = run.source(source.id)
    plan = source.fetch_plan()
    url = source.build_url(keyword, page)
    dump.write_request(
        url=url,
        headers={"User-Agent": "DaShenSou/1.0 (+scrapling)"},
        charset=plan.charset,
    )

    loop = asyncio.get_event_loop()

    try:
        # functools.partial because loop.run_in_executor doesn't accept kwargs.
        http_call = functools.partial(_do_http, url, **plan.as_kwargs())
        result = await loop.run_in_executor(None, http_call)
    except FetchError as exc:
        dump.write_error(
            SourceFailure(message=str(exc), kind=FailureKind.NETWORK.value)
        )
        return {"status": "network_error", "result_count": 0, "error": str(exc)}

    if result.status_code >= 400 or not result.body:
        dump.write_raw(
            body=result.body,
            status_code=result.status_code,
            content_type=result.content_type,
            fetcher=result.fetcher,
        )
        dump.write_error(
            SourceFailure(
                message=f"HTTP {result.status_code} or empty body",
                kind=FailureKind.SOURCE_DOWN.value,
            )
        )
        return {
            "status": "source_down",
            "result_count": 0,
            "http_status": result.status_code,
        }

    text = _decode(result.body, plan.charset)
    dump.write_raw(
        body=result.body,
        status_code=result.status_code,
        content_type=result.content_type,
        fetcher=result.fetcher,
    )

    try:
        items = await loop.run_in_executor(None, source.parse, text, category)
    except Exception as exc:  # noqa: BLE001
        dump.write_error(
            SourceFailure(message=f"parse: {exc}", kind=FailureKind.PARSE.value)
        )
        return {"status": "parse_error", "result_count": 0, "error": str(exc)}

    status: dict = {
        "status": "ok" if items else "empty",
        "result_count": len(items),
        "fetcher": result.fetcher,
        "http_status": result.status_code,
    }

    # Optional detail follow-up
    if do_detail and max_detail > 0 and source.supports_detail() and items:
        details: list[dict] = []
        enriched: list[SearchResult] = []
        for i, item in enumerate(items[:max_detail]):
            try:
                detail = await source.fetch_detail(item.url)
            except Exception as exc:
                details.append({"sourceUrl": item.url, "error": str(exc)})
                enriched.append(item)
                continue
            if detail is None:
                details.append({"sourceUrl": item.url, "error": "fetch_detail returned None"})
                enriched.append(item)
                continue
            new_item = item.model_copy(update={
                "netDiskType": detail.netDiskType,
                "extractionCode": detail.password or item.extractionCode,
            })
            enriched.append(new_item)
            details.append({
                "sourceUrl": item.url,
                "netDiskType": detail.netDiskType.value,
                "password": detail.password,
                "gotoUrl": detail.gotoUrl,
            })
        dump.write_results(enriched)
        (dump._dir / "details.json").write_text(
            _json.dumps(details, ensure_ascii=False, indent=2),
            encoding="utf-8",
        )
        status["detail_attempted"] = min(len(items), max_detail)
        status["detail_ok"] = sum(1 for d in details if "gotoUrl" in d)
    else:
        dump.write_results(items)

    return status


async def run_async(
    keyword: str,
    *,
    page: int = 1,
    category: ResourceCategory = ResourceCategory.ALL,
    only: str | None = None,
    runs_dir: Path = Path("runs"),
    do_detail: bool = False,
    max_detail: int = 5,
) -> Path:
    """Run all (filtered) sources once. Sequential is fine for 4-11 sources."""
    from .storage import RunWriter

    sources = _filter_sources(only)
    if not sources:
        print("no enabled sources match", file=sys.stderr)
        sys.exit(2)

    run = RunWriter(
        keyword=keyword,
        page=page,
        base_dir=runs_dir,
        enabled=[s.id for s in sources],
    )
    print(f"[run] {run.root}")

    status: dict[str, dict] = {}
    for src in sources:
        print(f"  -> {src.id} ...", end=" ", flush=True)
        status[src.id] = await _run_one(
            run, src, keyword, page, category,
            do_detail=do_detail, max_detail=max_detail,
        )
        last = status[src.id]
        print(
            f"{last['status']:<12} "
            f"count={last.get('result_count', 0)} "
            f"fetcher={last.get('fetcher', '-')}"
            + (f" detail={last.get('detail_ok', 0)}/{last.get('detail_attempted', 0)}"
               if do_detail else "")
        )

    run.finalize(status)
    print(f"[done] {run.root / 'manifest.json'}")
    return run.root


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(description="dashensou multi-source scraper")
    p.add_argument("keyword", help="搜索关键词")
    p.add_argument("--page", type=int, default=1)
    p.add_argument(
        "--category",
        choices=[c.value for c in ResourceCategory],
        default=ResourceCategory.ALL.value,
    )
    p.add_argument("--only", help="逗号分隔源 ID，例 pansou_cc,aiqu225")
    p.add_argument("--runs-dir", type=Path, default=Path("runs"))
    p.add_argument("--detail", action="store_true",
                   help="对支持 detail 的源 (pansou_cc / aiqu225) 追跑详情解析")
    p.add_argument("--max-detail", type=int, default=3,
                   help="每个源最多追多少条 detail（默认 3）")
    args = p.parse_args(argv)

    asyncio.run(
        run_async(
            keyword=args.keyword,
            page=args.page,
            category=ResourceCategory(args.category),
            only=args.only,
            runs_dir=args.runs_dir,
            do_detail=args.detail,
            max_detail=args.max_detail,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
