#requires -Version 5.1
[CmdletBinding()]
param(
    [string]$ServerUrl = 'http://127.0.0.1:8080',
    [string]$InstallDirectory = '',
    [string]$BinaryPath = ''
)

$ErrorActionPreference = 'Stop'

function Test-MeterBinary([string]$Path) {
    $probe = Start-Process -FilePath $Path -ArgumentList '--version' -WindowStyle Hidden -PassThru
    try {
        if (-not $probe.WaitForExit(10000)) {
            $probe.Kill()
            $probe.WaitForExit()
            throw 'The bundled meter executable did not finish its startup check.'
        }
        if ($probe.ExitCode -ne 0) {
            throw 'The bundled meter executable cannot run on this computer.'
        }
    } finally {
        $probe.Dispose()
    }
}

function Write-MeterSetupStatus([string]$State, [string]$Stage, [string]$Reason = '') {
    $directory = Join-Path $env:APPDATA 'com.codexquotaguard.desktop'
    New-Item -ItemType Directory -Path $directory -Force | Out-Null
    $summary = @{state = $State; stage = $Stage; reason = $Reason; at = [DateTimeOffset]::UtcNow.ToString('o')}
    [IO.File]::WriteAllText((Join-Path $directory 'meter-setup-status.json'),
        ($summary | ConvertTo-Json -Compress), [Text.UTF8Encoding]::new($false))
}

function Resolve-MeterServerUri([string]$Url) {
    $parsed = $null
    if (-not [Uri]::TryCreate($Url.Trim(), [UriKind]::Absolute, [ref]$parsed) -or
        $parsed.Scheme -notin @('http', 'https') -or -not $parsed.Host -or
        $parsed.AbsolutePath -ne '/' -or $parsed.UserInfo -or $parsed.Query -or $parsed.Fragment) {
        throw 'ServerUrl must be an HTTP or HTTPS origin.'
    }
    if ($parsed.Host -eq '0.0.0.0') {
        $builder = [UriBuilder]::new($parsed)
        $builder.Host = '127.0.0.1'
        $parsed = $builder.Uri
    }
    return $parsed
}

function Resolve-MeterInstallDirectory([string]$Requested) {
    if (-not $Requested) {
        $programFilesRoot = [Environment]::GetFolderPath([Environment+SpecialFolder]::ProgramFiles)
        if (-not $programFilesRoot) { $programFilesRoot = $env:ProgramFiles }
        if (-not $programFilesRoot) { throw 'Windows Program Files directory is unavailable.' }
        $Requested = Join-Path $programFilesRoot 'CodexQuotaGuard\Meter'
    }
    $resolved = [IO.Path]::GetFullPath($Requested)
    if ($resolved -eq [IO.Path]::GetPathRoot($resolved)) {
        throw 'A drive root cannot be used as a service directory.'
    }
    if (-not (Test-Path -LiteralPath ([IO.Path]::GetPathRoot($resolved)) -PathType Container -ErrorAction SilentlyContinue)) {
        throw 'The selected install drive is unavailable.'
    }
    return $resolved
}

function Resolve-MeterBundledBinary([string]$Requested, [string]$ScriptDirectory) {
    if ($Requested) {
        $resolved = [IO.Path]::GetFullPath($Requested)
        if (-not (Test-Path -LiteralPath $resolved -PathType Leaf)) {
            throw 'The bundled meter executable was not found.'
        }
        return $resolved
    }
    $adjacent = Join-Path $ScriptDirectory 'quota-guard-meter.exe'
    if (Test-Path -LiteralPath $adjacent -PathType Leaf) { return [IO.Path]::GetFullPath($adjacent) }
    return $null
}

# Loading these helpers for regression checks never touches a service or config.
if ($MyInvocation.InvocationName -eq '.') { return }

$setupStage = 'ADMIN_CHECK'
$setupReason = ''
trap {
    try { Write-MeterSetupStatus 'FAILED' $setupStage $setupReason } catch { }
    Write-Host "Meter setup failed at $setupStage ($setupReason). See meter-setup-status.json in the app data directory."
    exit 1
}
Write-MeterSetupStatus 'RUNNING' $setupStage
$serviceName = 'CodexQuotaGuardMeter'
$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
$principal = [Security.Principal.WindowsPrincipal]::new($identity)
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw 'Run this script from an elevated Administrator PowerShell.'
}

$setupStage = 'SERVER_URL_VALIDATE'
$setupReason = 'SERVER_URL_INVALID'
$uri = Resolve-MeterServerUri $ServerUrl

$setupStage = 'INSTALL_DIRECTORY'
$setupReason = 'INSTALL_DIRECTORY_UNAVAILABLE'
$install = Resolve-MeterInstallDirectory $InstallDirectory

$setupStage = 'SOURCE_BINARY'
$setupReason = 'BUNDLED_BINARY_MISSING'
$sourceBinary = Resolve-MeterBundledBinary $BinaryPath $PSScriptRoot
if (-not $sourceBinary) {
    throw 'No bundled meter executable was found. Source-tree operators must build it first and pass -BinaryPath.'
}
$sourceBinary = [IO.Path]::GetFullPath($sourceBinary)
if (-not (Test-Path -LiteralPath $sourceBinary -PathType Leaf)) {
    throw 'The Rust meter executable was not found.'
}

$setupStage = 'BINARY_CHECK'
$setupReason = ''
Test-MeterBinary $sourceBinary

$setupStage = 'PREPARE_FILES'
$appData = Join-Path $env:APPDATA 'com.codexquotaguard.desktop'
$deviceIdFile = Join-Path $appData 'meter-device-id.txt'
$pairingInbox = Join-Path $appData 'meter-pairing.json'
$statusFile = Join-Path $appData 'meter-status.json'
$userHome = [IO.Path]::GetFullPath($env:USERPROFILE)
$codexHome = Join-Path $userHome '.codex'
$stateDirectory = Join-Path $env:ProgramData 'CodexQuotaGuard\Meter'
New-Item -ItemType Directory -Path $appData, $stateDirectory, $install -Force | Out-Null

if (Test-Path -LiteralPath $deviceIdFile -PathType Leaf) {
    $deviceId = [Guid]::Parse((Get-Content -LiteralPath $deviceIdFile -Raw).Trim())
} else {
    $deviceId = [Guid]::NewGuid()
    [IO.File]::WriteAllText($deviceIdFile, $deviceId.ToString(), [Text.UTF8Encoding]::new($false))
}

$installedBinary = Join-Path $install 'quota-guard-meter.exe'
$configPath = Join-Path $install 'meter-config.json'
$service = Get-Service -Name $serviceName -ErrorAction SilentlyContinue
$existingConfiguration = $null
if ($service) {
    $setupStage = 'EXISTING_SERVICE_CHECK'
    $registered = Get-CimInstance Win32_Service -Filter "Name='$serviceName'"
    if ($registered.PathName -notmatch '^\s*"?(.+?quota-guard-meter\.exe)"?\s+service\s+--config\s+(?:"([^"]+)"|(.+?))\s*$') {
        throw 'The existing meter service has an unexpected command line.'
    }
    $existingConfigPath = [IO.Path]::GetFullPath($(if ($Matches[2]) { $Matches[2] } else { $Matches[3] }))
    if ((Get-Item -LiteralPath $existingConfigPath).Length -gt 8192) {
        throw 'The existing meter configuration is invalid.'
    }
    $setupReason = 'EXISTING_CONFIGURATION_INVALID'
    $existingConfiguration = Get-Content -LiteralPath $existingConfigPath -Raw | ConvertFrom-Json
    $setupReason = 'WINDOWS_PROFILE_MISMATCH'
    if ([IO.Path]::GetDirectoryName([string]$existingConfiguration.statusFile) -ne [IO.Path]::GetFullPath($appData) -or
        [string]$existingConfiguration.deviceId -ne $deviceId.ToString()) {
        throw 'This meter service belongs to another Windows profile or device.'
    }
    $setupStage = 'PREPARE_FILES'
    $setupReason = ''
}
if ($service -and $service.Status -ne 'Stopped') {
    Stop-Service -Name $serviceName -ErrorAction Stop
    $service.WaitForStatus('Stopped', [TimeSpan]::FromSeconds(20))
}
if ($sourceBinary -ne $installedBinary) {
    Copy-Item -LiteralPath $sourceBinary -Destination $installedBinary -Force
}
$configuration = [ordered]@{
    serverUrl = $uri.GetLeftPart([UriPartial]::Authority)
    codexHome = $codexHome
    pairingInbox = $pairingInbox
    stateDir = $stateDirectory
    statusFile = $statusFile
    deviceId = $deviceId.ToString()
}
if ($existingConfiguration) {
    # Repair executable/registration while preserving attribution and cursors.
    foreach ($field in @('codexHome', 'pairingInbox', 'stateDir', 'statusFile', 'deviceId')) {
        if (-not $existingConfiguration.$field) { throw 'The existing meter configuration is incomplete.' }
        $configuration[$field] = $existingConfiguration.$field
    }
    $stateDirectory = [string]$configuration.stateDir
}
[IO.File]::WriteAllText($configPath, ($configuration | ConvertTo-Json -Compress),
    [Text.UTF8Encoding]::new($false))

# The device reporting token is kept under ProgramData and is not part of the UI.
$setupStage = 'PROTECT_FILES'
& icacls.exe $stateDirectory /inheritance:r /grant:r '*S-1-5-18:(OI)(CI)F' '*S-1-5-32-544:(OI)(CI)F' | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Could not protect the meter state directory.' }
& icacls.exe $install /inheritance:r /grant:r '*S-1-5-18:(OI)(CI)F' '*S-1-5-32-544:(OI)(CI)F' | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Could not protect the meter installation directory.' }

$imagePath = '"' + $installedBinary + '" service --config "' + $configPath + '"'
$setupStage = 'SERVICE_REGISTER'
if ($null -eq $service) {
    New-Service -Name $serviceName -BinaryPathName $imagePath -DisplayName 'Codex Quota Guard Meter' `
        -Description 'Reports local Codex token usage for one Windows profile.' `
        -StartupType Automatic | Out-Null
} else {
    # CIM passes the quoted image path as one value; Windows PowerShell's
    # legacy native argument quoting can strip quotes around Program Files.
    $changed = Invoke-CimMethod -InputObject $registered -MethodName Change `
        -Arguments @{ PathName = $imagePath; StartMode = 'Automatic' }
    if ($changed.ReturnValue -ne 0) { throw 'Could not update the existing meter service.' }
}
$setupStage = 'SERVICE_RECOVERY'
& sc.exe failure $serviceName reset= 86400 actions= restart/5000/restart/5000/restart/30000 | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Could not configure service recovery.' }
$setupStage = 'SERVICE_START'
Start-Service -Name $serviceName -ErrorAction Stop
$running = Get-Service -Name $serviceName
$running.WaitForStatus('Running', [TimeSpan]::FromSeconds(20))
[IO.File]::WriteAllText((Join-Path $appData 'meter-connection.json'),
    (@{serverUrl = $configuration.serverUrl;
        binaryHash = (Get-FileHash -LiteralPath $installedBinary -Algorithm SHA256).Hash} | ConvertTo-Json -Compress),
    [Text.UTF8Encoding]::new($false))
Write-MeterSetupStatus 'SUCCEEDED' 'READY'
Write-Host "Rust meter service is running: $serviceName"
Write-Host "Device ID: $deviceId"
Write-Host "Private activity log: $(Join-Path $stateDirectory 'logs\meter.jsonl')"
Write-Host 'Reopen Quota Guard and sign in to pair this service. No computer restart was performed.'
