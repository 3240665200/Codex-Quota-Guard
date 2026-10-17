#requires -Version 5.1
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '..\install-meter-service.ps1')

function Assert-Equal($Actual, $Expected) {
    if ($Actual -cne $Expected) { throw "Expected $Expected, got $Actual" }
}
function Assert-Rejected([scriptblock]$Action) {
    $rejected = $false
    try { & $Action | Out-Null } catch { $rejected = $true }
    if (-not $rejected) { throw 'Expected unsafe state to be rejected.' }
}

$fixture = Join-Path ([IO.Path]::GetTempPath()) ('meter-origin-fixture-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $fixture | Out-Null
$journal = Join-Path $fixture 'usage-outbox.json'
$configPath = Join-Path $fixture 'meter-config.json'
$credential = Join-Path $fixture 'meter-credential.json'
try {
    $startedAt = [DateTime]::UtcNow
    $status = [pscustomobject]@{version='0.2.5';lastScanEpochSeconds=([DateTimeOffset]$startedAt).ToUnixTimeSeconds();errorCode=$null}
    Assert-Equal (Test-MeterStartedStatus $status $startedAt.AddMilliseconds(-1) '0.2.5' $startedAt) $false
    Assert-Equal (Test-MeterStartedStatus $status $startedAt.AddMilliseconds(1) '0.2.5' $startedAt) $true
    $status.errorCode='METER_OUTBOX_IDENTITY_MISMATCH'
    Assert-Equal (Test-MeterStartedStatus $status $startedAt.AddMilliseconds(1) '0.2.5' $startedAt) $false
    $status.errorCode='METER_OUTBOX_INVALID'
    Assert-Equal (Test-MeterStartedStatus $status $startedAt.AddMilliseconds(1) '0.2.5' $startedAt) $false
    $config = [pscustomobject]@{
        serverUrl='http://127.0.0.1:18080'; deviceId=[Guid]::NewGuid().ToString()
        codexHome='C:\Users\fixture\.codex'; stateDir=$fixture
    }
    Assert-Equal (Test-MeterStateTransition $config 'http://127.0.0.1:18080/') $false
    Assert-Equal (Test-MeterStateTransition $config 'https://new.example') $true
    [IO.File]::WriteAllText($credential, '{"deviceToken":"fixture-not-a-real-token"}')
    Assert-Equal (Test-MeterStateTransition $config 'http://127.0.0.1:18080') $true
    Assert-Equal ([IO.File]::ReadAllText($credential)) '{"deviceToken":"fixture-not-a-real-token"}'
    $box = @{
        deviceId=$config.deviceId; codexHome=$config.codexHome
        serverOrigin=$config.serverUrl; userId=[Guid]::NewGuid().ToString()
        pending=@(); quarantined=@(); scanState=@{files=@{}}
    }
    [IO.File]::WriteAllText($journal, ($box | ConvertTo-Json -Depth 8))
    $before = (Get-FileHash -LiteralPath $journal).Hash
    Assert-Equal (Test-MeterStateTransition $config 'http://127.0.0.1:18080') $false
    Assert-Equal (Test-MeterStateTransition $config 'https://new.example') $true
    # Repair the real upgrade shape: settings are new but an empty journal is old.
    $config.serverUrl='https://new.example'
    Assert-Equal (Test-MeterStateTransition $config 'https://new.example') $true
    Assert-Equal (Get-FileHash -LiteralPath $journal).Hash $before
    $box.serverOrigin='https://new.example'
    [IO.File]::WriteAllText($journal, ($box | ConvertTo-Json -Depth 8))
    Assert-Equal (Test-MeterStateTransition $config 'https://new.example:443/') $false
    $box.deviceId=[Guid]::NewGuid().ToString()
    [IO.File]::WriteAllText($journal, ($box | ConvertTo-Json -Depth 8))
    Assert-Rejected { Test-MeterStateTransition $config 'https://another.example' }
    [IO.File]::WriteAllText($journal, '{broken')
    Assert-Rejected { Test-MeterStateTransition $config 'https://another.example' }
    Assert-Equal ([IO.File]::ReadAllText($journal)) '{broken'
    Write-MeterConfigurationAtomic $configPath $config
    $config.serverUrl='https://other.example'
    Write-MeterConfigurationAtomic $configPath $config
    Assert-Equal ((Get-Content -LiteralPath $configPath -Raw | ConvertFrom-Json).serverUrl) 'https://other.example'
    $configBefore=[IO.File]::ReadAllText($configPath)
    $locked=[IO.File]::Open($configPath,[IO.FileMode]::Open,[IO.FileAccess]::Read,[IO.FileShare]::None)
    try { Assert-Rejected { Write-MeterConfigurationAtomic $configPath $config } }
    finally { $locked.Dispose() }
    Assert-Equal ([IO.File]::ReadAllText($configPath)) $configBefore
} finally {
    foreach ($file in @($journal,$configPath,$credential)) {
        if (Test-Path -LiteralPath $file -PathType Leaf) { Remove-Item -LiteralPath $file }
    }
    Remove-Item -LiteralPath $fixture
}
Write-Output 'Meter server transition preflight and atomic-write tests passed.'
