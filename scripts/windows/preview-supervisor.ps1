#requires -Version 5.1
# One independent preview run; no restart loop or account-operation replay.
[CmdletBinding()]
param([Parameter(Mandatory)][string]$RunDirectory)
$ErrorActionPreference='Stop'
Import-Module (Join-Path $PSScriptRoot 'preview-lifecycle.psm1') -Force
Set-PreviewPrivateDirectory $RunDirectory
$requestPath=Join-Path $RunDirectory 'launch.json';Assert-PreviewPath $requestPath
$request=Get-Content -LiteralPath $requestPath -Raw|ConvertFrom-Json
$ConfigPath=$request.ConfigPath;$PreviewDirectory=$request.PreviewDirectory
$BackendPort=[int]$request.BackendPort;$FrontendPort=[int]$request.FrontendPort
$ProxyKernel=$request.ProxyKernel;$EnableWebLogin=[bool]$request.EnableWebLogin;$BrowserExecutable=$request.BrowserExecutable
$AuthorizationPresetFile=if($request.PSObject.Properties['AuthorizationPresetFile']){[string]$request.AuthorizationPresetFile}else{''}
$PackageDirectory=if($request.PSObject.Properties['PackageDirectory']){[string]$request.PackageDirectory}else{''}
$state=@{Schema=1;RunId=(Split-Path -Leaf $RunDirectory);Phase='STARTING';ErrorCode=$null;LaunchMode='WMI';
    Supervisor=@{Pid=$PID;CreatedUtc=[Diagnostics.Process]::GetCurrentProcess().StartTime.ToUniversalTime().ToString('o');JobFlags=[Pingche.Preview.Native]::CurrentJobFlags()};
    Backend=@{Port=$BackendPort;Launcher=$null;Listener=$null;LauncherExitCode=$null;ListenerExitCode=$null};
    Frontend=@{Port=$FrontendPort;Launcher=$null;Listener=$null;LauncherExitCode=$null;ListenerExitCode=$null}}
$job=$null;$backend=$null;$frontend=$null;$backendListener=$null;$frontendListener=$null
try {
    Assert-PreviewWorkerIdentity -ExpectedSid $request.ExpectedSid
    Write-PreviewState $RunDirectory $state 'worker_created'
    $repository=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\..')).Path
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
    Assert-PreviewPath $ConfigPath
    $settings=Import-Clixml -LiteralPath $ConfigPath
    if($settings -isnot [Collections.IDictionary]){throw 'PREVIEW_CONFIG_INVALID'}
    $runtimeKeys=@('DATABASE_URL','DATABASE_USERNAME','DATABASE_PASSWORD','JWT_SECRET','JWT_ISSUER','ACCESS_TOKEN_TTL',
        'REFRESH_TOKEN_TTL','AUTH_RATE_LIMIT_PER_MINUTE','MAIL_ASSISTANCE_ENCRYPTION_KEY')
    foreach($key in @('DATABASE_URL','DATABASE_USERNAME','DATABASE_PASSWORD','JWT_SECRET')) {
        if(!$settings.Contains($key) -or $settings[$key] -isnot [Security.SecureString]){throw 'PREVIEW_PROTECTED_CONFIG_REQUIRED'}
    }
    Set-PreviewPrivateDirectory $PreviewDirectory
    $featureFile=Join-Path $PreviewDirectory 'feature-keys.clixml';Assert-PreviewPath $featureFile
    $featureKeys=@('OFFICIAL_SESSION_ENCRYPTION_KEY','MANAGED_PROXY_ENCRYPTION_KEY')
    $browserKeys=@('WEB_LOGIN_BROWSER_ENABLED','WEB_LOGIN_BROWSER_EXECUTABLE','WEB_LOGIN_BROWSER_HEADLESS')
    if(Test-Path -LiteralPath $featureFile){$features=Import-Clixml -LiteralPath $featureFile}
    else {
        $features=@{};$random=[Security.Cryptography.RandomNumberGenerator]::Create()
        try {
            foreach($key in $featureKeys){$bytes=New-Object byte[] 32;$random.GetBytes($bytes);$features[$key]=ConvertTo-SecureString ([Convert]::ToBase64String($bytes)) -AsPlainText -Force;[Array]::Clear($bytes,0,$bytes.Length)}
        } finally {$random.Dispose()}
        try {$features|Export-Clixml -LiteralPath $featureFile -NoClobber}
        catch {if(!(Test-Path -LiteralPath $featureFile)){throw};$features=Import-Clixml -LiteralPath $featureFile}
    }
    if($features -isnot [Collections.IDictionary]){throw 'PREVIEW_FEATURE_KEYS_INVALID'}
    foreach($key in $featureKeys){if(!$features.Contains($key) -or $features[$key] -isnot [Security.SecureString]){throw 'PREVIEW_FEATURE_KEYS_INVALID'}}
    $apiLog=Join-Path $RunDirectory 'api.out.log';$apiError=Join-Path $RunDirectory 'api.err.log'
    $uiLog=Join-Path $RunDirectory 'ui.out.log';$uiError=Join-Path $RunDirectory 'ui.err.log'
    $toolDirectories=@($request.Tools.Maven,$request.Tools.Pnpm,$request.Tools.Java,$request.Tools.Node|Where-Object{$_}|ForEach-Object{Split-Path -Parent $_})
    $apiEnvironment=Get-PreviewBaseEnvironment -ToolDirectories $toolDirectories
    $uiEnvironment=Get-PreviewBaseEnvironment -ToolDirectories $toolDirectories
    $job=[Pingche.Preview.OwnedChildJob]::new()
    try {
        foreach($key in $runtimeKeys){if($settings.Contains($key)){if($settings[$key] -isnot [Security.SecureString]){throw 'PREVIEW_PROTECTED_CONFIG_REQUIRED'};$apiEnvironment[$key]=[pscredential]::new('preview',$settings[$key]).GetNetworkCredential().Password}}
        foreach($key in $featureKeys){$apiEnvironment[$key]=[pscredential]::new('preview',$features[$key]).GetNetworkCredential().Password}
        $apiEnvironment['SERVER_ADDRESS']='127.0.0.1';$apiEnvironment['WEB_SERVER_PORT']=[string]$BackendPort
        $apiEnvironment['APP_REQUIRE_HTTPS']='false';$apiEnvironment['SERVER_FORWARD_HEADERS_STRATEGY']='none'
        $apiEnvironment['MANAGEMENT_ALLOW_LOOPBACK_HTTP']='true';$apiEnvironment['MANAGED_PROXY_SING_BOX_PATH']=$ProxyKernel
        $apiEnvironment['WEB_LOGIN_BROWSER_ENABLED']=if($EnableWebLogin){'true'}else{'false'}
        if($EnableWebLogin){$apiEnvironment['WEB_LOGIN_BROWSER_EXECUTABLE']=$BrowserExecutable}
        $apiEnvironment['WEB_LOGIN_BROWSER_HEADLESS']='true'
        if($AuthorizationPresetFile){$apiEnvironment['OFFICIAL_AUTHORIZATION_PRESET_FILE']=$AuthorizationPresetFile}
        $apiEnvironment['JAVA_HOME']=Split-Path -Parent (Split-Path -Parent $request.Tools.Java)
        $apiEnvironment['CORS_ALLOWED_ORIGINS']="http://localhost:$FrontendPort,http://127.0.0.1:$FrontendPort"
        if ($PackageDirectory) {
            Assert-PreviewPath $PackageDirectory
            $packagedJar=Join-Path $PackageDirectory 'backend.jar'
            if (!(Test-Path -LiteralPath $packagedJar -PathType Leaf)) { throw 'PREVIEW_PACKAGE_INCOMPLETE' }
            $backend=$job.Spawn($request.Tools.Java,('-jar ' + [Pingche.Preview.Native]::Quote($packagedJar)),$PackageDirectory,$apiEnvironment,$apiLog,$apiError)
        } else {
            $backend=$job.Spawn($request.Tools.ComSpec,(New-PreviewBatchArguments $request.Tools.Maven @('-o','-f',(Join-Path $repository 'apps\admin\backend\pom.xml'),'-Dmaven.test.skip=true','spring-boot:run')),$repository,$apiEnvironment,$apiLog,$apiError)
        }
    } finally {$apiEnvironment.Clear()}
    # UI receives a separately built allowlist environment; backend secrets and inherited NODE_OPTIONS never enter it.
    $uiEnvironment['HOST']='127.0.0.1';$uiEnvironment['PORT']=[string]$FrontendPort
    $uiEnvironment['ADMIN_API_TARGET']="http://127.0.0.1:$BackendPort"
    $hook=(Join-Path $PSScriptRoot 'preview-loopback.cjs').Replace('\','/')
    $uiEnvironment['NODE_OPTIONS']='--require "'+$hook+'"'
    if ($PackageDirectory) {
        $uiEnvironment['ADMIN_OUTPUT_DIRECTORY']=Join-Path $PackageDirectory 'web'
        $adminFrontend=Join-Path $repository 'apps\admin\frontend'
        $maxCli=Join-Path $adminFrontend 'node_modules\@umijs\max\bin\max.js'
        $maxCli=Resolve-PreviewToolPath -Path $maxCli
        $frontend=$job.Spawn($request.Tools.Node,(([Pingche.Preview.Native]::Quote($maxCli)) + ' preview --port ' + $FrontendPort),$adminFrontend,$uiEnvironment,$uiLog,$uiError)
    } else {
        $frontend=$job.Spawn($request.Tools.ComSpec,(New-PreviewBatchArguments $request.Tools.Pnpm @('dev:admin')),$repository,$uiEnvironment,$uiLog,$uiError)
    }
    $uiEnvironment.Clear()
    $state.Backend.Launcher=@{Pid=$backend.Id;CreatedUtc=$backend.CreatedUtc.ToString('o')}
    $state.Frontend.Launcher=@{Pid=$frontend.Id;CreatedUtc=$frontend.CreatedUtc.ToString('o')}
    Write-PreviewState $RunDirectory $state 'services_created'
    $started=[Diagnostics.Stopwatch]::StartNew()
    while($true) {
        $stop=Join-Path $RunDirectory 'stop.request'
        if(Test-Path -LiteralPath $stop){Assert-PreviewPath $stop;if((Get-Content -LiteralPath $stop -Raw) -ne $state.RunId){throw 'PREVIEW_STOP_REQUEST_INVALID'};$state.Phase='STOPPING';Write-PreviewState $RunDirectory $state 'stop_requested';break}
        if($backend.HasExited){$state.Backend.LauncherExitCode=$backend.ExitCode;throw 'PREVIEW_BACKEND_EXITED'}
        if($frontend.HasExited){$state.Frontend.LauncherExitCode=$frontend.ExitCode;throw 'PREVIEW_FRONTEND_EXITED'}
        $apiIdentity=Get-PreviewListener $BackendPort $job;$uiIdentity=Get-PreviewListener $FrontendPort $job
        if($state.Phase -eq 'READY') {
            if(!$apiIdentity -or $apiIdentity.Pid -ne $state.Backend.Listener.Pid -or $apiIdentity.CreatedUtc -ne $state.Backend.Listener.CreatedUtc){throw 'PREVIEW_BACKEND_LISTENER_CHANGED'}
            if(!$uiIdentity -or $uiIdentity.Pid -ne $state.Frontend.Listener.Pid -or $uiIdentity.CreatedUtc -ne $state.Frontend.Listener.CreatedUtc){throw 'PREVIEW_FRONTEND_LISTENER_CHANGED'}
        } elseif($apiIdentity -and $uiIdentity) {
            $state.Backend.Listener=$apiIdentity;$state.Frontend.Listener=$uiIdentity
            $backendListener=$job.ObserveListener([int]$apiIdentity.Pid);$frontendListener=$job.ObserveListener([int]$uiIdentity.Pid)
            $state.Phase='READY';Write-PreviewState $RunDirectory $state 'ready'
        } elseif($started.Elapsed.TotalSeconds -gt [Math]::Max(1,[Math]::Min(180,[int]$request.StartupTimeoutSeconds))){throw 'PREVIEW_STARTUP_TIMEOUT'}
        Start-Sleep -Milliseconds 500
    }
} catch {
    $state.Phase='FAILED';$state.ErrorCode=Get-PreviewFailureCode $_.Exception
    Write-PreviewState $RunDirectory $state 'failed'
} finally {
    # Closing only this run's private Job forcibly stops its children; it is not a graceful application logout.
    Write-PreviewState $RunDirectory $state 'owned_job_cleanup'
    if($null -ne $job){$job.Dispose()}
    foreach($item in @(@('Backend',$backend,$backendListener),@('Frontend',$frontend,$frontendListener))) {
        if($null -ne $item[1]){$null=$item[1].WaitForExit(5000);$state[$item[0]].LauncherExitCode=$item[1].ExitCode;$item[1].Dispose()}
        if($null -ne $item[2]){$null=$item[2].WaitForExit(5000);$state[$item[0]].ListenerExitCode=$item[2].ExitCode;$item[2].Dispose()}
    }
    if($state.Phase -ne 'FAILED'){$state.Phase='EXITED'}
    Write-PreviewState $RunDirectory $state 'worker_exit'
}
