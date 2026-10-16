@echo off
cd /d "%~dp0"
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\windows\start-admin-local-test.ps1"
set "START_RESULT=%ERRORLEVEL%"
if not "%START_RESULT%"=="0" (
  echo.
  echo Admin startup failed. See the error above.
  pause
)
exit /b %START_RESULT%
