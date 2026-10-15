#requires -Version 5.1
$ErrorActionPreference='Stop'
Import-Module (Join-Path $PSScriptRoot '..\preview-lifecycle.psm1') -Force
$testRoot=Join-Path ([IO.Path]::GetTempPath()) ('pingche-preview-supervisor-test-'+[Guid]::NewGuid().ToString('N'))
Set-PreviewPrivateDirectory $testRoot
$shell=[Diagnostics.Process]::GetCurrentProcess().MainModule.FileName
$worker=Join-Path $PSScriptRoot '..\preview-supervisor.ps1'
$fixtureTools=Join-Path $PSScriptRoot 'preview-tools'
function Assert-Case([bool]$Value,[string]$Message){if(!$Value){throw $Message}}
function Free-Port { $reservation=[Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback,0);$reservation.Start();try{return $reservation.LocalEndpoint.Port}finally{$reservation.Stop()} }
function New-SyntheticRun([string]$Maven='mvn.cmd',[string]$Pnpm='pnpm.cmd',[int]$Timeout=15) {
    $preview=Join-Path $testRoot 'preview';Set-PreviewPrivateDirectory $preview
    $run=Join-Path $preview ([Guid]::NewGuid().ToString('N'));Set-PreviewPrivateDirectory $run
    $config=Join-Path $testRoot 'synthetic-config.clixml'
    if(!(Test-Path -LiteralPath $config)){
        $settings=@{};foreach($key in @('DATABASE_URL','DATABASE_USERNAME','DATABASE_PASSWORD','JWT_SECRET')){$settings[$key]=ConvertTo-SecureString 'synthetic-preview-secret' -AsPlainText -Force};$settings|Export-Clixml -LiteralPath $config
    }
    $request=@{Schema=1;ExpectedSid=[Security.Principal.WindowsIdentity]::GetCurrent().User.Value;ConfigPath=$config;PreviewDirectory=$preview;
        BackendPort=(Free-Port);FrontendPort=(Free-Port);ProxyKernel=(Join-Path $env:SystemRoot 'System32\cmd.exe');EnableWebLogin=$false;BrowserExecutable='';StartupTimeoutSeconds=$Timeout;
        Tools=@{Maven=(Join-Path $fixtureTools $Maven);Pnpm=(Join-Path $fixtureTools $Pnpm);Java=(Get-Command java.exe).Source;Node=(Get-Command node.exe).Source;ComSpec=(Join-Path $env:SystemRoot 'System32\cmd.exe')}}
    [IO.File]::WriteAllText((Join-Path $run 'launch.json'),($request|ConvertTo-Json -Depth 5))
    return $run
}
function Start-SyntheticRun([string]$Run) {return Start-PreviewDetachedWorker -Executable $shell -Arguments @('-NoLogo','-NoProfile','-NonInteractive','-File',$worker,'-RunDirectory',$Run) -WorkingDirectory $Run}
function Wait-Phase([string]$Run,[string[]]$Phases) {
    $deadline=[DateTime]::UtcNow.AddSeconds(35);$path=Join-Path $Run 'status.json'
    while([DateTime]::UtcNow -lt $deadline){if(Test-Path -LiteralPath $path){$state=Get-Content -LiteralPath $path -Raw|ConvertFrom-Json;if($state.Phase -in $Phases){return $state}};Start-Sleep -Milliseconds 100}
    throw 'Synthetic supervisor phase timeout.'
}
$run=New-SyntheticRun;$identity=Start-SyntheticRun $run
try {
    $ready=Wait-Phase $run @('READY','FAILED')
    Assert-Case ($ready.Phase -eq 'READY') ('Synthetic startup failed: '+$ready.ErrorCode)
    Assert-Case ($ready.Backend.Launcher.Pid -ne $ready.Backend.Listener.Pid) 'Actual API listener identity was not recorded separately.'
    Assert-Case ($ready.Frontend.Launcher.Pid -ne $ready.Frontend.Listener.Pid) 'Actual UI listener identity was not recorded separately.'
    $feature=Join-Path (Split-Path -Parent $run) 'feature-keys.clixml';$firstHash=(Get-FileHash -LiteralPath $feature).Hash
    Request-PreviewStop $run|Out-Null;$ended=Wait-Phase $run @('EXITED','FAILED')
    Assert-Case ($ended.Phase -eq 'EXITED') 'Synthetic stop did not finish.'
    Assert-Case ($null -ne $ended.Backend.ListenerExitCode -and $null -ne $ended.Frontend.ListenerExitCode) 'Actual listener exit codes were not recorded.'
    Assert-Case ((Get-Content -LiteralPath (Join-Path $run 'ui.out.log') -Raw) -match 'SYNTHETIC_UI_ENV_OK') 'UI received a backend secret.'
    Assert-Case ((Get-Content -LiteralPath (Join-Path $run 'events.jsonl') -Raw) -notmatch 'synthetic-preview-secret') 'Lifecycle metadata leaked a synthetic secret.'
    Write-Output 'PASS synthetic READY, distinct owned listeners, safe stop and exit metadata'
    $second=New-SyntheticRun;$null=Start-SyntheticRun $second;$secondState=Wait-Phase $second @('READY','FAILED')
    Assert-Case ($secondState.Phase -eq 'READY') 'Second synthetic run did not start.'
    Assert-Case ((Get-FileHash -LiteralPath $feature).Hash -eq $firstHash) 'Existing DPAPI feature keys changed.'
    Request-PreviewStop $second|Out-Null;$null=Wait-Phase $second @('EXITED','FAILED')
    Write-Output 'PASS existing synthetic DPAPI feature keys preserved'
    $early=New-SyntheticRun 'exit-mvn.cmd';$null=Start-SyntheticRun $early;$failure=Wait-Phase $early @('FAILED')
    Assert-Case ($failure.ErrorCode -eq 'PREVIEW_CHILD_EXITED') 'Early child exit was not reported.'
    Write-Output 'PASS early child exit fails startup'
    $timeout=New-SyntheticRun 'wait.cmd' 'wait.cmd' 1;$null=Start-SyntheticRun $timeout;$failure=Wait-Phase $timeout @('FAILED')
    Assert-Case ($failure.ErrorCode -eq 'PREVIEW_STARTUP_TIMEOUT') 'Missing listeners did not time out safely.'
    Write-Output 'PASS missing listener startup timeout'
} finally {if(Test-Path -LiteralPath (Join-Path $run 'status.json')){try{Request-PreviewStop $run|Out-Null}catch{}}}
Write-Output 'Synthetic supervisor tests passed; no real API/UI, credential or logout was used.'
