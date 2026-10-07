#requires -Version 5.1
[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$name = 'CodexQuotaGuardMeter'
$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
$principal = [Security.Principal.WindowsPrincipal]::new($identity)
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw 'Run this script from an elevated Administrator PowerShell.'
}
$service = Get-Service -Name $name -ErrorAction SilentlyContinue
if ($service) {
    if ($service.Status -ne 'Stopped') {
        Stop-Service -Name $name -ErrorAction Stop
        $service.WaitForStatus('Stopped', [TimeSpan]::FromSeconds(20))
    }
    & sc.exe delete $name | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Could not remove the meter service registration.' }
}

$appData = [IO.Path]::GetFullPath((Join-Path $env:APPDATA 'com.codexquotaguard.desktop'))
$stateDir = [IO.Path]::GetFullPath((Join-Path $env:ProgramData 'CodexQuotaGuard\Meter'))
foreach ($file in @(
    (Join-Path $appData 'meter-pairing.json'),
    (Join-Path $appData 'meter-status.json'),
    (Join-Path $stateDir 'meter-credential.json')
)) {
    $resolved = [IO.Path]::GetFullPath($file)
    if ($resolved.StartsWith($appData + [IO.Path]::DirectorySeparatorChar,
            [StringComparison]::OrdinalIgnoreCase) -or
        $resolved.StartsWith($stateDir + [IO.Path]::DirectorySeparatorChar,
            [StringComparison]::OrdinalIgnoreCase)) {
        Remove-Item -LiteralPath $resolved -ErrorAction SilentlyContinue
    } else {
        throw 'Unexpected meter file path; no files were removed.'
    }
}
Write-Host 'Rust meter service removed. Revoke its device token in the admin console as well.'
