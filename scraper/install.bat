@echo off
REM Install dashensou_scraper in an isolated venv.
REM Usage: install.bat

setlocal

set "PY=C:\Users\Squema-Mini\.workbuddy\binaries\python\versions\3.13.12\python.exe"
if not exist "%PY%" (
    set "PY=python"
)

if not exist .venv (
    echo [1/3] creating venv
    "%PY%" -m venv .venv
)

echo [2/3] installing deps
.venv\Scripts\python.exe -m pip install --upgrade pip
.venv\Scripts\python.exe -m pip install -r requirements.txt
.venv\Scripts\python.exe -m pip install -e .

echo [3/3] installing browser (Playwright Chromium) for DynamicFetcher + StealthyFetcher
.venv\Scripts\scrapling install

echo.
echo OK. Try:
echo   .venv\Scripts\python.exe -m dashensou_scraper "三国演义" --only aiqu225 --detail
echo.
endlocal
