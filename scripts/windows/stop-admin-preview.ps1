#requires -Version 5.1
[CmdletBinding()]
param([Parameter(Mandatory)][string]$RunDirectory)
$ErrorActionPreference='Stop'
# Stop only this recorded preview run. Finish browser login/business operations first.
Import-Module (Join-Path $PSScriptRoot 'preview-lifecycle.psm1') -Force
Request-PreviewStop -RunDirectory $RunDirectory
