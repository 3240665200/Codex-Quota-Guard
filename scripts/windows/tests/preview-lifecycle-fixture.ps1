#requires -Version 5.1
param([ValidateSet('Legacy','Detached','Sleep','WmiWorker')][string]$Mode, [string]$OutputDirectory)
$ErrorActionPreference = 'Stop'
if ($Mode -eq 'Sleep') { Start-Sleep -Seconds 30; exit 0 }
if ($Mode -eq 'WmiWorker') {
    Import-Module (Join-Path $PSScriptRoot '..\preview-lifecycle.psm1') -Force -ErrorAction Stop
    $control=Get-Content -LiteralPath (Join-Path $OutputDirectory 'control.json') -Raw | ConvertFrom-Json
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
    @{ ErrorCode='PREVIEW_DETACHED_START_FAILED'; ErrorType=$_.Exception.GetType().FullName } |
        ConvertTo-Json -Compress | Set-Content -LiteralPath $identityFile -Encoding UTF8
}
Start-Sleep -Seconds 30
