#requires -Version 5.1
[CmdletBinding()]
param([string]$OutputDirectory='')
$ErrorActionPreference='Stop'
$repositoryRoot=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\..')).Path
$frontend=Join-Path $repositoryRoot 'apps\client\frontend'
Push-Location -LiteralPath $frontend
try {
    # Builds the one standalone service executable, without packaging the desktop UI.
    & pnpm.cmd build:meter
    if($LASTEXITCODE -ne 0){throw 'Standalone meter build failed.'}
    if($OutputDirectory){& node.exe package-meter.mjs $OutputDirectory}else{& node.exe package-meter.mjs}
    if($LASTEXITCODE -ne 0){throw 'Standalone meter signing failed.'}
} finally {Pop-Location}
