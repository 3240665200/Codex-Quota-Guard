#requires -Version 5.1
[CmdletBinding()]
param(
    [string]$LogDirectory = '',
    [string]$ControlPlaneUrl = '',
    [ValidateRange(30, 600)][int]$StartupTimeoutSeconds = 240
)

$ErrorActionPreference = 'Stop'

function Resolve-ClientServerOrigin([string]$Url) {
    $parsed = $null
    if (-not [Uri]::TryCreate($Url.Trim(), [UriKind]::Absolute, [ref]$parsed) -or
        $parsed.Scheme -notin @('http', 'https') -or -not $parsed.Host -or
        $parsed.AbsolutePath -ne '/' -or $parsed.UserInfo -or $parsed.Query -or $parsed.Fragment) {
        throw 'ControlPlaneUrl must be an HTTP or HTTPS origin.'
    }
    if ($parsed.Host -eq '0.0.0.0') {
        $builder = [UriBuilder]::new($parsed)
        $builder.Host = '127.0.0.1'
        $parsed = $builder.Uri
    }
    return $parsed.GetLeftPart([UriPartial]::Authority)
}

# Importing this file for tests never starts a process or changes a service.
if ($MyInvocation.InvocationName -eq '.') { return }

$repositoryRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\..')).Path
$appData = Join-Path $env:APPDATA 'com.codexquotaguard.desktop'
$serverConfigFile = Join-Path $appData 'server-config.json'
$connectionFile = Join-Path $appData 'meter-connection.json'
$installedOrigin = ''
if (Test-Path -LiteralPath $connectionFile -PathType Leaf) {
    if ((Get-Item -LiteralPath $connectionFile).Length -gt 4096) { throw 'Statistics service connection metadata is invalid.' }
    $installedOrigin = Resolve-ClientServerOrigin ([string](Get-Content -LiteralPath $connectionFile -Raw | ConvertFrom-Json).serverUrl)
}
if (-not $ControlPlaneUrl) {
    if (Test-Path -LiteralPath $serverConfigFile -PathType Leaf) {
        if ((Get-Item -LiteralPath $serverConfigFile).Length -gt 4096) { throw 'Desktop server settings are invalid.' }
        $ControlPlaneUrl = [string](Get-Content -LiteralPath $serverConfigFile -Raw | ConvertFrom-Json).url
    } elseif ($installedOrigin) { $ControlPlaneUrl = $installedOrigin }
    else { $ControlPlaneUrl = 'http://127.0.0.1:8080' }
}
$ControlPlaneUrl = Resolve-ClientServerOrigin $ControlPlaneUrl
if ($installedOrigin -and $installedOrigin -cne $ControlPlaneUrl) {
    throw 'Desktop and statistics service addresses differ. Change the server in desktop Settings to synchronize them, or reinstall with the selected address.'
}
$running = Get-Process -Name 'codex-quota-guard' -ErrorAction SilentlyContinue |
    Where-Object MainWindowHandle -NE 0 | Sort-Object StartTime -Descending | Select-Object -First 1
if ($running) {
    throw "A desktop client is already running (PID $($running.Id)). Quit it from the system tray, then run this script again to test the current source."
}
$listener = Get-NetTCPConnection -LocalPort 3000 -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
if ($listener) { throw "Development port 3000 is occupied (PID $($listener.OwningProcess))." }
$pnpm = (Get-Command 'pnpm.cmd' -ErrorAction Stop).Source
if (-not $LogDirectory) { $LogDirectory = Join-Path $repositoryRoot '.local\logs\client' }
New-Item -ItemType Directory -Path $LogDirectory -Force | Out-Null
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss-fff'
$stdout = Join-Path $LogDirectory "client-$stamp.out.log"
$stderr = Join-Path $LogDirectory "client-$stamp.err.log"
$startedAt = Get-Date
$previousOrigin = [Environment]::GetEnvironmentVariable('QUOTA_GUARD_CONTROL_PLANE_URL', 'Process')
try {
    $env:QUOTA_GUARD_CONTROL_PLANE_URL = $ControlPlaneUrl
    $launcher = Start-Process -FilePath $pnpm -ArgumentList 'dev:client' -WorkingDirectory $repositoryRoot `
        -WindowStyle Hidden -RedirectStandardOutput $stdout -RedirectStandardError $stderr -PassThru
} finally {
    [Environment]::SetEnvironmentVariable('QUOTA_GUARD_CONTROL_PLANE_URL', $previousOrigin, 'Process')
}
$deadline = (Get-Date).AddSeconds($StartupTimeoutSeconds)
do {
    $client = Get-Process -Name 'codex-quota-guard' -ErrorAction SilentlyContinue |
        Where-Object { $_.StartTime -ge $startedAt -and $_.MainWindowHandle -ne 0 } |
        Sort-Object StartTime -Descending | Select-Object -First 1
    if ($client -and $client.Responding) {
        Write-Host "Desktop client is ready (PID $($client.Id)). Startup log: $stdout"
        return
    }
    if ($launcher.HasExited -and -not (Get-NetTCPConnection -LocalPort 3000 -State Listen -ErrorAction SilentlyContinue)) {
        throw "Desktop client exited during startup. See $stdout and $stderr"
    }
    Start-Sleep -Milliseconds 750
} while ((Get-Date) -lt $deadline)
throw "Desktop client startup timed out. See $stdout and $stderr"
