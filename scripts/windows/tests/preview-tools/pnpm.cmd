@echo off
set PREVIEW_SYNTHETIC_UI=true
"%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe" -NoLogo -NoProfile -NonInteractive -File "%~dp0..\preview-owned-fixture.ps1" -Mode Server -Port "%PORT%"
