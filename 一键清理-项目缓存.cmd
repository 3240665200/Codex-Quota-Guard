@echo off
chcp 65001 >nul
cd /d "%~dp0"
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\windows\clean-project-cache.ps1" -IncludeLegacyCaches -OrganizeDist
if errorlevel 1 (
  echo Some files could not be cleaned. See .local\reports\cache-cleanup-result.json
) else (
  echo Cleanup finished. See .local\reports\cache-cleanup-result.json
)
pause
