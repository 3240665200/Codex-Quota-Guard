#requires -Version 5.1
[CmdletBinding()]
param([string]$ExpectedUserSid = '')

$ErrorActionPreference = 'Stop'
$name = 'CodexQuotaGuardMeter'
$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
. (Join-Path $PSScriptRoot 'install-meter-service.ps1') -ExpectedUserSid $ExpectedUserSid
Assert-MeterExpectedUser $identity.User.Value $ExpectedUserSid
$principal = [Security.Principal.WindowsPrincipal]::new($identity)
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw 'Run this script from an elevated Administrator PowerShell.'
}
$service = Get-Service -Name $name -ErrorAction SilentlyContinue
if ($service) {
    $registered = Get-CimInstance Win32_Service -Filter "Name='$name'"
    $existing = Get-MeterExistingConfiguration $registered.PathName (Join-Path $env:APPDATA 'com.codexquotaguard.desktop')
    Resolve-MeterOwnerSid $identity.User.Value ([string]$existing.config.ownerUserSid) | Out-Null
    if ($service.Status -ne 'Stopped') {
        Stop-Service -Name $name -ErrorAction Stop
        $service.WaitForStatus('Stopped', [TimeSpan]::FromSeconds(20))
    }
    & sc.exe delete $name | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Could not remove the meter service registration.' }
}
Write-Host 'Statistics service removed. Accounting journals, device identity and credentials were retained for reinstallation.'
