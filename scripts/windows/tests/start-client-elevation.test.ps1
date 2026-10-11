#requires -Version 5.1
$ErrorActionPreference = 'Stop'
$scriptPath = Join-Path $PSScriptRoot '..\start-client.ps1'
. $scriptPath

function Assert-Equal($Actual, $Expected) {
    if ($Actual -ne $Expected) { throw "Expected '$Expected', got '$Actual'" }
}

function Assert-True([bool]$Value, [string]$Message) {
    if (-not $Value) { throw $Message }
}

Assert-ClientExpectedUser 'S-1-5-21-current' 'S-1-5-21-current'
$wrongUserRejected = $false
try { Assert-ClientExpectedUser 'S-1-5-21-other' 'S-1-5-21-current' }
catch { $wrongUserRejected = $true }
Assert-Equal $wrongUserRejected $true
Assert-Equal (Get-ClientElevationFailureMessage 23) 'Administrator approval must use the same Windows user as the desktop client.'
Assert-Equal (Get-ClientElevationFailureMessage 1) 'Elevated desktop client startup failed (exit code 1).'

Assert-Equal (Resolve-ClientMeterInstallDecision $true 'n') $false
Assert-Equal (Resolve-ClientMeterInstallDecision $false 'Y') $true
Assert-Equal (Resolve-ClientMeterInstallDecision $false '') $true
$cancelRejected = $false
try { Resolve-ClientMeterInstallDecision $false 'n' | Out-Null }
catch { $cancelRejected = $true }
Assert-Equal $cancelRejected $true

# Verify argument transport without executing the encoded command or starting a process.
$inputParameters = @{
    ExpectedUserSid = 'S-1-5-21-current'
    MeterInstallDecisionMade = $true
    InstallMeter = $false
    LogDirectory = "C:\Build Cache\O'Brien logs"
    ControlPlaneUrl = 'https://quota.example.test'
}
$encodedCommand = New-ClientElevationCommand 'C:\Source Tree\start-client.ps1' $inputParameters
$decodedCommand = [Text.Encoding]::Unicode.GetString([Convert]::FromBase64String($encodedCommand))
$payloadMatch = [regex]::Match($decodedCommand, "FromBase64String\('(?<payload>[^']+)'\)")
Assert-True $payloadMatch.Success 'Elevation command did not contain its encoded parameter payload.'
$payloadJson = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($payloadMatch.Groups['payload'].Value))
$payload = $payloadJson | ConvertFrom-Json
Assert-Equal $payload.scriptPath 'C:\Source Tree\start-client.ps1'
Assert-Equal $payload.parameters.ExpectedUserSid $inputParameters.ExpectedUserSid
Assert-Equal $payload.parameters.MeterInstallDecisionMade $true
Assert-Equal $payload.parameters.InstallMeter $false
Assert-Equal $payload.parameters.LogDirectory $inputParameters.LogDirectory
Assert-Equal $payload.parameters.ControlPlaneUrl $inputParameters.ControlPlaneUrl

# Check the control-flow contract without invoking Start-Process, Read-Host, service installation, or process termination.
$source = Get-Content -LiteralPath $scriptPath -Raw
$promptIndex = $source.IndexOf('Read-Host')
$elevationIndex = $source.IndexOf('$elevated = Start-Process')
Assert-True ($promptIndex -ge 0 -and $promptIndex -lt $elevationIndex) 'First-install interaction must happen before elevation.'
$elevationEnd = $source.IndexOf('} catch {', $elevationIndex)
$elevationBlock = $source.Substring($elevationIndex, $elevationEnd - $elevationIndex)
Assert-True ($elevationBlock -match '-Verb RunAs') 'The desktop startup must request elevation.'
Assert-True ($elevationBlock -match '-WindowStyle Hidden') 'The elevated PowerShell process must be hidden.'
Assert-True ($elevationBlock -match '-Wait -PassThru') 'The launcher must report the elevated process result.'
Assert-True ($source -match 'MeterInstallDecisionMade = \$true') 'The elevated invocation must skip interactive prompts.'

Write-Output 'Desktop client elevation policy tests passed without starting or installing anything.'
