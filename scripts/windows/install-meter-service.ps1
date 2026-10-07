#requires -Version 5.1
[CmdletBinding()]
param(
    [string]$ServerUrl = 'http://192.168.0.102:8080',
    [string]$InstallDirectory = '',
    [string]$TargetDirectory = '',
    [string]$BinaryPath = ''
)

$ErrorActionPreference = 'Stop'
$serviceName = 'CodexQuotaGuardMeter'
$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
$principal = [Security.Principal.WindowsPrincipal]::new($identity)
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw 'Run this script from an elevated Administrator PowerShell.'
}

$uri = $null
if (-not [Uri]::TryCreate($ServerUrl, [UriKind]::Absolute, [ref]$uri)) {
    throw 'ServerUrl must be an absolute URL.'
}
$privateHttp = $uri.Scheme -eq 'http' -and ($uri.IsLoopback -or
    $uri.Host -match '^(10\.|192\.168\.|172\.(1[6-9]|2[0-9]|3[01])\.)')
if (($uri.Scheme -ne 'https' -and -not $privateHttp) -or
    $uri.AbsolutePath -ne '/' -or $uri.UserInfo -or $uri.Query -or $uri.Fragment) {
    throw 'ServerUrl must be an HTTPS origin or a private LAN/loopback HTTP origin.'
}

$hasBuildDrive = Test-Path -LiteralPath 'D:\' -PathType Container
if (-not $InstallDirectory) {
    $InstallDirectory = if ($hasBuildDrive) { 'D:\BuildCache\pingche\meter-service' }
        else { Join-Path $env:ProgramFiles 'CodexQuotaGuard\Meter' }
}
if (-not $TargetDirectory) {
    $TargetDirectory = if ($hasBuildDrive) { 'D:\BuildCache\pingche\meter-target' }
        else { Join-Path $env:TEMP 'CodexQuotaGuard-MeterBuild' }
}
$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$meterManifest = Join-Path $repositoryRoot 'apps\client\meter\Cargo.toml'
$target = [IO.Path]::GetFullPath($TargetDirectory)
$install = [IO.Path]::GetFullPath($InstallDirectory)
if ($install -eq [IO.Path]::GetPathRoot($install) -or $target -eq [IO.Path]::GetPathRoot($target)) {
    throw 'A drive root cannot be used as a service or build directory.'
}
if (-not (Test-Path -LiteralPath ([IO.Path]::GetPathRoot($install)) -PathType Container)) {
    throw 'The selected install drive is unavailable.'
}

if (-not $BinaryPath -and (Test-Path -LiteralPath (Join-Path $PSScriptRoot 'quota-guard-meter.exe') -PathType Leaf)) {
    $BinaryPath = Join-Path $PSScriptRoot 'quota-guard-meter.exe'
}
if (-not $BinaryPath) {
    if (-not (Test-Path -LiteralPath $meterManifest -PathType Leaf)) {
        throw 'No bundled Rust meter executable or source manifest was found.'
    }
    $cargo = Get-Command cargo.exe -ErrorAction Stop | Select-Object -First 1
    New-Item -ItemType Directory -Path $target -Force | Out-Null
    $previous = [Environment]::GetEnvironmentVariable('CARGO_TARGET_DIR', 'Process')
    try {
        $env:CARGO_TARGET_DIR = $target
        & $cargo.Source build --offline --release --manifest-path $meterManifest
        if ($LASTEXITCODE -ne 0) { throw 'Rust meter build failed.' }
    } finally {
        [Environment]::SetEnvironmentVariable('CARGO_TARGET_DIR', $previous, 'Process')
    }
    $BinaryPath = Join-Path $target 'release\quota-guard-meter.exe'
}
$sourceBinary = [IO.Path]::GetFullPath($BinaryPath)
if (-not (Test-Path -LiteralPath $sourceBinary -PathType Leaf)) {
    throw 'The Rust meter executable was not found.'
}

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
[IO.File]::WriteAllText($configPath, ($configuration | ConvertTo-Json -Compress),
    [Text.UTF8Encoding]::new($false))

# The device reporting token is kept under ProgramData and is not part of the UI.
& icacls.exe $stateDirectory /inheritance:r /grant:r '*S-1-5-18:(OI)(CI)F' '*S-1-5-32-544:(OI)(CI)F' | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Could not protect the meter state directory.' }
& icacls.exe $install /inheritance:r /grant:r '*S-1-5-18:(OI)(CI)F' '*S-1-5-32-544:(OI)(CI)F' | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Could not protect the meter installation directory.' }

$imagePath = '"' + $installedBinary + '" service --config "' + $configPath + '"'
if ($null -eq $service) {
    New-Service -Name $serviceName -BinaryPathName $imagePath -DisplayName 'Codex Quota Guard Meter' `
        -Description 'Reports local Codex token usage for one Windows profile.' `
        -StartupType Automatic | Out-Null
} else {
    & sc.exe config $serviceName binPath= $imagePath start= auto | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Could not update the existing meter service.' }
}
& sc.exe failure $serviceName reset= 86400 actions= restart/5000/restart/5000/restart/30000 | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Could not configure service recovery.' }
Start-Service -Name $serviceName -ErrorAction Stop
$running = Get-Service -Name $serviceName
$running.WaitForStatus('Running', [TimeSpan]::FromSeconds(20))
Write-Host "Rust meter service is running: $serviceName"
Write-Host "Device ID: $deviceId"
Write-Host 'Reopen Quota Guard and sign in to pair this service. No computer restart was performed.'
