#requires -Version 5.1
[CmdletBinding()]
param(
    [string]$LogDirectory = 'D:\BuildCache\pingche\logs',
    [string]$ControlPlaneUrl = 'http://127.0.0.1:8080',
    [ValidateRange(30, 600)][int]$StartupTimeoutSeconds = 240
)

$ErrorActionPreference = 'Stop'
$repositoryRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\..')).Path
$appData = Join-Path $env:APPDATA 'com.codexquotaguard.desktop'
$serverConfigFile = Join-Path $appData 'server-config.json'
if (-not $PSBoundParameters.ContainsKey('ControlPlaneUrl') -and
        (Test-Path -LiteralPath $serverConfigFile -PathType Leaf)) {
    if ((Get-Item -LiteralPath $serverConfigFile).Length -gt 4096) {
        throw 'Desktop server settings are invalid.'
    }
    $savedConfig = Get-Content -LiteralPath $serverConfigFile -Raw | ConvertFrom-Json
    $ControlPlaneUrl = [string]$savedConfig.url
}

$controlPlaneUri = $null
if (-not [Uri]::TryCreate($ControlPlaneUrl, [UriKind]::Absolute, [ref]$controlPlaneUri)) {
    throw 'ControlPlaneUrl must be an absolute URL.'
}
$hasOriginOnly = $controlPlaneUri.AbsolutePath -eq '/' -and
    [string]::IsNullOrEmpty($controlPlaneUri.Query) -and
    [string]::IsNullOrEmpty($controlPlaneUri.Fragment) -and
    [string]::IsNullOrEmpty($controlPlaneUri.UserInfo)
if ($controlPlaneUri.Scheme -notin @('http', 'https') -or -not $controlPlaneUri.Host -or -not $hasOriginOnly) {
    throw 'ControlPlaneUrl must be an HTTP or HTTPS origin.'
}
if ($controlPlaneUri.Host -eq '0.0.0.0') {
    $builder = [UriBuilder]::new($controlPlaneUri)
    $builder.Host = '127.0.0.1'
    $ControlPlaneUrl = $builder.Uri.GetLeftPart([UriPartial]::Authority)
}
$ControlPlaneUrl = ([Uri]$ControlPlaneUrl).GetLeftPart([UriPartial]::Authority)
if ($PSBoundParameters.ContainsKey('ControlPlaneUrl')) {
    New-Item -ItemType Directory -Path $appData -Force | Out-Null
    [IO.File]::WriteAllText($serverConfigFile,
        (@{url = $ControlPlaneUrl} | ConvertTo-Json -Compress), [Text.UTF8Encoding]::new($false))
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
$frontendDirectory = Join-Path $repositoryRoot 'apps\client\frontend'
Push-Location -LiteralPath $frontendDirectory
try {
    & $pnpm build:meter
    if ($LASTEXITCODE -ne 0) { throw 'Rust meter build failed; the desktop client was not started.' }
} finally {
    Pop-Location
}

# Prepare the independent meter before compiling or opening the desktop UI.
$meter = Get-Service -Name 'CodexQuotaGuardMeter' -ErrorAction SilentlyContinue
$installMeter = $false
if ($null -eq $meter) {
    Write-Host 'The always-on Rust meter service is not installed.'
    $answer = Read-Host 'Install it now? Windows will request administrator approval. [Y/n]'
    if ($answer -and $answer -notmatch '^(y|yes)$') {
        throw 'Meter installation was cancelled; the desktop client was not started.'
    }
    $installMeter = $true
}
& (Join-Path $PSScriptRoot 'ensure-meter-service.ps1') -ServerUrl $ControlPlaneUrl -InstallIfMissing:$installMeter

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
