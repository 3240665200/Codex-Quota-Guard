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
