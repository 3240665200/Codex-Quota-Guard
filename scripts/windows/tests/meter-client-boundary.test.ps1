#requires -Version 5.1
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '..\installer-meter.ps1')
. (Join-Path $PSScriptRoot '..\configure-meter-server.ps1') -ServerUrl 'https://new.example' -ExpectedUserSid 'S-1-5-21-100'
. (Join-Path $PSScriptRoot '..\start-client.ps1')

function Assert-Equal($Actual, $Expected) {
    if ($Actual -cne $Expected) { throw "Expected '$Expected', got '$Actual'" }
}
function Assert-Rejected([scriptblock]$Action) {
    $rejected = $false
    try { & $Action | Out-Null } catch { $rejected = $true }
    if (-not $rejected) { throw 'Expected operation to fail.' }
}

# Exercise the real recovery writer against a fake native adapter. Neither
# successful calls nor injected failure can change the host's service settings.
$script:scCalls = [Collections.Generic.List[string]]::new()
$script:failFlag = $false
function sc.exe {
    $script:scCalls.Add(($args -join ' '))
    $global:LASTEXITCODE = if ($script:failFlag -and $args[0] -eq 'failureflag') { 5 } else { 0 }
}
Set-MeterServiceRecovery 'CodexQuotaGuardMeter'
Assert-Equal $script:scCalls.Count 2
Assert-Equal $script:scCalls[0] 'failure CodexQuotaGuardMeter reset= 86400 actions= restart/5000/restart/5000/restart/30000'
Assert-Equal $script:scCalls[1] 'failureflag CodexQuotaGuardMeter 1'
$script:failFlag = $true
Assert-Rejected { Set-MeterServiceRecovery 'CodexQuotaGuardMeter' }

# Verify argument transport including spaces/apostrophes, without elevation.
$encoded = New-InstallerMeterElevation "C:\My App\O'Brien\installer-meter.ps1" 'Install' 'S-1-5-21-100'
$decoded = [Text.Encoding]::Unicode.GetString([Convert]::FromBase64String($encoded))
$match = [regex]::Match($decoded, "FromBase64String\('([^']+)'\)")
$request = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($match.Groups[1].Value)) | ConvertFrom-Json
Assert-Equal $request.script "C:\My App\O'Brien\installer-meter.ps1"
Assert-Equal $request.sid 'S-1-5-21-100'
Assert-Equal $request.action 'Install'
Assert-Equal (Resolve-ClientServerOrigin 'http://0.0.0.0:8080') 'http://127.0.0.1:8080'
Assert-Rejected { Resolve-ClientServerOrigin 'https://user:pass@example.test' }

$testRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..\..\dist'))
$fixture = Join-Path $testRoot ('meter-boundary-' + [Guid]::NewGuid().ToString('N'))
$previousAppData = $env:APPDATA
New-Item -ItemType Directory -Path $fixture -Force | Out-Null
try {
    $env:APPDATA = Join-Path $fixture 'profile'
    $appDirectory = Join-Path $env:APPDATA 'com.codexquotaguard.desktop'
    $oldState = Join-Path $fixture 'original-state'
    $newState = Join-Path $fixture 'separate-state'
    New-Item -ItemType Directory -Path $appDirectory, $oldState, $newState -Force | Out-Null
    $script:configFile = Join-Path $fixture 'meter-config.json'
    $script:statusFile = Join-Path $appDirectory 'meter-status.json'
    $connectionFile = Join-Path $appDirectory 'meter-connection.json'
    $binary = Join-Path $fixture 'quota-guard-meter.exe'
    [IO.File]::WriteAllText($binary, 'fixture bytes, never executed')
    $device = [Guid]::NewGuid().ToString()
    $configuration = @{
        serverUrl='https://old.example';ownerUserSid='S-1-5-21-100';deviceId=$device
        stateDir=$oldState;logDir=(Join-Path $oldState 'logs');codexHome=(Join-Path $fixture '.codex')
        pairingInbox=(Join-Path $appDirectory 'meter-pairing.json');statusFile=$script:statusFile
    }
    Write-MeterConfigurationAtomic $script:configFile $configuration
    Write-MeterConfigurationAtomic $connectionFile @{serverUrl='https://old.example';binaryHash='preserved';binaryVersion='0.2.5'}
    $outbox = Join-Path $oldState 'usage-outbox.json'
    Write-MeterConfigurationAtomic $outbox @{
        deviceId=$device;codexHome=$configuration.codexHome;serverOrigin='https://old.example'
        scanState=@{files=@{}};pending=@(@{eventId='preserve-42';tokens=42})
    }
    $journalHash = (Get-FileHash -LiteralPath $outbox).Hash
    $binaryHash = (Get-FileHash -LiteralPath $binary).Hash
    Assert-Equal (Get-InstallerMeterServer $appDirectory) 'https://old.example'
    Write-MeterConfigurationAtomic (Join-Path $appDirectory 'server-config.json') @{url='https://new.example'}
    Assert-Equal (Get-InstallerMeterServer $appDirectory) 'https://new.example'

    $script:service = [pscustomobject]@{Status='Running'}
    $script:service | Add-Member ScriptMethod WaitForStatus { param($state,$timeout)
        if ($this.Status -ne $state) { throw 'Fixture service did not reach expected state.' }
    }
    $script:imagePath = '"' + $binary + '" service --config "' + $script:configFile + '"'
    $script:starts = 0
    $script:stops = 0
    $script:startFailure = $false
    function Assert-MeterConfigurationPermission([string]$ExpectedSid) {
        Assert-Equal $ExpectedSid 'S-1-5-21-100'
        return 'S-1-5-21-100'
    }
    function Get-CimInstance { param($ClassName,$Filter) return [pscustomobject]@{PathName=$script:imagePath} }
    function Get-Service { param($Name) return $script:service }
    function Stop-Service { param($Name) $script:stops++; $script:service.Status='Stopped' }
    function Start-Service { param($Name)
        $script:starts++
        if ($script:startFailure) { $script:startFailure=$false; throw 'Injected start failure' }
        $script:service.Status='Running'
        Write-MeterConfigurationAtomic $script:statusFile @{
            version='0.2.5';lastScanEpochSeconds=[DateTimeOffset]::UtcNow.ToUnixTimeSeconds();errorCode=$null
        }
    }
    function Test-MeterBinary { param($Path) return '0.2.5' }
    function New-MeterServerState { param($Binary,$ConfigPath) return $newState }
    function New-Service { throw 'Configuration must never install a service.' }

    # Successful change keeps the binary and the old owner's durable outbox.
    Invoke-MeterServerConfiguration 'https://new.example' 'S-1-5-21-100'
    $updated = Get-Content -LiteralPath $script:configFile -Raw | ConvertFrom-Json
    Assert-Equal $updated.serverUrl 'https://new.example'
    Assert-Equal $updated.stateDir $newState
    Assert-Equal $updated.deviceId $device
    Assert-Equal (Get-FileHash -LiteralPath $outbox).Hash $journalHash
    Assert-Equal (Get-FileHash -LiteralPath $binary).Hash $binaryHash
    Assert-Equal $script:starts 1
    # Saving the same origin is a read-only no-op, never a keepalive restart.
    Invoke-MeterServerConfiguration 'https://new.example' 'S-1-5-21-100'
    Assert-Equal $script:starts 1
    Assert-Equal $script:stops 1
    Remove-Item -LiteralPath $connectionFile
    Invoke-MeterServerConfiguration 'https://new.example' 'S-1-5-21-100'
    Assert-Equal ((Get-Content -LiteralPath $connectionFile -Raw | ConvertFrom-Json).serverUrl) 'https://new.example'
    Assert-Equal $script:starts 1
    Assert-Equal $script:stops 1

    $beforeConfig = Get-Content -LiteralPath $script:configFile -Raw | ConvertFrom-Json
    $beforeConnection = Get-Content -LiteralPath $connectionFile -Raw | ConvertFrom-Json
    $script:startFailure = $true
    Assert-Rejected { Invoke-MeterServerConfiguration 'https://third.example' 'S-1-5-21-100' }
    $restored = Get-Content -LiteralPath $script:configFile -Raw | ConvertFrom-Json
    $restoredConnection = Get-Content -LiteralPath $connectionFile -Raw | ConvertFrom-Json
    Assert-Equal $restored.serverUrl $beforeConfig.serverUrl
    Assert-Equal $restored.stateDir $beforeConfig.stateDir
    Assert-Equal $restoredConnection.serverUrl $beforeConnection.serverUrl
    Assert-Equal $script:service.Status 'Running'
    Assert-Equal (Get-FileHash -LiteralPath $outbox).Hash $journalHash
    Assert-Equal (Get-FileHash -LiteralPath $binary).Hash $binaryHash
    Write-Output 'Installer recovery, encoded UAC transport, configuration no-op and rollback passed using isolated OS adapters.'
} finally {
    $env:APPDATA = $previousAppData
    if ([IO.Path]::GetDirectoryName([IO.Path]::GetFullPath($fixture)) -cne $testRoot -or
        [IO.Path]::GetFileName($fixture) -notmatch '^meter-boundary-[a-f0-9]{32}$') { throw 'Unexpected test cleanup path.' }
    Remove-Item -LiteralPath $fixture -Recurse -Force
}
