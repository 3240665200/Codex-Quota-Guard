#requires -Version 5.1
[CmdletBinding()]
param([switch]$NoBrowser,[switch]$NoPause)

$ErrorActionPreference = 'Stop'
# Use the existing owned-process supervisor and its persistent protected keys.
try {
    $preview = & (Join-Path $PSScriptRoot 'start-admin-preview.ps1')
} catch {
    if ($_.Exception.Message -eq 'PREVIEW_CONFIG_MISSING') {
        Write-Warning 'Protected backend config is missing. Restore the existing admin-backend.clixml for this Windows user; do not replace database credentials or encryption keys.'
    } elseif ($_.Exception.Message -eq 'PREVIEW_PROXY_KERNEL_MISSING') {
        Write-Warning 'sing-box is missing. Check the configured executable path; see docs/local-admin-test.md for prerequisites.'
    } elseif ($_.Exception.Message -eq 'PREVIEW_PORT_UNAVAILABLE') {
        Write-Warning 'Port 8000 or 18080 is unavailable. Check the existing run and stop it with its recorded stop command before retrying.'
    }
    throw
}
$preview | Format-List | Out-Host
$stopScript = Join-Path $PSScriptRoot 'stop-admin-preview.ps1'
$quotedStopScript = $stopScript.Replace("'", "''")
$quotedRunDirectory = $preview.RunDirectory.Replace("'", "''")
Write-Host "Stop this run: & '$quotedStopScript' -RunDirectory '$quotedRunDirectory'"
if (-not $NoBrowser) {
    try { Start-Process 'http://localhost:8000/#/user/login' }
    catch { Write-Warning 'Browser could not open. Visit http://localhost:8000/#/user/login manually.' }
}
# Keep the double-click window available so the precise stop command is retained.
if (-not $NoPause) {
    Read-Host 'Press Enter to close this window; services remain running' | Out-Null
}
