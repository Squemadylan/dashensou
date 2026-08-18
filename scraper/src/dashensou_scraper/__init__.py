"""dashensou multi-source scraper.

Layered so it stays inside the Scrapling "process only" design:

  storage      ← per-run isolated dumps (runs/<ts>/<kw>/<source>/...)
  http         ← thin wrapper over Fetcher / StealthyFetcher / DynamicFetcher
  sources/*    ← one file per aggregator, mirror of app/.../source/*.kt
  run.py       ← CLI entry
"""

__version__ = "0.1.0"
