"""Allow `python -m dashensou_scraper ...` to invoke the CLI."""
from .run import main

raise SystemExit(main())
