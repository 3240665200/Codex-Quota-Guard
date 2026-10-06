#requires -Version 5.1
[CmdletBinding()]
param(
    [switch]$Rebuild,
    [switch]$NoBrowser,
    [string]$BackendBindAddress = 'auto',
    [string]$LogDirectory = 'D:\BuildCache\pingche\logs'
)

$ErrorActionPreference = 'Stop'
$repositoryRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\..')).Path
$backendRoot = Join-Path $repositoryRoot 'apps\admin\backend'
$backendLauncher = Join-Path $backendRoot 'scripts\start-local.ps1'
$backendJar = Join-Path $backendRoot 'target\web-backend-0.1.0-SNAPSHOT.jar'
$adminUrl = 'http://localhost:8000/#/user/login'

if ($BackendBindAddress -eq 'auto') {
    $lan = Get-NetIPConfiguration | Where-Object {
        $_.NetAdapter.Status -eq 'Up' -and $_.IPv4DefaultGateway -and $_.IPv4Address
    } | Select-Object -First 1
    if ($null -eq $lan) { throw 'No active LAN IPv4 address with a default gateway was found.' }
    $BackendBindAddress = $lan.IPv4Address.IPAddress
}

function Get-Listener([int]$Port) {
    return @(Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue) |
        Select-Object -First 1
}

function Test-AdminFrontend {
    try {
        $response = Invoke-WebRequest -Uri 'http://127.0.0.1:8000' -UseBasicParsing -TimeoutSec 3
        return $response.StatusCode -eq 200 -and $response.Content -match 'Codex Quota Guard'
    } catch {
        return $false
    }
}

function Test-BackendNeedsBuild {
    if ($Rebuild -or -not (Test-Path -LiteralPath $backendJar -PathType Leaf)) {
        return $true
    }
    $jarTime = (Get-Item -LiteralPath $backendJar).LastWriteTimeUtc
    $inputs = @(Get-Item -LiteralPath (Join-Path $backendRoot 'pom.xml')) +
        @(Get-ChildItem -LiteralPath (Join-Path $backendRoot 'src') -Recurse -File)
    return $null -ne ($inputs | Where-Object LastWriteTimeUtc -GT $jarTime | Select-Object -First 1)
}

function Require-Command([string]$Name) {
    $command = Get-Command $Name -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($null -eq $command) {
        throw "Required command was not found: $Name"
    }
    return $command.Source
}

New-Item -ItemType Directory -Path $LogDirectory -Force | Out-Null

$backendListener = @(Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue) |
    Where-Object LocalAddress -eq $BackendBindAddress | Select-Object -First 1
if ($null -ne $backendListener) {
    $process = Get-CimInstance Win32_Process -Filter "ProcessId = $($backendListener.OwningProcess)"
    if ($null -eq $process -or $process.CommandLine -notmatch '(admin-backend-|web-backend-0\.1\.0-SNAPSHOT\.jar)') {
        throw "Port 8080 is occupied by another program (PID $($backendListener.OwningProcess))."
    }
    Write-Host "Java API is already running (PID $($backendListener.OwningProcess))."
} else {
    if (Get-Listener 8080) {
        throw "Port 8080 is already listening on another address. Expected: $BackendBindAddress."
    }
    $java = Require-Command 'java.exe'
    $maven = Require-Command 'mvn.cmd'
    if (Test-BackendNeedsBuild) {
        Write-Host 'Building, verifying, and starting the Java API...'
        & $backendLauncher -JavaCommand $java -MavenCommand $maven -BindAddress $BackendBindAddress | Out-Host
    } else {
        Write-Host 'Starting the verified Java API...'
        & $backendLauncher -JavaCommand $java -MavenCommand $maven -BindAddress $BackendBindAddress -SkipBuild | Out-Host
    }
}

$frontendListener = Get-Listener 8000
if ($null -ne $frontendListener) {
    if (-not (Test-AdminFrontend)) {
        throw "Port 8000 is occupied by another program (PID $($frontendListener.OwningProcess))."
    }
    Write-Host "Admin web UI is already running (PID $($frontendListener.OwningProcess))."
} else {
    $pnpm = Require-Command 'pnpm.cmd'
    $stamp = Get-Date -Format 'yyyyMMdd-HHmmss-fff'
    $stdout = Join-Path $LogDirectory "admin-frontend-$stamp.out.log"
    $stderr = Join-Path $LogDirectory "admin-frontend-$stamp.err.log"
    $previousApiTarget = [Environment]::GetEnvironmentVariable('ADMIN_API_TARGET', 'Process')
    try {
        $env:ADMIN_API_TARGET = "http://${BackendBindAddress}:8080"
        $frontendProcess = Start-Process -FilePath $pnpm -ArgumentList 'dev:admin' `
            -WorkingDirectory $repositoryRoot -WindowStyle Hidden `
            -RedirectStandardOutput $stdout -RedirectStandardError $stderr -PassThru
    } finally {
        [Environment]::SetEnvironmentVariable('ADMIN_API_TARGET', $previousApiTarget, 'Process')
    }

    $deadline = (Get-Date).AddSeconds(120)
    while ((Get-Date) -lt $deadline -and -not (Test-AdminFrontend)) {
        if ($frontendProcess.HasExited) {
            throw "Admin web UI exited during startup. See $stdout and $stderr"
        }
        Start-Sleep -Milliseconds 500
    }
    if (-not (Test-AdminFrontend)) {
        throw "Admin web UI startup timed out. See $stdout and $stderr"
    }
    Write-Host "Admin web UI started. Log: $stdout"
}

if (-not $NoBrowser) {
    Start-Process $adminUrl
}
Write-Host "Admin system is ready: $adminUrl"
