#requires -Version 5.1
param([ValidateSet('Legacy','Detached','Sleep','WmiWorker')][string]$Mode, [string]$OutputDirectory)
$ErrorActionPreference = 'Stop'
if ($Mode -eq 'Sleep') { Start-Sleep -Seconds 30; exit 0 }
if ($Mode -eq 'WmiWorker') {
    Import-Module (Join-Path $PSScriptRoot '..\preview-lifecycle.psm1') -Force -ErrorAction Stop
    $control=Get-Content -LiteralPath (Join-Path $OutputDirectory 'control.json') -Raw | ConvertFrom-Json
    $self=Get-CimInstance Win32_Process -Filter "ProcessId=$PID"
    $parent=Get-CimInstance Win32_Process -Filter "ProcessId=$($self.ParentProcessId)"
    @{Pid=$PID;ParentPid=$self.ParentProcessId;ParentName=$parent.Name;InAnyJob=[Pingche.Preview.Native]::CurrentInAnyJob();JobFlags=[Pingche.Preview.Native]::CurrentJobFlags();Sid=[Security.Principal.WindowsIdentity]::GetCurrent().User.Value} |
        ConvertTo-Json -Compress | Set-Content -LiteralPath (Join-Path $OutputDirectory 'job.json') -Encoding UTF8
    Assert-PreviewWorkerIdentity -ExpectedSid $control.ExpectedSid
    $protected=Import-Clixml -LiteralPath (Join-Path $OutputDirectory 'synthetic-secret.clixml')
    $valid=[pscredential]::new('synthetic',$protected).GetNetworkCredential().Password -eq 'preview-dpapi-synthetic'
    if(!$valid){throw 'Synthetic DPAPI mismatch'}
    @{ Sid=[Security.Principal.WindowsIdentity]::GetCurrent().User.Value; DpapiVerified=$true; Independent=$true } |
        ConvertTo-Json -Compress | Set-Content -LiteralPath (Join-Path $OutputDirectory 'ready.json') -Encoding UTF8
    Start-Sleep -Seconds 30; exit 0
}
$shell = [Diagnostics.Process]::GetCurrentProcess().MainModule.FileName
$identityFile = Join-Path $OutputDirectory 'identity.json'
try {
    if ($Mode -eq 'Legacy') {
        $child = Start-Process -FilePath $shell -ArgumentList '-NoLogo','-NoProfile','-NonInteractive','-File',
            "`"$PSCommandPath`"",'-Mode','Sleep' -WindowStyle Hidden -PassThru
        $identity = @{ Pid=$child.Id; CreatedUtc=$child.StartTime.ToUniversalTime().ToString('o') }
    } else {
        Import-Module (Join-Path $PSScriptRoot '..\preview-lifecycle.psm1') -Force -ErrorAction Stop
        $identity = Start-PreviewDetachedWorker -Executable $shell -Arguments @('-NoLogo','-NoProfile','-NonInteractive','-File',$PSCommandPath,'-Mode','WmiWorker','-OutputDirectory',$OutputDirectory) `
            -WorkingDirectory $OutputDirectory
    }
    $identity | ConvertTo-Json -Compress | Set-Content -LiteralPath $identityFile -Encoding UTF8
} catch {
    $safeCode = if($_.Exception.Message -match '^PREVIEW_[A-Z0-9_]+$'){$_.Exception.Message}else{'PREVIEW_TEST_HELPER_ERROR'}
    @{ ErrorCode='PREVIEW_DETACHED_START_FAILED'; ErrorType=$_.Exception.GetType().FullName; SafeCode=$safeCode } |
        ConvertTo-Json -Compress | Set-Content -LiteralPath $identityFile -Encoding UTF8
}
Start-Sleep -Seconds 30
