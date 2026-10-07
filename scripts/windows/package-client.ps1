#requires -Version 5.1
[CmdletBinding()]
param(
    [string]$TargetDirectory = 'D:\BuildCache\pingche\desktop-package-target'
)

$ErrorActionPreference = 'Stop'
$repositoryRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\..')).Path
$frontendDirectory = Join-Path $repositoryRoot 'apps\client\frontend'
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
Require-Command 'mvn.cmd' | Out-Null
Require-Command 'java.exe' | Out-Null
Require-Command 'cargo.exe' | Out-Null
Require-Command 'rustc.exe' | Out-Null

New-Item -ItemType Directory -Path $targetDirectory -Force | Out-Null
Write-Host "Building desktop installer. Rust output: $targetDirectory"
$startedAt = Get-Date
$previousTargetDirectory = [Environment]::GetEnvironmentVariable('CARGO_TARGET_DIR', 'Process')

try {
    $env:CARGO_TARGET_DIR = $targetDirectory
    Push-Location -LiteralPath $frontendDirectory
    try {
        & $pnpm tauri build --bundles nsis --ci
        if ($LASTEXITCODE -ne 0) {
            throw "Desktop packaging failed (exit code $LASTEXITCODE)."
        }
    } finally {
        Pop-Location
    }
} finally {
    if ($null -eq $previousTargetDirectory) {
        Remove-Item -LiteralPath 'Env:CARGO_TARGET_DIR' -ErrorAction SilentlyContinue
    } else {
        [Environment]::SetEnvironmentVariable('CARGO_TARGET_DIR', $previousTargetDirectory, 'Process')
    }
}

$bundleDirectory = Join-Path $targetDirectory 'release\bundle\nsis'
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
foreach ($installer in $installers) {
    Write-Host $installer.FullName
}
Write-Host 'The installed app requires Java 17 or newer on the target PC.'
Write-Host 'The Rust meter executable and install script are bundled in the app meter directory.'
Write-Host 'Install the Windows service from an elevated PowerShell on each target PC; the setup does not install it silently.'
