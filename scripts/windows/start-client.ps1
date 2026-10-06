#requires -Version 5.1
[CmdletBinding()]
param(
    [string]$LogDirectory = 'D:\BuildCache\pingche\logs',
    [string]$ControlPlaneUrl = 'http://127.0.0.1:8080',
    [ValidateRange(30, 600)][int]$StartupTimeoutSeconds = 240
)

$ErrorActionPreference = 'Stop'
$repositoryRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\..')).Path

$controlPlaneUri = $null
if (-not [Uri]::TryCreate($ControlPlaneUrl, [UriKind]::Absolute, [ref]$controlPlaneUri)) {
    throw 'ControlPlaneUrl must be an absolute URL.'
}
$isSecureOrigin = $controlPlaneUri.Scheme -eq 'https'
$isLocalDevelopmentOrigin = $controlPlaneUri.Scheme -eq 'http' -and $controlPlaneUri.IsLoopback
$hasOriginOnly = $controlPlaneUri.AbsolutePath -eq '/' -and
    [string]::IsNullOrEmpty($controlPlaneUri.Query) -and
    [string]::IsNullOrEmpty($controlPlaneUri.Fragment) -and
    [string]::IsNullOrEmpty($controlPlaneUri.UserInfo)
if ((-not $isSecureOrigin -and -not $isLocalDevelopmentOrigin) -or -not $hasOriginOnly) {
    throw 'ControlPlaneUrl must be an HTTPS origin, or an HTTP loopback origin for local development.'
}

function Require-Command([string]$Name) {
    $command = Get-Command $Name -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($null -eq $command) {
        throw "Required command was not found: $Name"
    }
    return $command.Source
}

$runningClient = Get-Process -Name 'codex-quota-guard' -ErrorAction SilentlyContinue |
    Where-Object MainWindowHandle -NE 0 | Sort-Object StartTime -Descending | Select-Object -First 1
if ($null -ne $runningClient) {
    try { (New-Object -ComObject WScript.Shell).AppActivate($runningClient.Id) | Out-Null } catch { }
    Write-Host "Desktop client is already running (PID $($runningClient.Id))."
    return
}

$rendererListener = Get-NetTCPConnection -LocalPort 3000 -State Listen -ErrorAction SilentlyContinue |
    Select-Object -First 1
if ($null -ne $rendererListener) {
    throw "Port 3000 is occupied while the desktop window is not running (PID $($rendererListener.OwningProcess)). Close the old client development process and retry."
}

$pnpm = Require-Command 'pnpm.cmd'
$java = Require-Command 'java.exe'
$javaHome = Split-Path -Parent (Split-Path -Parent $java)
$javaw = Join-Path $javaHome 'bin\javaw.exe'
if (-not (Test-Path -LiteralPath $javaw -PathType Leaf)) {
    $javaw = $java
}
New-Item -ItemType Directory -Path $LogDirectory -Force | Out-Null
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss-fff'
$stdout = Join-Path $LogDirectory "client-$stamp.out.log"
$stderr = Join-Path $LogDirectory "client-$stamp.err.log"
$startedAt = Get-Date

$environmentKeys = @('JAVA_HOME', 'CODEX_QUOTA_GUARD_JAVA', 'QUOTA_GUARD_CONTROL_PLANE_URL')
$previousEnvironment = @{}
try {
    foreach ($key in $environmentKeys) {
        $previousEnvironment[$key] = [Environment]::GetEnvironmentVariable($key, 'Process')
    }
    $env:JAVA_HOME = $javaHome
    $env:CODEX_QUOTA_GUARD_JAVA = $javaw
    $env:QUOTA_GUARD_CONTROL_PLANE_URL = $ControlPlaneUrl
    $launcher = Start-Process -FilePath $pnpm -ArgumentList 'dev:client' `
        -WorkingDirectory $repositoryRoot -WindowStyle Hidden `
        -RedirectStandardOutput $stdout -RedirectStandardError $stderr -PassThru
} finally {
    foreach ($key in $environmentKeys) {
        if ($null -eq $previousEnvironment[$key]) {
            Remove-Item -LiteralPath "Env:$key" -ErrorAction SilentlyContinue
        } else {
            [Environment]::SetEnvironmentVariable($key, $previousEnvironment[$key], 'Process')
        }
    }
}

$deadline = (Get-Date).AddSeconds($StartupTimeoutSeconds)
do {
    $client = Get-Process -Name 'codex-quota-guard' -ErrorAction SilentlyContinue |
        Where-Object { $_.StartTime -ge $startedAt -and $_.MainWindowHandle -ne 0 } |
        Sort-Object StartTime -Descending | Select-Object -First 1
    if ($null -ne $client -and $client.Responding) {
        Write-Host "Desktop client is ready (PID $($client.Id))."
        Write-Host "Startup log: $stdout"
        return
    }
    if ($launcher.HasExited -and $null -eq (Get-NetTCPConnection -LocalPort 3000 -State Listen -ErrorAction SilentlyContinue)) {
        throw "Desktop client exited during startup. See $stdout and $stderr"
    }
    Start-Sleep -Milliseconds 750
} while ((Get-Date) -lt $deadline)

throw "Desktop client startup timed out. See $stdout and $stderr"
