#requires -Version 5.1
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '..\ensure-meter-service.ps1')

function Assert-Equal($Actual, $Expected) {
    if ($Actual -ne $Expected) { throw "Expected $Expected, got $Actual" }
}
Assert-Equal (Get-MeterStartupAction '' '' 'http://127.0.0.1:8080') 'INSTALL'
Assert-Equal (Get-MeterStartupAction 'Stopped' 'http://127.0.0.1:8080' 'http://127.0.0.1:8080') 'START'
Assert-Equal (Get-MeterStartupAction 'Running' 'http://127.0.0.1:8080' 'http://127.0.0.1:8080') 'READY'
Assert-Equal (Get-MeterStartupAction 'Running' 'http://192.168.0.102:8080' 'http://127.0.0.1:8080') 'CONFIGURE'
Assert-Equal (Get-MeterStartupAction 'Running' 'http://127.0.0.1:8080' 'http://127.0.0.1:8080' 'old' 'new') 'UPGRADE'
Assert-Equal (Get-MeterStartupAction 'Running' 'http://127.0.0.1:8080' 'http://127.0.0.1:8080' 'same' 'same') 'READY'
Assert-Equal (Normalize-MeterServerUrl 'http://0.0.0.0:8080') 'http://127.0.0.1:8080'
$config = [pscustomobject]@{
    serverUrl = 'http://192.168.0.102:8080'; codexHome = 'C:\Users\test\.codex'
    statusFile = 'C:\Users\test\AppData\Roaming\com.codexquotaguard.desktop\meter-status.json'
    deviceId = 'immutable-device'; stateDir = 'C:\ProgramData\CodexQuotaGuard\Meter'
}
$updated = Set-MeterServerInConfig $config 'http://127.0.0.1:8080'
Assert-Equal $updated.serverUrl 'http://127.0.0.1:8080'
Assert-Equal $updated.deviceId 'immutable-device'
Assert-Equal $updated.codexHome 'C:\Users\test\.codex'
Assert-Equal $updated.stateDir 'C:\ProgramData\CodexQuotaGuard\Meter'
Write-Output 'Meter startup policy tests passed.'
