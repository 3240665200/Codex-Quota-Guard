$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '../../../../../scripts/windows/repair-meter-service.ps1') `
    -ServerUrl 'https://example.com' -ExpectedUserSid 'S-1-5-21-1'
$existing = [pscustomobject]@{ownerUserSid='S-1-5-21-1';serverUrl='https://example.com'}
if ((Assert-MeterRepairContext $existing 'S-1-5-21-1' 'https://example.com') -ne 0) { throw 'Matching context rejected.' }
if ((Assert-MeterRepairContext $existing 'S-1-5-21-2' 'https://example.com') -ne 20) { throw 'Owner change allowed.' }
if ((Assert-MeterRepairContext $existing 'S-1-5-21-1' 'https://other.example.com') -ne 21) { throw 'Origin change allowed.' }
foreach ($state in @('Running', 'StopPending', 'Stopped')) {
    $fake = [pscustomobject]@{Status=$state;StopCount=0;WaitCount=0}
    $fake | Add-Member ScriptMethod Refresh { }
    $fake | Add-Member ScriptMethod Stop { $this.StopCount++ }
    $fake | Add-Member ScriptMethod WaitForStatus {
        param($target,$timeout)
        if ($target -ne 'Stopped' -or $timeout.TotalSeconds -ne 20) { throw 'Unbounded service stop.' }
        $this.WaitCount++
    }
    Stop-MeterServiceBounded $fake
    if ($state -eq 'Running' -and $fake.StopCount -ne 1) { throw 'Running service was not stopped once.' }
    if ($state -ne 'Running' -and $fake.StopCount -ne 0) { throw 'Stop control repeated unnecessarily.' }
    if ($state -ne 'Stopped' -and $fake.WaitCount -ne 1) { throw 'Missing bounded wait.' }
}
Write-Output 'Repair context checks passed (no service changes).'
