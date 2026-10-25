#requires -Version 5.1
[CmdletBinding()]
param(
    [string]$PackageDirectory = '',
    [string]$ConfigPath = 'D:\BuildCache\pingche\private\admin-backend.clixml',
    [string]$PreviewDirectory = 'D:\BuildCache\pingche\management-preview',
    [switch]$NoBrowser
)
$ErrorActionPreference = 'Stop'
$repositoryRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\..')).Path
if (!$PackageDirectory) {
    $version = (Get-Content -LiteralPath (Join-Path $repositoryRoot 'package.json') -Raw | ConvertFrom-Json).version
    $PackageDirectory = Join-Path $repositoryRoot "dist\backend-v$version"
}
$run = & (Join-Path $PSScriptRoot 'start-admin-preview.ps1') -PackageDirectory $PackageDirectory -ConfigPath $ConfigPath -PreviewDirectory $PreviewDirectory
$run | Format-List | Out-Host
Write-Host ("Stop this run: & '" + (Join-Path $PSScriptRoot 'stop-admin-preview.ps1') + "' -RunDirectory '" + $run.RunDirectory + "'")
if (!$NoBrowser) { Start-Process -FilePath 'http://localhost:8000/#/user/login' }
$run
