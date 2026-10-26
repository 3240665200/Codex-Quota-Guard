#requires -Version 5.1
[CmdletBinding()]
param(
    [string]$TargetDirectory = '',
    [string]$OutputDirectory = '',
    [switch]$BundleOnly
)

$ErrorActionPreference = 'Stop'
$repositoryRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\..')).Path
$frontendDirectory = Join-Path $repositoryRoot 'apps\client\frontend'
$version = (Get-Content -LiteralPath (Join-Path $frontendDirectory 'package.json') -Raw | ConvertFrom-Json).version
$cacheDirectory = 'D:\BuildCache\pingche\cache'
if (-not $TargetDirectory) { $TargetDirectory = Join-Path $cacheDirectory 'rust' }
if (-not $OutputDirectory) { $OutputDirectory = "D:\BuildCache\pingche\artifacts\desktop\$version" }
$targetDirectory = [System.IO.Path]::GetFullPath($TargetDirectory)
$targetDrive = [System.IO.Path]::GetPathRoot($targetDirectory)

if (-not (Test-Path -LiteralPath $targetDrive -PathType Container)) {
    throw "Build drive is unavailable: $targetDrive. Pass -TargetDirectory with an existing drive."
}

function Require-Command([string]$Name) {
    $command = Get-Command $Name -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($null -eq $command) {
        throw "Required build tool was not found: $Name"
    }
    return $command.Source
}

$pnpm = Require-Command 'pnpm.cmd'
$node = Require-Command 'node.exe'
Require-Command 'cargo.exe' | Out-Null
Require-Command 'rustc.exe' | Out-Null

New-Item -ItemType Directory -Path $targetDirectory -Force | Out-Null
$temporaryDirectory = Join-Path $cacheDirectory 'tmp'
$cargoHome = Join-Path $cacheDirectory 'cargo'
New-Item -ItemType Directory -Path $temporaryDirectory,$cargoHome -Force | Out-Null
# Seed public dependency downloads only; never copy registry credentials.
$originalCargoHome = [Environment]::GetEnvironmentVariable('CARGO_HOME','Process')
if (!$originalCargoHome) { $originalCargoHome = Join-Path ([Environment]::GetFolderPath('UserProfile')) '.cargo' }
$registrySource = Join-Path $originalCargoHome 'registry'
if (!(Test-Path -LiteralPath (Join-Path $cargoHome 'registry')) -and (Test-Path -LiteralPath $registrySource)) {
    Copy-Item -LiteralPath $registrySource -Destination (Join-Path $cargoHome 'registry') -Recurse
}
Write-Host "Building desktop installer. Rust output: $targetDirectory"
Write-Host "Build temporary files: $temporaryDirectory"
Write-Host "Installer output: $OutputDirectory"
$startedAt = Get-Date
$buildEnvironment = @{CARGO_TARGET_DIR=$targetDirectory; CARGO_HOME=$cargoHome; TEMP=$temporaryDirectory; TMP=$temporaryDirectory; TMPDIR=$temporaryDirectory}
$previousEnvironment = @{}
foreach ($name in $buildEnvironment.Keys) {
    $previousEnvironment[$name] = [Environment]::GetEnvironmentVariable($name,'Process')
}

try {
    foreach ($name in $buildEnvironment.Keys) { [Environment]::SetEnvironmentVariable($name,$buildEnvironment[$name],'Process') }
    Push-Location -LiteralPath $frontendDirectory
    try {
        if ($BundleOnly) { & $pnpm exec tauri bundle --bundles nsis --ci }
        else { & $pnpm build --bundles nsis --ci }
        if ($LASTEXITCODE -ne 0) {
            throw "Desktop packaging failed (exit code $LASTEXITCODE)."
        }
    } finally {
        Pop-Location
    }
} finally {
    foreach ($name in $previousEnvironment.Keys) { [Environment]::SetEnvironmentVariable($name,$previousEnvironment[$name],'Process') }
}

$bundleDirectory = Join-Path $targetDirectory 'release\bundle\nsis'
$meterManifest = Join-Path $repositoryRoot 'apps\client\meter\Cargo.toml'
$meterResource = Join-Path $frontendDirectory 'src-tauri\resources\meter\quota-guard-meter.exe'
$builtMeter = Join-Path $targetDirectory 'release\quota-guard-meter.exe'
$meterVerifier = Join-Path $frontendDirectory 'meter-package-verification.mjs'
& $node $meterVerifier $meterManifest $meterResource $builtMeter
if ($LASTEXITCODE -ne 0) {
    throw 'Packaging finished with a missing or outdated Rust meter resource.'
}
$installers = @(
    Get-ChildItem -LiteralPath $bundleDirectory -Filter '*.exe' -File -ErrorAction SilentlyContinue |
        Where-Object { $_.LastWriteTime -ge $startedAt.AddSeconds(-3) } |
        Sort-Object LastWriteTime -Descending
)
if ($installers.Count -eq 0) {
    throw "Packaging finished, but no new installer was found in $bundleDirectory"
}

Write-Host ''
Write-Host 'Windows installer is ready:'
$releaseDirectory = [IO.Path]::GetFullPath($OutputDirectory)
$meterVersion = ((& $builtMeter --version) -replace '^quota-guard-meter ', '').Trim()
New-Item -ItemType Directory -Path $releaseDirectory -Force | Out-Null
foreach ($installer in $installers) {
    $destination = Join-Path $releaseDirectory $installer.Name
    $sha256 = (Get-FileHash -LiteralPath $installer.FullName -Algorithm SHA256).Hash
    Move-Item -LiteralPath $installer.FullName -Destination $destination -Force
    if ((Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash -ne $sha256) { throw 'Installer transfer verification failed.' }
    [ordered]@{ version=$version; meterVersion=$meterVersion; installer=$installer.Name; sha256=$sha256; createdAt=[DateTime]::UtcNow.ToString('o') } |
        ConvertTo-Json | Set-Content -LiteralPath (Join-Path $releaseDirectory 'package-manifest.json') -Encoding utf8
    Write-Host $destination
}
Write-Host 'The installer includes the independent Rust statistics service and the offline WebView2 runtime installer.'
Write-Host 'The target PC does not need Java, Maven, Node.js, pnpm, or Rust installed.'
Write-Host 'The Rust meter executable, install script, and safe log viewer are bundled in the app meter directory.'
Write-Host 'The installer uses the original current-user scope and allows a custom installation directory.'
Write-Host 'The statistics service is mandatory. Setup configures automatic Windows startup and failure recovery.'
Write-Host 'The desktop uses Axios to contact the central server and includes no Java or local HTTP backend.'
