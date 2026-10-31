#requires -Version 5.1
[CmdletBinding()]
param([Parameter(Mandatory=$true)][string]$PlanDirectory,[switch]$Recover)
$ErrorActionPreference='Stop'

function Get-MeterUpdateVersion([string]$Binary) {
    $start=[Diagnostics.ProcessStartInfo]::new()
    $start.FileName=$Binary; $start.Arguments='--version'; $start.UseShellExecute=$false; $start.CreateNoWindow=$true
    $start.RedirectStandardOutput=$true; $start.RedirectStandardError=$true
    $start.EnvironmentVariables.Remove('QUOTA_GUARD_CODEX_CAPTURE')
    $process=[Diagnostics.Process]::Start($start)
    try {
        if(!$process.WaitForExit(10000)) { $process.Kill(); $process.WaitForExit(); throw 'METER_UPDATE_PROBE_TIMEOUT' }
        $text=$process.StandardOutput.ReadToEnd().Trim()
        if($process.ExitCode -ne 0 -or $text -notmatch '^quota-guard-meter (\d+\.\d+\.\d+)$') {throw 'METER_UPDATE_PROBE_INVALID'}
        return $Matches[1]
    } finally {$process.Dispose()}
}

function Invoke-MeterBinaryTransaction([string]$Target,[string]$Candidate,[string]$Backup,[scriptblock]$Stop,[scriptblock]$Start,[scriptblock]$Healthy) {
    $replacement=$Candidate+'.replacement'; $stopped=$false; $swapped=$false
    try {
        & $Stop; $stopped=$true
        [IO.File]::Copy($Candidate,$replacement,$true)
        # Same-volume ReplaceFile is atomic and retains the previous executable as a rollback copy.
        [IO.File]::Replace($replacement,$Target,$Backup,$true); $swapped=$true
        & $Start
        if(!(& $Healthy)) {throw 'METER_UPDATE_HEALTH_FAILED'}
    } catch {
        $failure=$_
        if($stopped) {
            if($swapped) {
                & $Stop
                if(!(Test-Path -LiteralPath $Backup -PathType Leaf)) {throw 'METER_UPDATE_BACKUP_MISSING'}
                [IO.File]::Copy($Backup,$replacement,$true)
                [IO.File]::Replace($replacement,$Target,[NullString]::Value,$true)
            }
            & $Start
        }
        throw $failure
    } finally {if(Test-Path -LiteralPath $replacement -PathType Leaf){[IO.File]::Delete($replacement)}}
}

function Write-MeterUpdateResult([string]$Path,$Value) {
    $temporary=$Path+'.'+[Guid]::NewGuid().ToString('N')+'.tmp'
    [IO.File]::WriteAllText($temporary,($Value|ConvertTo-Json -Compress),[Text.UTF8Encoding]::new($false))
    if(Test-Path -LiteralPath $Path -PathType Leaf){[IO.File]::Replace($temporary,$Path,[NullString]::Value,$true)}else{[IO.File]::Move($temporary,$Path)}
}

if([Security.Principal.WindowsIdentity]::GetCurrent().User.Value -ne 'S-1-5-18'){throw 'METER_UPDATE_SYSTEM_REQUIRED'}
$directory=[IO.Path]::GetFullPath($PlanDirectory)
$id=[IO.Path]::GetFileName($directory);$parsed=[Guid]::Empty
if(![Guid]::TryParse($id,[ref]$parsed)){throw 'METER_UPDATE_PATH_UNSAFE'}
$updates=[IO.Path]::GetDirectoryName($directory)
if([IO.Path]::GetFileName($updates) -ne '.meter-updates'){throw 'METER_UPDATE_PATH_UNSAFE'}
$install=[IO.Path]::GetDirectoryName($updates)
$lock=[IO.File]::Open((Join-Path $install '.meter-setup.lock'),[IO.FileMode]::OpenOrCreate,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None)
$plan=$null;$stage='FAILED';$code='METER_UPDATE_FAILED'
try {
    $verifier=Join-Path $directory 'verifier.exe'
    $verify=[Diagnostics.ProcessStartInfo]::new();$verify.FileName=$verifier
    $verify.Arguments='verify-update --directory "'+$directory.Replace('"','')+'"';$verify.UseShellExecute=$false;$verify.CreateNoWindow=$true
    $verify.EnvironmentVariables.Remove('QUOTA_GUARD_CODEX_CAPTURE')
    $process=[Diagnostics.Process]::Start($verify)
    try {if(!$process.WaitForExit(15000)){ $process.Kill();throw 'METER_UPDATE_VERIFY_TIMEOUT' };if($process.ExitCode -ne 0){throw 'METER_UPDATE_VERIFY_FAILED'}}finally{$process.Dispose()}
    $plan=Get-Content -LiteralPath (Join-Path $directory 'plan.json') -Raw|ConvertFrom-Json
    $target=Join-Path $install 'quota-guard-meter.exe';$candidate=Join-Path $directory 'meter-new.exe';$backup=Join-Path $directory 'previous.exe'
    $serviceName='CodexQuotaGuardMeter'
    $stop={
        $service=Get-Service -Name $serviceName
        if($service.Status -ne 'Stopped') {$service.Stop();$service.WaitForStatus([ServiceProcess.ServiceControllerStatus]::Stopped,[TimeSpan]::FromSeconds(120))}
    }.GetNewClosure()
    $start={
        $service=Get-Service -Name $serviceName
        if($service.Status -ne 'Running') {$service.Start();$service.WaitForStatus([ServiceProcess.ServiceControllerStatus]::Running,[TimeSpan]::FromSeconds(60))}
    }.GetNewClosure()
    if($Recover) {
        if((Get-FileHash -LiteralPath $backup -Algorithm SHA256).Hash -ine $plan.previousSha256){throw 'METER_UPDATE_BACKUP_INVALID'}
        & $stop
        $restore=Join-Path $directory 'restore.exe';[IO.File]::Copy($backup,$restore,$true)
        [IO.File]::Replace($restore,$target,[NullString]::Value,$true);& $start;$stage='ROLLED_BACK';$code='METER_UPDATE_INTERRUPTED'
    } else {
        if((Get-MeterUpdateVersion $candidate) -ne $plan.version){throw 'METER_UPDATE_PROBE_INVALID'}
        $expected=$plan.version;$heartbeat=Join-Path $updates 'running.json';$began=[DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
        $healthy={
            $stable=0
            $deadline=[DateTime]::UtcNow.AddSeconds(60)
            do {
                try {
                    $status=Get-Content -LiteralPath $heartbeat -Raw|ConvertFrom-Json
                    if((Get-Service -Name $serviceName).Status -eq 'Running' -and $status.version -eq $expected -and [long]$status.atEpochSeconds -ge $began){$stable++;if($stable -ge 4){return $true}}else{$stable=0}
                }catch{}
                Start-Sleep -Milliseconds 500
            }while([DateTime]::UtcNow -lt $deadline)
            return $false
        }.GetNewClosure()
        try {Invoke-MeterBinaryTransaction $target $candidate $backup $stop $start $healthy;$stage='APPLIED';$code=''}
        catch {$stage=if((Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash -ieq $plan.previousSha256){'ROLLED_BACK'}else{'ROLLBACK_FAILED'};throw}
    }
} catch {if($_.Exception.Message -match '^METER_UPDATE_[A-Z_]+$'){$code=$_.Exception.Message}}
finally {
    try {
        if($plan) {Write-MeterUpdateResult (Join-Path $updates 'last-result.json') @{stage=$stage;code=$code;version=$plan.version;directory=$id;atEpochSeconds=[DateTimeOffset]::UtcNow.ToUnixTimeSeconds()}}
        $pending=Join-Path $updates 'pending.json'
        if($stage -ne 'ROLLBACK_FAILED' -and (Test-Path -LiteralPath $pending -PathType Leaf)) {
            $record=Get-Content -LiteralPath $pending -Raw|ConvertFrom-Json
            if($record.directory -eq $id){[IO.File]::Delete($pending)}
        }
    }catch{}
    $lock.Dispose()
}
