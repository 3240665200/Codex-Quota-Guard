#requires -Version 5.1
[CmdletBinding()]
param(
    [ValidateSet('Install', 'Uninstall')][string]$Operation = 'Install',
    [string]$ExpectedUserSid = '',
    [switch]$Elevated
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'install-meter-service.ps1') -ExpectedUserSid $ExpectedUserSid

function Get-InstallerMeterServer([string]$Directory) {
    foreach ($entry in @(@{name='server-config.json';field='url'}, @{name='meter-connection.json';field='serverUrl'})) {
        $file = Join-Path $Directory $entry.name
        if (Test-Path -LiteralPath $file -PathType Leaf) {
            if ((Get-Item -LiteralPath $file).Length -gt 4096) { throw 'Saved server settings are invalid.' }
            $saved = Get-Content -LiteralPath $file -Raw | ConvertFrom-Json
            return (Resolve-MeterServerUri ([string]$saved.($entry.field))).GetLeftPart([UriPartial]::Authority)
        }
    }
    return 'http://127.0.0.1:8080'
}

function New-InstallerMeterElevation([string]$Script, [string]$Action, [string]$Sid) {
    $json = @{script=$Script;action=$Action;sid=$Sid} | ConvertTo-Json -Compress
    $payload = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($json))
    $command = @'
$ErrorActionPreference = 'Stop'
$request = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('__PAYLOAD__')) | ConvertFrom-Json
& ([string]$request.script) -Operation ([string]$request.action) -ExpectedUserSid ([string]$request.sid) -Elevated
exit $LASTEXITCODE
'@.Replace('__PAYLOAD__', $payload)
    return [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($command))
}

if ($MyInvocation.InvocationName -eq '.') { return }

try {
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    $sid = $identity.User.Value
    Assert-MeterExpectedUser $sid $ExpectedUserSid
    $principal = [Security.Principal.WindowsPrincipal]::new($identity)
    if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
        if ($Elevated) { throw 'Windows administrator approval was not granted.' }
        $powershell = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
        $encoded = New-InstallerMeterElevation $PSCommandPath $Operation $sid
        $process = Start-Process -FilePath $powershell -ArgumentList @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-EncodedCommand', $encoded) `
            -Verb RunAs -WindowStyle Hidden -Wait -PassThru
        exit $process.ExitCode
    }
    if ($Operation -eq 'Install') {
        $global:LASTEXITCODE = 0
        $server = Get-InstallerMeterServer (Join-Path $env:APPDATA 'com.codexquotaguard.desktop')
        & (Join-Path $PSScriptRoot 'install-meter-service.ps1') -ServerUrl $server `
            -BinaryPath (Join-Path $PSScriptRoot 'quota-guard-meter.exe') -ExpectedUserSid $sid
    } else {
        $global:LASTEXITCODE = 0
        & (Join-Path $PSScriptRoot 'uninstall-meter-service.ps1') -ExpectedUserSid $sid
    }
    if ($LASTEXITCODE -ne 0) { throw 'The required statistics service operation failed.' }
    exit 0
} catch {
    [Console]::Error.WriteLine($_.Exception.Message)
    exit 1
}
