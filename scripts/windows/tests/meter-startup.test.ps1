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
Assert-Equal (Get-MeterStartupAction 'Running' 'http://127.0.0.1:8080' 'http://127.0.0.1:8080' '' 'new') 'UPGRADE'
Assert-Equal (Get-MeterStartupAction 'Running' 'http://127.0.0.1:8080' 'http://127.0.0.1:8080' 'same' 'same') 'READY'
Assert-Equal (Get-MeterStartupAction 'Running' 'http://127.0.0.1:8080' 'http://127.0.0.1:8080' 'same' 'same' 'actual-old') 'UPGRADE'
Assert-Equal (Get-MeterStartupAction 'Running' 'http://127.0.0.1:8080' 'http://127.0.0.1:8080' 'record-old' 'new-bundle' 'new-bundle') 'READY'
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
$bundledBinary = Join-Path $PSScriptRoot '..\..\..\apps\client\frontend\src-tauri\resources\meter\quota-guard-meter.exe'
if (Test-Path -LiteralPath $bundledBinary -PathType Leaf) {
    $identity = Get-MeterBinaryIdentity $bundledBinary
    Assert-Equal $identity.binaryVersion (Get-MeterBinaryVersion $bundledBinary)
    Assert-Equal $identity.binaryHash (Get-FileHash -LiteralPath $bundledBinary -Algorithm SHA256).Hash
    $connectionFixture = Join-Path ([IO.Path]::GetTempPath()) ('meter connection test ' + [Guid]::NewGuid().ToString('N') + '.json')
    try {
        Write-MeterConnection $connectionFixture 'http://127.0.0.1:8080' $identity
        $connection = Get-Content -LiteralPath $connectionFixture -Raw | ConvertFrom-Json
        Assert-Equal $connection.serverUrl 'http://127.0.0.1:8080'
        Assert-Equal $connection.binaryHash $identity.binaryHash
        Assert-Equal $connection.binaryVersion $identity.binaryVersion
    } finally {
        Remove-Item -LiteralPath $connectionFixture -ErrorAction SilentlyContinue
    }
}
$missingBinaryRejected = $false
try { Get-MeterBinaryIdentity (Join-Path $PSScriptRoot 'missing-meter.exe') | Out-Null }
catch { $missingBinaryRejected = $true }
Assert-Equal $missingBinaryRejected $true
Write-Output 'Meter startup policy tests passed.'
