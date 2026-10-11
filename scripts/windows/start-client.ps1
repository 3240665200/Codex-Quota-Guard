#requires -Version 5.1
[CmdletBinding()]
param(
    [string]$LogDirectory = 'D:\BuildCache\pingche\logs',
    [string]$ControlPlaneUrl = 'http://127.0.0.1:8080',
    [ValidateRange(30, 600)][int]$StartupTimeoutSeconds = 240,
    [string]$ExpectedUserSid = '',
    [bool]$MeterInstallDecisionMade = $false,
    [bool]$InstallMeter = $false
)

$ErrorActionPreference = 'Stop'

function Assert-ClientExpectedUser([string]$CurrentSid, [string]$ExpectedSid) {
    if ($ExpectedSid -and $CurrentSid -ne $ExpectedSid) {
        throw 'Administrator approval must use the same Windows user as the desktop client.'
    }
}

function New-ClientElevationCommand([string]$ScriptPath, [hashtable]$Parameters) {
    $payload = @{ scriptPath = $ScriptPath; parameters = $Parameters } | ConvertTo-Json -Compress
    $encodedPayload = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($payload))
    $command = @'
$ErrorActionPreference = 'Stop'
$payload = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('__PAYLOAD__')) | ConvertFrom-Json
$parameters = @{}
foreach ($property in $payload.parameters.PSObject.Properties) {
    $parameters[$property.Name] = $property.Value
}
& ([string]$payload.scriptPath) @parameters
'@.Replace('__PAYLOAD__', $encodedPayload)
    return [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($command))
}

function Resolve-ClientMeterInstallDecision([bool]$MeterInstalled, [string]$Answer) {
    if ($MeterInstalled) { return $false }
    if ($Answer -and $Answer -notmatch '^(y|yes)$') {
        throw 'Meter installation was cancelled; the desktop client was not started.'
    }
    return $true
}

function Get-ClientElevationFailureMessage([int]$ExitCode) {
    if ($ExitCode -eq 23) {
        return 'Administrator approval must use the same Windows user as the desktop client.'
    }
    return "Elevated desktop client startup failed (exit code $ExitCode)."
}

# Dot-sourcing exposes the elevation policy to tests without starting the client.
if ($MyInvocation.InvocationName -eq '.') { return }

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

$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
$currentSid = $identity.User.Value
$differentUserExitCode = 23
try {
    Assert-ClientExpectedUser $currentSid $ExpectedUserSid
} catch {
    if ($ExpectedUserSid) {
        [Console]::Error.WriteLine($_.Exception.Message)
        exit $differentUserExitCode
    }
    throw
}

$runningClient = Get-Process -Name 'codex-quota-guard' -ErrorAction SilentlyContinue |
    Where-Object MainWindowHandle -NE 0 | Sort-Object StartTime -Descending | Select-Object -First 1
if ($null -ne $runningClient) {
    try { (New-Object -ComObject WScript.Shell).AppActivate($runningClient.Id) | Out-Null } catch { }
    Write-Host "Desktop client is already running (PID $($runningClient.Id))."
    return
}

if (-not $MeterInstallDecisionMade) {
    $meter = Get-Service -Name 'CodexQuotaGuardMeter' -ErrorAction SilentlyContinue
    if ($null -eq $meter) {
        Write-Host 'The always-on Rust meter service is not installed.'
        $answer = Read-Host 'Install it now? Windows will request administrator approval. [Y/n]'
        $InstallMeter = Resolve-ClientMeterInstallDecision $false $answer
    }
    $MeterInstallDecisionMade = $true
}

$principal = [Security.Principal.WindowsPrincipal]::new($identity)
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    $parameters = @{
        ExpectedUserSid = $currentSid
        MeterInstallDecisionMade = $true
        InstallMeter = $InstallMeter
    }
    foreach ($name in @('LogDirectory', 'ControlPlaneUrl', 'StartupTimeoutSeconds')) {
        if ($PSBoundParameters.ContainsKey($name)) { $parameters[$name] = $PSBoundParameters[$name] }
    }
    $encodedCommand = New-ClientElevationCommand $PSCommandPath $parameters
    $powershell = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
    Write-Host 'Administrator approval is required to start the desktop client.'
    try {
        $elevated = Start-Process -FilePath $powershell `
            -ArgumentList @('-NoLogo', '-NoProfile', '-ExecutionPolicy', 'Bypass', '-EncodedCommand', $encodedCommand) `
            -WorkingDirectory $repositoryRoot -Verb RunAs -WindowStyle Hidden -Wait -PassThru
    } catch {
        throw 'Administrator approval was not completed; the desktop client was not started.'
    }
    if ($elevated.ExitCode -ne 0) {
        throw (Get-ClientElevationFailureMessage $elevated.ExitCode)
    }
    return
}

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
& (Join-Path $PSScriptRoot 'ensure-meter-service.ps1') -ServerUrl $ControlPlaneUrl -InstallIfMissing:$InstallMeter

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
