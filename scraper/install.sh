#!/usr/bin/env bash
# Install dashensou_scraper in an isolated venv.
# Usage: ./install.sh

set -euo pipefail
cd "$(dirname "$0")"

PY=${PY:-python3.10}

if [ ! -d .venv ]; then
    echo "[1/3] creating venv"
    "$PY" -m venv .venv
fi

echo "[2/3] installing deps"
.venv/bin/python -m pip install --upgrade pip
.venv/bin/python -m pip install -r requirements.txt
.venv/bin/python -m pip install -e .

echo "[3/3] installing browser (Playwright Chromium)"
.venv/bin/scrapling install

echo ""
echo "OK. Try:"
echo "  .venv/bin/python -m dashensou_scraper '三国演义' --only aiqu225 --detail"
