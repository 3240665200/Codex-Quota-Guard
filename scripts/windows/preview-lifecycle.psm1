#requires -Version 5.1
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if (-not ('Pingche.Preview.Native' -as [type])) { Add-Type -Path (Join-Path $PSScriptRoot 'preview-process.cs') }
if (-not ('Pingche.Preview.OwnedChildJob' -as [type])) { Add-Type -Path (Join-Path $PSScriptRoot 'preview-owned-process.cs') }

function Start-PreviewDetachedWorker {
    [CmdletBinding()]
    param([Parameter(Mandatory)][string]$Executable,[Parameter(Mandatory)][string[]]$Arguments,
        [Parameter(Mandatory)][string]$WorkingDirectory)
    if (![IO.Path]::IsPathRooted($Executable) -or !(Test-Path -LiteralPath $Executable -PathType Leaf)) { throw 'PREVIEW_WORKER_EXECUTABLE_INVALID' }
    $commandLine = ((@($Executable) + $Arguments | ForEach-Object { [Pingche.Preview.Native]::Quote($_) }) -join ' ')
    # Explicit WMI broker selection, never a silent fallback from ordinary Start-Process.
    $startup = New-CimInstance -ClassName Win32_ProcessStartup -Namespace root/cimv2 -ClientOnly -Property @{
        ShowWindow=[uint16]0; CreateFlags=[uint32](0x01000000 -bor 0x08000000)
    }
    $createdAfter=[DateTime]::UtcNow
    $result=Invoke-CimMethod -ClassName Win32_Process -MethodName Create -Arguments @{
        CommandLine=$commandLine; CurrentDirectory=$WorkingDirectory; ProcessStartupInformation=$startup
    } -ErrorAction Stop
    if($result.ReturnValue -ne 0 -or $result.ProcessId -le 0){throw ('PREVIEW_WMI_CREATE_FAILED_'+$result.ReturnValue)}
    $process=[Diagnostics.Process]::GetProcessById([int]$result.ProcessId)
    try {
        $created=$process.StartTime.ToUniversalTime()
        if($created -lt $createdAfter.AddSeconds(-1)){throw 'PREVIEW_WORKER_IDENTITY_INVALID'}
        return @{Pid=$process.Id;CreatedUtc=$created.ToString('o');LaunchMode='WMI'}
    } finally {$process.Dispose()}
}

function Assert-PreviewWorkerIdentity {
    param([Parameter(Mandatory)][string]$ExpectedSid)
    if(([Pingche.Preview.Native]::CurrentJobFlags() -band 0x2000) -ne 0){throw 'PREVIEW_WORKER_KILL_JOB_BOUND'}
    if([Security.Principal.WindowsIdentity]::GetCurrent().User.Value -ne $ExpectedSid){throw 'PREVIEW_WORKER_USER_MISMATCH'}
}

function Assert-PreviewPath {
    param([Parameter(Mandatory)][string]$Path)
    if(![IO.Path]::IsPathRooted($Path)){throw 'PREVIEW_PATH_INVALID'}
    $cursor=[IO.Path]::GetFullPath($Path)
    while($cursor) {
        if(Test-Path -LiteralPath $cursor) {
            if((Get-Item -LiteralPath $cursor -Force).Attributes -band [IO.FileAttributes]::ReparsePoint){throw 'PREVIEW_PATH_REPARSE_POINT'}
        }
        $next=Split-Path -Parent $cursor
        if($next -eq $cursor){break};$cursor=$next
    }
}

function Set-PreviewPrivateDirectory {
    param([Parameter(Mandatory)][string]$Path)
    Assert-PreviewPath $Path
    $sid=[Security.Principal.WindowsIdentity]::GetCurrent().User
    if(!(Test-Path -LiteralPath $Path)) {
        New-Item -ItemType Directory -Path $Path -ErrorAction Stop | Out-Null
        $acl=[Security.AccessControl.DirectorySecurity]::new();$acl.SetAccessRuleProtection($true,$false)
        foreach($identity in @($sid,[Security.Principal.SecurityIdentifier]'S-1-5-18')) {
            $acl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new($identity,'FullControl','ContainerInherit,ObjectInherit','None','Allow'))
        }
        Set-Acl -LiteralPath $Path -AclObject $acl
    }
    $actual=Get-Acl -LiteralPath $Path
    $owner=$actual.GetOwner([Security.Principal.SecurityIdentifier]).Value
    if($owner -ne $sid.Value -or !$actual.AreAccessRulesProtected){throw 'PREVIEW_DIRECTORY_NOT_PRIVATE'}
    foreach($rule in $actual.GetAccessRules($true,$true,[Security.Principal.SecurityIdentifier])) {
        if($rule.AccessControlType -eq 'Allow' -and $rule.IdentityReference.Value -notin @($sid.Value,'S-1-5-18')){throw 'PREVIEW_DIRECTORY_NOT_PRIVATE'}
    }
}

function Resolve-PreviewToolPath {
    param([Parameter(Mandatory)][string]$Path)
    if(![IO.Path]::IsPathRooted($Path) -or !(Test-Path -LiteralPath $Path -PathType Leaf)){throw 'PREVIEW_TOOL_PATH_INVALID'}
    $resolved=[Pingche.Preview.Native]::FinalToolPath($Path)
    Assert-PreviewPath $resolved
    return $resolved
}

function Get-PreviewBaseEnvironment {
    param([string[]]$ToolDirectories=@())
    # A typed dictionary keeps PowerShell wrappers out of the native environment block.
    $values=[Collections.Generic.Dictionary[string,string]]::new([StringComparer]::OrdinalIgnoreCase)
    foreach($name in @('SystemRoot','SYSTEMROOT','WINDIR','COMSPEC','PATHEXT','TEMP','TMP','USERPROFILE','APPDATA','LOCALAPPDATA',
        'HOMEDRIVE','HOMEPATH','USERNAME','USERDOMAIN','ProgramFiles','ProgramFiles(x86)','ProgramW6432','PROCESSOR_ARCHITECTURE','LANG','LC_ALL')) {
        $value=[Environment]::GetEnvironmentVariable($name,'Process');if($null -ne $value){$values[$name]=$value}
    }
    $values['PATH']=((@($ToolDirectories) + @((Join-Path $env:SystemRoot 'System32'),$env:SystemRoot) | Select-Object -Unique) -join ';')
    return $values
}

function Write-PreviewState {
    param([string]$RunDirectory,[System.Collections.IDictionary]$State,[string]$Event)
    $State['UpdatedUtc']=[DateTime]::UtcNow.ToString('o')
    $status=Join-Path $RunDirectory 'status.json';Assert-PreviewPath $status
    $temporary=Join-Path $RunDirectory ('status-'+[Guid]::NewGuid().ToString('N')+'.tmp')
    [IO.File]::WriteAllText($temporary,($State|ConvertTo-Json -Depth 8),[Text.UTF8Encoding]::new($false))
    Move-Item -LiteralPath $temporary -Destination $status -Force
    $entry=@{TimeUtc=$State.UpdatedUtc;Event=$Event;State=$State.Phase;Supervisor=$State.Supervisor;Backend=$State.Backend;Frontend=$State.Frontend;ErrorCode=$State.ErrorCode}
    [IO.File]::AppendAllText((Join-Path $RunDirectory 'events.jsonl'),(($entry|ConvertTo-Json -Depth 7 -Compress)+[Environment]::NewLine),[Text.UTF8Encoding]::new($false))
}

function Get-PreviewListener {
    param([int]$Port,$OwnedJob)
    $listeners=@(Get-NetTCPConnection -State Listen -ErrorAction Stop | Where-Object LocalPort -eq $Port)
    if($listeners.Count -eq 0){return $null}
    $identities=@()
    foreach($listener in $listeners) {
        if($listener.LocalAddress -ne '127.0.0.1'){throw 'PREVIEW_LISTENER_NOT_LOOPBACK'}
        $identity=$OwnedJob.ObserveListener([int]$listener.OwningProcess)
        try {$identities += @{Pid=$identity.Id;CreatedUtc=$identity.CreatedUtc.ToString('o')}} finally {$identity.Dispose()}
    }
    if(@($identities|ForEach-Object{$_.Pid}|Select-Object -Unique).Count -ne 1){throw 'PREVIEW_PORT_CONFLICT'}
    return $identities[0]
}

function Request-PreviewStop {
    param([Parameter(Mandatory)][string]$RunDirectory)
    Set-PreviewPrivateDirectory $RunDirectory
    $path=Join-Path $RunDirectory 'status.json';Assert-PreviewPath $path
    $state=Get-Content -LiteralPath $path -Raw|ConvertFrom-Json
    if($state.RunId -ne (Split-Path -Leaf $RunDirectory)){throw 'PREVIEW_RUN_ID_INVALID'}
    if($state.Phase -in @('EXITED','FAILED')){return @{StatusPath=$path;State=$state.Phase}}
    $process=[Diagnostics.Process]::GetProcessById([int]$state.Supervisor.Pid)
    try {
        if($process.StartTime.ToUniversalTime().Ticks -ne ([DateTime]$state.Supervisor.CreatedUtc).ToUniversalTime().Ticks){throw 'PREVIEW_SUPERVISOR_IDENTITY_CHANGED'}
        $request=Join-Path $RunDirectory 'stop.request';Assert-PreviewPath $request
        [IO.File]::WriteAllText($request,[string]$state.RunId,[Text.UTF8Encoding]::new($false))
    } finally {$process.Dispose()}
    return @{StatusPath=$path;State='STOP_REQUESTED'}
}

function New-PreviewBatchArguments {
    param([string]$Batch,[string[]]$Arguments)
    $parts=@($Batch)+$Arguments
    foreach($part in $parts){if($part -match '[\r\n"%!\x00]'){throw 'PREVIEW_BATCH_ARGUMENT_INVALID'}}
    return '/d /v:off /s /c "'+(($parts|ForEach-Object{'"'+$_+'"'}) -join ' ')+'"'
}

function Get-PreviewFailureCode {
    param([Exception]$Exception)
    for($level=0;$level -lt 6 -and $null -ne $Exception;$level++) {
        if($Exception.Message -match '^PREVIEW_[A-Z0-9_]{1,80}$'){return $Exception.Message}
        $Exception=$Exception.InnerException
    }
    return 'PREVIEW_WORKER_FAILED'
}

Export-ModuleMember -Function Start-PreviewDetachedWorker,Assert-PreviewWorkerIdentity,Assert-PreviewPath,Resolve-PreviewToolPath,Set-PreviewPrivateDirectory,Get-PreviewBaseEnvironment,Write-PreviewState,Get-PreviewListener,Request-PreviewStop,New-PreviewBatchArguments,Get-PreviewFailureCode
