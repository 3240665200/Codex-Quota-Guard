#requires -Version 5.1
$ErrorActionPreference='Stop'
Import-Module (Join-Path $PSScriptRoot '..\preview-lifecycle.psm1') -Force
$testRoot=Join-Path ([IO.Path]::GetTempPath()) ('pingche-preview-policy-test-'+[Guid]::NewGuid().ToString('N'))
Set-PreviewPrivateDirectory $testRoot
$real=Join-Path $testRoot 'real';New-Item -ItemType Directory -Path $real|Out-Null
$fake=Join-Path $real 'synthetic-node.exe';[IO.File]::WriteAllText($fake,'not executable; metadata test only')
$link=Join-Path $testRoot 'manager-link';New-Item -ItemType Junction -Path $link -Target $real|Out-Null
$linked=Join-Path $link 'synthetic-node.exe'
$blocked=$false;try{Assert-PreviewPath $linked}catch{$blocked=$true}
if(!$blocked){throw 'Private data paths must continue to reject reparse components.'}
$resolved=Resolve-PreviewToolPath $linked
if(![string]::Equals($resolved,$fake,[StringComparison]::OrdinalIgnoreCase)){throw 'Tool manager link did not resolve to its final file.'}
Write-Output 'PASS tool manager link resolves, private data link stays rejected'
$sentinelName='PINGCHE_PREVIEW_SYNTHETIC_SECRET_'+[Guid]::NewGuid().ToString('N')
[Environment]::SetEnvironmentVariable($sentinelName,'synthetic-env-only','Process')
try {
    $environment=Get-PreviewBaseEnvironment
    if($environment.ContainsKey($sentinelName) -or $environment.ContainsKey('NODE_OPTIONS') -or $environment.ContainsKey('DATABASE_PASSWORD')){throw 'Base environment inherited a secret or preload.'}
} finally {[Environment]::SetEnvironmentVariable($sentinelName,$null,'Process')}
Write-Output 'PASS child base environment excludes ambient secrets and Node preloads'

# A second synthetic Job represents an unrelated listener; no production process is used.
$foreignJob=[Pingche.Preview.OwnedChildJob]::new();$otherJob=[Pingche.Preview.OwnedChildJob]::new();$foreign=$null
try {
    $reservation=[Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback,0);$reservation.Start();$port=$reservation.LocalEndpoint.Port;$reservation.Stop()
    $shell=[Diagnostics.Process]::GetCurrentProcess().MainModule.FileName
    $arguments=(@('-NoLogo','-NoProfile','-NonInteractive','-File',(Join-Path $PSScriptRoot 'preview-owned-fixture.ps1'),'-Mode','Server','-Port',[string]$port)|ForEach-Object{[Pingche.Preview.Native]::Quote($_)}) -join ' '
    $foreign=$foreignJob.Spawn($shell,$arguments,$testRoot,(Get-PreviewBaseEnvironment),(Join-Path $testRoot 'foreign.out.log'),(Join-Path $testRoot 'foreign.err.log'))
    $deadline=[DateTime]::UtcNow.AddSeconds(10);$found=$null
    while(!$found -and [DateTime]::UtcNow -lt $deadline){$found=Get-PreviewListener $port $foreignJob;if(!$found){Start-Sleep -Milliseconds 50}}
    if(!$found){throw 'Synthetic foreign listener did not start.'}
    $rejected=$false;try{Get-PreviewListener $port $otherJob|Out-Null}catch{$rejected=$true}
    if(!$rejected -or $foreign.HasExited){throw 'An unrelated listener was accepted or terminated.'}
    Write-Output 'PASS unrelated owned-test listener is rejected without termination'
    $run=Join-Path $testRoot ([Guid]::NewGuid().ToString('N'));Set-PreviewPrivateDirectory $run
    $state=@{RunId=(Split-Path -Leaf $run);Phase='READY';ErrorCode=$null;Supervisor=@{Pid=$foreign.Id;CreatedUtc=$foreign.CreatedUtc.AddSeconds(-1).ToString('o')};Backend=@{};Frontend=@{}}
    Write-PreviewState $run $state 'synthetic_identity'
    $rejected=$false;try{Request-PreviewStop $run|Out-Null}catch{$rejected=$true}
    if(!$rejected -or (Test-Path -LiteralPath (Join-Path $run 'stop.request')) -or $foreign.HasExited){throw 'A stale process identity was allowed to stop another run.'}
    Write-Output 'PASS stale creation time cannot request a stop'
} finally {$otherJob.Dispose();$foreignJob.Dispose();if($null -ne $foreign){$null=$foreign.WaitForExit(5000);$foreign.Dispose()}}
