#requires -Version 5.1
# Explicit WMI supervisor launch; no account operation or automatic restart is performed.
[CmdletBinding()]
param(
    [string]$ConfigPath = 'D:\BuildCache\pingche\private\admin-backend.clixml',
    [string]$PreviewDirectory = '',
    [int]$BackendPort = 18080,
    [int]$FrontendPort = 8000,
    [string]$ProxyKernel = 'D:\codex-tools\sing-box-1.13.14\sing-box-1.13.14-windows-amd64\sing-box.exe',
    [switch]$EnableWebLogin,
    [string]$BrowserExecutable = '',
    [string]$AuthorizationPresetFile = '',
    [string]$PackageDirectory = ''
)
$ErrorActionPreference='Stop'
if (!$PreviewDirectory) { $PreviewDirectory = Join-Path (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\..')).Path '.local\runtime\admin-preview' }
Import-Module (Join-Path $PSScriptRoot 'preview-lifecycle.psm1') -Force
if ($PackageDirectory) {
    if (![IO.Path]::IsPathRooted($PackageDirectory)) { throw 'PREVIEW_PACKAGE_PATH_INVALID' }
    $PackageDirectory = (Resolve-Path -LiteralPath $PackageDirectory -ErrorAction Stop).Path
    Assert-PreviewPath $PackageDirectory
    foreach ($packageFile in @('backend.jar', 'web\index.html', 'package-manifest.json')) {
        if (!(Test-Path -LiteralPath (Join-Path $PackageDirectory $packageFile) -PathType Leaf)) { throw 'PREVIEW_PACKAGE_INCOMPLETE' }
    }
    $packageManifest = Get-Content -LiteralPath (Join-Path $PackageDirectory 'package-manifest.json') -Raw | ConvertFrom-Json
    if ((Get-FileHash -LiteralPath (Join-Path $PackageDirectory 'backend.jar') -Algorithm SHA256).Hash -ne $packageManifest.backendSha256) { throw 'PREVIEW_PACKAGE_HASH_MISMATCH' }
}
foreach($path in @($ConfigPath,$PreviewDirectory,$ProxyKernel)){Assert-PreviewPath $path}
if(!(Test-Path -LiteralPath $ConfigPath -PathType Leaf)){throw 'PREVIEW_CONFIG_MISSING'}
if(!(Test-Path -LiteralPath $ProxyKernel -PathType Leaf)){throw 'PREVIEW_PROXY_KERNEL_MISSING'}
foreach($port in @($BackendPort,$FrontendPort)) {
    if($port -lt 1024 -or $port -gt 65535 -or @(Get-NetTCPConnection -State Listen -ErrorAction Stop|Where-Object LocalPort -eq $port).Count -gt 0){throw 'PREVIEW_PORT_UNAVAILABLE'}
}
if($BackendPort -eq $FrontendPort){throw 'PREVIEW_PORTS_MUST_DIFFER'}
if($AuthorizationPresetFile) {
    if(![IO.Path]::IsPathRooted($AuthorizationPresetFile) -or !(Test-Path -LiteralPath $AuthorizationPresetFile -PathType Leaf)){throw 'PREVIEW_AUTHORIZATION_PRESET_INVALID'}
    Assert-PreviewPath $AuthorizationPresetFile
}
if($EnableWebLogin) {
    if(!$BrowserExecutable -or ![IO.Path]::IsPathRooted($BrowserExecutable) -or !(Test-Path -LiteralPath $BrowserExecutable -PathType Leaf)){throw 'PREVIEW_BROWSER_EXECUTABLE_INVALID'}
    Assert-PreviewPath $BrowserExecutable
}
Set-PreviewPrivateDirectory $PreviewDirectory
$runs=Join-Path $PreviewDirectory 'runs';Set-PreviewPrivateDirectory $runs
$runId=[Guid]::NewGuid().ToString('N');$run=Join-Path $runs $runId;Set-PreviewPrivateDirectory $run
$tools=@{}
$toolSpecs = @(@('Java','java.exe'),@('Node','node.exe'))
if (!$PackageDirectory) { $toolSpecs += @(@('Maven','mvn.cmd'),@('Pnpm','pnpm.cmd')) }
foreach($tool in $toolSpecs) {
    $tools[$tool[0]]=Resolve-PreviewToolPath (Get-Command $tool[1] -ErrorAction Stop|Select-Object -First 1).Source
}
$tools['ComSpec']=Join-Path $env:SystemRoot 'System32\cmd.exe';Assert-PreviewPath $tools.ComSpec
$request=@{Schema=1;ExpectedSid=[Security.Principal.WindowsIdentity]::GetCurrent().User.Value;
    ConfigPath=[IO.Path]::GetFullPath($ConfigPath);PreviewDirectory=[IO.Path]::GetFullPath($PreviewDirectory);
    BackendPort=$BackendPort;FrontendPort=$FrontendPort;ProxyKernel=$ProxyKernel;EnableWebLogin=[bool]$EnableWebLogin;
    BrowserExecutable=$BrowserExecutable;AuthorizationPresetFile=$AuthorizationPresetFile;PackageDirectory=$PackageDirectory;Tools=$tools;StartupTimeoutSeconds=180}
[IO.File]::WriteAllText((Join-Path $run 'launch.json'),($request|ConvertTo-Json -Depth 5),[Text.UTF8Encoding]::new($false))
$shell=[Diagnostics.Process]::GetCurrentProcess().MainModule.FileName
$worker=Join-Path $PSScriptRoot 'preview-supervisor.ps1';Assert-PreviewPath $worker
$identity=Start-PreviewDetachedWorker -Executable $shell -Arguments @('-NoLogo','-NoProfile','-NonInteractive','-File',$worker,'-RunDirectory',$run) -WorkingDirectory $run
$statusPath=Join-Path $run 'status.json';$deadline=[DateTime]::UtcNow.AddSeconds(195)
while([DateTime]::UtcNow -lt $deadline) {
    if(Test-Path -LiteralPath $statusPath) {
        $state=Read-PreviewState -Path $statusPath
        if($state.Supervisor.Pid -ne $identity.Pid -or ([DateTime]$state.Supervisor.CreatedUtc).ToUniversalTime().Ticks -ne ([DateTime]$identity.CreatedUtc).ToUniversalTime().Ticks){throw 'PREVIEW_SUPERVISOR_IDENTITY_CHANGED'}
        if($state.Phase -eq 'READY') {
            [pscustomobject]@{Url="http://localhost:$FrontendPort/#/official-accounts";LaunchMode='WMI';RunDirectory=$run;StatusPath=$statusPath;
                SupervisorPid=$identity.Pid;SupervisorCreatedUtc=$identity.CreatedUtc;BackendLauncherPid=$state.Backend.Launcher.Pid;
                BackendListenerPid=$state.Backend.Listener.Pid;FrontendLauncherPid=$state.Frontend.Launcher.Pid;FrontendListenerPid=$state.Frontend.Listener.Pid;
                BackendLog=(Join-Path $run 'api.out.log');FrontendLog=(Join-Path $run 'ui.out.log')}
            return
        }
        if($state.Phase -in @('FAILED','EXITED')){throw ('PREVIEW_START_FAILED code='+$state.ErrorCode+' status='+$statusPath)}
    }
    try {$check=[Diagnostics.Process]::GetProcessById([int]$identity.Pid);if($check.StartTime.ToUniversalTime().Ticks -ne ([DateTime]$identity.CreatedUtc).ToUniversalTime().Ticks){throw 'PREVIEW_SUPERVISOR_IDENTITY_CHANGED'};$check.Dispose()}
    catch {throw ('PREVIEW_SUPERVISOR_EXITED status='+$statusPath)}
    Start-Sleep -Milliseconds 500
}
Request-PreviewStop $run|Out-Null
throw ('PREVIEW_START_TIMEOUT status='+$statusPath)
