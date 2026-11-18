@echo off
setlocal
cd /d "%~dp0"
if not exist ".venv\Scripts\python.exe" (
    echo Create the Python environment and install requirements.txt first.
    exit /b 1
)
set "DREAMSHIELD_HOST=127.0.0.1"
if not defined DREAMSHIELD_PORT set "DREAMSHIELD_PORT=8016"
if not defined DREAMSHIELD_MOBILE set "DREAMSHIELD_MOBILE=1"
if not defined DREAMSHIELD_MOBILE_PORT set "DREAMSHIELD_MOBILE_PORT=8746"
if not defined DREAMSHIELD_DATA_DIR set "DREAMSHIELD_DATA_DIR=%LOCALAPPDATA%\DreamShield016Core\data"
".venv\Scripts\python.exe" -B -X utf8 backend\app\server.py
exit /b %ERRORLEVEL%
