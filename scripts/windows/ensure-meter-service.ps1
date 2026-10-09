#requires -Version 5.1
[CmdletBinding()]
param(
    [string]$ServerUrl = '',
    [switch]$InstallIfMissing,
    [string]$ExpectedUserSid = ''
)

$ErrorActionPreference = 'Stop'

function Normalize-MeterServerUrl([string]$Url) {
    $parsed = $null
    if (-not [Uri]::TryCreate($Url.Trim(), [UriKind]::Absolute, [ref]$parsed) -or
        $parsed.Scheme -notin @('http', 'https') -or -not $parsed.Host -or
        $parsed.AbsolutePath -ne '/' -or $parsed.UserInfo -or $parsed.Query -or $parsed.Fragment) {
        throw 'ServerUrl must be an HTTP or HTTPS origin.'
    }
    if ($parsed.Host -eq '0.0.0.0') {
        $builder = [UriBuilder]::new($parsed)
        $builder.Host = '127.0.0.1'
        $parsed = $builder.Uri
    }
    return $parsed.GetLeftPart([UriPartial]::Authority)
}

function Get-MeterStartupAction([string]$State, [string]$InstalledUrl, [string]$SelectedUrl,
        [string]$InstalledHash = '', [string]$BundledHash = '') {
    if (-not $State) { return 'INSTALL' }
    if ($BundledHash -and $InstalledHash -ne $BundledHash) { return 'UPGRADE' }
    if ($InstalledUrl -ne $SelectedUrl) { return 'CONFIGURE' }
    if ($State -eq 'Running') { return 'READY' }
    return 'START'
}

function Set-MeterServerInConfig($Configuration, [string]$Url) {
    if (-not $Configuration.serverUrl -or -not $Configuration.statusFile) {
        throw 'The installed meter configuration is incomplete.'
    }
    # Preserve the Windows profile, device identity and accounting state paths.
    $Configuration.serverUrl = Normalize-MeterServerUrl $Url
    return $Configuration
}

# Dot-sourcing exposes the pure startup policy to tests without changing services.
if ($MyInvocation.InvocationName -eq '.') { return }

trap {
    $diagnosticsDirectory = Join-Path $env:APPDATA 'com.codexquotaguard.desktop'
    try {
        New-Item -ItemType Directory -Path $diagnosticsDirectory -Force | Out-Null
        [IO.File]::WriteAllText((Join-Path $diagnosticsDirectory 'meter-setup-error.txt'),
            $_.Exception.Message, [Text.UTF8Encoding]::new($false))
    } catch { }
    Write-Host 'Meter service setup failed. See meter-setup-error.txt in the app data directory.'
    exit 1
}

$serviceName = 'CodexQuotaGuardMeter'
$ServerUrl = Normalize-MeterServerUrl $ServerUrl
$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
$currentSid = $identity.User.Value
if ($ExpectedUserSid -and $ExpectedUserSid -ne $currentSid) {
    throw 'Run the installer with the same Windows user, not a different administrator account.'
}
if (-not $ExpectedUserSid) { $ExpectedUserSid = $currentSid }
$principal = [Security.Principal.WindowsPrincipal]::new($identity)
$isAdmin = $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
$appData = Join-Path $env:APPDATA 'com.codexquotaguard.desktop'
$connectionFile = Join-Path $appData 'meter-connection.json'
$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$bundledBinary = Join-Path $PSScriptRoot 'quota-guard-meter.exe'
if (-not (Test-Path -LiteralPath $bundledBinary -PathType Leaf)) {
    $bundledBinary = Join-Path $repositoryRoot 'apps\client\frontend\src-tauri\resources\meter\quota-guard-meter.exe'
}
$bundledHash = if (Test-Path -LiteralPath $bundledBinary -PathType Leaf) {
    (Get-FileHash -LiteralPath $bundledBinary -Algorithm SHA256).Hash
} else { '' }
$installedUrl = ''
$installedHash = ''
if (Test-Path -LiteralPath $connectionFile -PathType Leaf) {
    try {
        if ((Get-Item -LiteralPath $connectionFile).Length -le 4096) {
            $connection = Get-Content -LiteralPath $connectionFile -Raw | ConvertFrom-Json
            $installedUrl = Normalize-MeterServerUrl ([string]$connection.serverUrl)
            $installedHash = [string]$connection.binaryHash
        }
    } catch { $installedUrl = '' }
}
$service = Get-Service -Name $serviceName -ErrorAction SilentlyContinue
$state = if ($service) { [string]$service.Status } else { '' }
$action = Get-MeterStartupAction $state $installedUrl $ServerUrl $installedHash $bundledHash
if ($action -eq 'READY') {
    Write-Host "Meter service is running: $serviceName ($ServerUrl)"
    return
}
if ($action -eq 'INSTALL' -and -not $InstallIfMissing) {
    throw 'Meter service is not installed. Run start-client.ps1 and accept the installation prompt.'
}

if (-not $isAdmin) {
    Write-Host 'Windows administrator approval is required to install, update, start, or configure the meter service.'
    $powershell = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
    $arguments = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', "`"$PSCommandPath`"",
        '-ServerUrl', "`"$ServerUrl`"", '-ExpectedUserSid', $ExpectedUserSid)
    if ($InstallIfMissing) { $arguments += '-InstallIfMissing' }
    try {
        $helper = Start-Process -FilePath $powershell -ArgumentList $arguments -Verb RunAs `
            -WindowStyle Hidden -Wait -PassThru
    } catch {
        throw 'Windows administrator approval was not completed; the meter service was not prepared.'
    }
    if ($helper.ExitCode -ne 0) {
        throw 'Meter service setup failed. Run ensure-meter-service.ps1 in an Administrator PowerShell for details.'
    }
    $service = Get-Service -Name $serviceName -ErrorAction Stop
    if ($service.Status -ne 'Running') { throw 'Meter service did not reach Running state.' }
    $connection = Get-Content -LiteralPath $connectionFile -Raw | ConvertFrom-Json
    if ((Normalize-MeterServerUrl ([string]$connection.serverUrl)) -ne $ServerUrl) {
        throw 'The meter service and desktop server addresses do not match.'
    }
    if ($bundledHash -and [string]$connection.binaryHash -ne $bundledHash) {
        throw 'The meter service was not updated to the bundled executable.'
    }
    Write-Host "Meter service is running: $serviceName ($ServerUrl)"
    return
}

if ($action -eq 'INSTALL') {
    $installer = Join-Path $PSScriptRoot 'install-meter-service.ps1'
    if (Test-Path -LiteralPath $bundledBinary -PathType Leaf) {
        & $installer -ServerUrl $ServerUrl -BinaryPath $bundledBinary
    } else {
        & $installer -ServerUrl $ServerUrl
    }
} else {
    $registered = Get-CimInstance Win32_Service -Filter "Name='$serviceName'"
    if ($registered.PathName -notmatch '^\s*"?(.+?quota-guard-meter\.exe)"?\s+service\s+--config\s+(?:"([^"]+)"|(.+?))\s*$') {
        throw 'The installed meter service has an unexpected command line.'
    }
    $binaryPath = [IO.Path]::GetFullPath($Matches[1])
    $configPath = [IO.Path]::GetFullPath($(if ($Matches[2]) { $Matches[2] } else { $Matches[3] }))
    if ([IO.Path]::GetFileName($configPath) -ne 'meter-config.json' -or
        [IO.Path]::GetDirectoryName($configPath) -ne [IO.Path]::GetDirectoryName($binaryPath) -or
        (Get-Item -LiteralPath $configPath).Length -gt 8192) {
        throw 'The installed meter configuration path is invalid.'
    }
    $config = Get-Content -LiteralPath $configPath -Raw | ConvertFrom-Json
    $config = Set-MeterServerInConfig $config $ServerUrl
    $statusDirectory = [IO.Path]::GetDirectoryName([string]$config.statusFile)
    if ($statusDirectory -ne [IO.Path]::GetFullPath($appData)) {
        throw 'This meter service belongs to a different Windows profile.'
    }
    if ($action -eq 'UPGRADE') {
        & (Join-Path $PSScriptRoot 'install-meter-service.ps1') -ServerUrl $ServerUrl -BinaryPath $bundledBinary
        Write-Host 'Meter service executable updated.'
    } else {
        $oldConfig = Get-Content -LiteralPath $configPath -Raw | ConvertFrom-Json
        if ((Normalize-MeterServerUrl ([string]$oldConfig.serverUrl)) -ne $ServerUrl) {
            if ($service.Status -ne 'Stopped') {
                Stop-Service -Name $serviceName -ErrorAction Stop
                $service.WaitForStatus('Stopped', [TimeSpan]::FromSeconds(20))
            }
            try {
                [IO.File]::WriteAllText($configPath, ($config | ConvertTo-Json -Compress),
                    [Text.UTF8Encoding]::new($false))
            } catch {
                Start-Service -Name $serviceName -ErrorAction SilentlyContinue
                throw
            }
            Write-Host "Meter server address updated: $ServerUrl"
        }
    }
    $service.Refresh()
    if ($service.Status -eq 'StopPending') {
        $service.WaitForStatus('Stopped', [TimeSpan]::FromSeconds(20))
    }
    $service.Refresh()
    if ($service.Status -eq 'Stopped') { Start-Service -Name $serviceName -ErrorAction Stop }
    $service.WaitForStatus('Running', [TimeSpan]::FromSeconds(20))
}

New-Item -ItemType Directory -Path $appData -Force | Out-Null
[IO.File]::WriteAllText($connectionFile,
    (@{serverUrl = $ServerUrl; binaryHash = $bundledHash} | ConvertTo-Json -Compress),
    [Text.UTF8Encoding]::new($false))
Write-Host "Meter service is running: $serviceName ($ServerUrl)"
