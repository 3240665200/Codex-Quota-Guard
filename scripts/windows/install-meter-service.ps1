#requires -Version 5.1
[CmdletBinding()]
param(
    [string]$ServerUrl = 'http://127.0.0.1:8080',
    [string]$InstallDirectory = '',
    [string]$BinaryPath = '',
    [string]$ExpectedUserSid = ''
)

$ErrorActionPreference = 'Stop'

function Test-MeterBinary([string]$Path) {
    $startInfo = [Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = $Path
    $startInfo.Arguments = '--version'
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $probe = [Diagnostics.Process]::Start($startInfo)
    try {
        if (-not $probe.WaitForExit(10000)) {
            $probe.Kill()
            $probe.WaitForExit()
            throw 'The bundled meter executable did not finish its startup check.'
        }
        if ($probe.ExitCode -ne 0) {
            throw 'The bundled meter executable cannot run on this computer.'
        }
        $output = $probe.StandardOutput.ReadToEnd().Trim()
        if ($output -notmatch '^quota-guard-meter (\d+\.\d+\.\d+(?:[-+][0-9A-Za-z.-]+)?)$') {
            throw 'The bundled meter executable did not report a valid version.'
        }
        return $Matches[1]
    } finally {
        $probe.Dispose()
    }
}

function Write-MeterSetupStatus([string]$State, [string]$Stage, [string]$Reason = '') {
    $directory = Join-Path $env:APPDATA 'com.codexquotaguard.desktop'
    New-Item -ItemType Directory -Path $directory -Force | Out-Null
    $summary = @{state = $State; stage = $Stage; reason = $Reason; at = [DateTimeOffset]::UtcNow.ToString('o')}
    [IO.File]::WriteAllText((Join-Path $directory 'meter-setup-status.json'),
        ($summary | ConvertTo-Json -Compress), [Text.UTF8Encoding]::new($false))
}

function Resolve-MeterServerUri([string]$Url) {
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
    return $parsed
}

function Resolve-MeterInstallDirectory([string]$Requested) {
    if (-not $Requested) {
        $programFilesRoot = [Environment]::GetFolderPath([Environment+SpecialFolder]::ProgramFiles)
        if (-not $programFilesRoot) { $programFilesRoot = $env:ProgramFiles }
        if (-not $programFilesRoot) { throw 'Windows Program Files directory is unavailable.' }
        $Requested = Join-Path $programFilesRoot 'CodexQuotaGuard\Meter'
    }
    $resolved = [IO.Path]::GetFullPath($Requested)
    if ($resolved -eq [IO.Path]::GetPathRoot($resolved)) {
        throw 'A drive root cannot be used as a service directory.'
    }
    if (-not (Test-Path -LiteralPath ([IO.Path]::GetPathRoot($resolved)) -PathType Container -ErrorAction SilentlyContinue)) {
        throw 'The selected install drive is unavailable.'
    }
    return $resolved
}

function Resolve-MeterBundledBinary([string]$Requested, [string]$ScriptDirectory) {
    if ($Requested) {
        $resolved = [IO.Path]::GetFullPath($Requested)
        if (-not (Test-Path -LiteralPath $resolved -PathType Leaf)) {
            throw 'The bundled meter executable was not found.'
        }
        return $resolved
    }
    $adjacent = Join-Path $ScriptDirectory 'quota-guard-meter.exe'
    if (Test-Path -LiteralPath $adjacent -PathType Leaf) { return [IO.Path]::GetFullPath($adjacent) }
    return $null
}

function Get-MeterExistingConfiguration([string]$ImagePath, [string]$AppData) {
    if ($ImagePath -notmatch '^\s*"?(.+?quota-guard-meter\.exe)"?\s+service\s+--config\s+(?:"([^"]+)"|(.+?))\s*$') {
        throw 'The existing meter service has an unexpected command line.'
    }
    $binaryPath = [IO.Path]::GetFullPath($Matches[1])
    $configPath = [IO.Path]::GetFullPath($(if ($Matches[2]) { $Matches[2] } else { $Matches[3] }))
    if ([IO.Path]::GetFileName($configPath) -ne 'meter-config.json' -or
        [IO.Path]::GetDirectoryName($configPath) -ne [IO.Path]::GetDirectoryName($binaryPath) -or
        -not (Test-Path -LiteralPath $configPath -PathType Leaf) -or
        (Get-Item -LiteralPath $configPath).Length -gt 8192) {
        throw 'The existing meter configuration path is invalid.'
    }
    $configuration = Get-Content -LiteralPath $configPath -Raw | ConvertFrom-Json
    foreach ($field in @('codexHome', 'pairingInbox', 'stateDir', 'statusFile', 'deviceId')) {
        if (-not $configuration.$field) { throw 'The existing meter configuration is incomplete.' }
    }
    $deviceId = [Guid]::Parse([string]$configuration.deviceId)
    $statusDirectory = [IO.Path]::GetFullPath([IO.Path]::GetDirectoryName([string]$configuration.statusFile))
    if (-not $statusDirectory.Equals([IO.Path]::GetFullPath($AppData), [StringComparison]::OrdinalIgnoreCase)) {
        throw 'This meter service belongs to another Windows profile.'
    }
    return @{ config = $configuration; deviceId = $deviceId; binaryPath = $binaryPath; configPath = $configPath }
}

function Assert-MeterExpectedUser([string]$ActualSid, [string]$ExpectedSid) {
    if ($ExpectedSid -and $ActualSid -ne $ExpectedSid) {
        throw 'Run the installer with the same Windows user, not a different administrator account.'
    }
}

function Resolve-MeterOwnerSid([string]$CurrentSid, [string]$ExistingSid) {
    if ($CurrentSid -notmatch '^S-1-(?:\d+-)+\d+$') {
        throw 'The installing Windows user SID is invalid.'
    }
    if ($ExistingSid -and $ExistingSid -ne $CurrentSid) {
        throw 'The existing meter service belongs to another Windows user.'
    }
    return $CurrentSid
}

# Loading these helpers for regression checks never touches a service or config.
if ($MyInvocation.InvocationName -eq '.') { return }

$setupStage = 'ADMIN_CHECK'
$setupReason = ''
$rollbackNeeded = $false
$stagedBinary = ''
$backupBinary = ''
$backupConfig = ''
$connectionBackup = ''
$connectionExisted = $false
trap {
    if ($rollbackNeeded -and $service) {
        try {
            $current = Get-Service -Name $serviceName -ErrorAction Stop
            if ($current.Status -ne 'Stopped') {
                Stop-Service -Name $serviceName -ErrorAction Stop
                $current.WaitForStatus('Stopped', [TimeSpan]::FromSeconds(20))
            }
            Copy-Item -LiteralPath $backupBinary -Destination $installedBinary -Force
            Copy-Item -LiteralPath $backupConfig -Destination $configPath -Force
            if ($connectionExisted) {
                Copy-Item -LiteralPath $connectionBackup `
                    -Destination (Join-Path $appData 'meter-connection.json') -Force
            } else {
                Remove-Item -LiteralPath (Join-Path $appData 'meter-connection.json') `
                    -Force -ErrorAction SilentlyContinue
            }
            $previous = Get-CimInstance Win32_Service -Filter "Name='$serviceName'"
            $restored = Invoke-CimMethod -InputObject $previous -MethodName Change `
                -Arguments @{ PathName = $registered.PathName; StartMode = 'Automatic' }
            if ($restored.ReturnValue -ne 0) { throw 'Could not restore the previous service registration.' }
            if ($wasRunning) { Start-Service -Name $serviceName -ErrorAction Stop }
            $rollbackNeeded = $false
        } catch {
            $setupReason = 'ROLLBACK_FAILED'
        }
    }
    if ($stagedBinary) { Remove-Item -LiteralPath $stagedBinary -Force -ErrorAction SilentlyContinue }
    if (-not $rollbackNeeded) {
        foreach ($backup in @($backupBinary, $backupConfig, $connectionBackup)) {
            if ($backup) { Remove-Item -LiteralPath $backup -Force -ErrorAction SilentlyContinue }
        }
    }
    try { Write-MeterSetupStatus 'FAILED' $setupStage $setupReason } catch { }
    Write-Host "Meter setup failed at $setupStage ($setupReason). See meter-setup-status.json in the app data directory."
    exit 1
}
Write-MeterSetupStatus 'RUNNING' $setupStage
$serviceName = 'CodexQuotaGuardMeter'
$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
try { Assert-MeterExpectedUser $identity.User.Value $ExpectedUserSid }
catch {
    $setupReason = 'WINDOWS_PROFILE_MISMATCH'
    throw
}
$principal = [Security.Principal.WindowsPrincipal]::new($identity)
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw 'Run this script from an elevated Administrator PowerShell.'
}

$setupStage = 'SERVER_URL_VALIDATE'
$setupReason = 'SERVER_URL_INVALID'
$uri = Resolve-MeterServerUri $ServerUrl

$setupStage = 'INSTALL_DIRECTORY'
$setupReason = 'INSTALL_DIRECTORY_UNAVAILABLE'
$install = Resolve-MeterInstallDirectory $InstallDirectory

$setupStage = 'SOURCE_BINARY'
$setupReason = 'BUNDLED_BINARY_MISSING'
$sourceBinary = Resolve-MeterBundledBinary $BinaryPath $PSScriptRoot
if (-not $sourceBinary) {
    throw 'No bundled meter executable was found. Source-tree operators must build it first and pass -BinaryPath.'
}
$sourceBinary = [IO.Path]::GetFullPath($sourceBinary)
if (-not (Test-Path -LiteralPath $sourceBinary -PathType Leaf)) {
    throw 'The Rust meter executable was not found.'
}

$setupStage = 'BINARY_CHECK'
$setupReason = ''
$binaryVersion = Test-MeterBinary $sourceBinary

$setupStage = 'PREPARE_FILES'
$appData = Join-Path $env:APPDATA 'com.codexquotaguard.desktop'
$deviceIdFile = Join-Path $appData 'meter-device-id.txt'
$pairingInbox = Join-Path $appData 'meter-pairing.json'
$statusFile = Join-Path $appData 'meter-status.json'
$userHome = [IO.Path]::GetFullPath($env:USERPROFILE)
$codexHome = Join-Path $userHome '.codex'
$stateDirectory = Join-Path $env:ProgramData 'CodexQuotaGuard\Meter'
$installedBinary = Join-Path $install 'quota-guard-meter.exe'
$configPath = Join-Path $install 'meter-config.json'
$service = Get-Service -Name $serviceName -ErrorAction SilentlyContinue
$existingConfiguration = $null
if ($service) {
    $setupStage = 'EXISTING_SERVICE_CHECK'
    $setupReason = 'EXISTING_CONFIGURATION_INVALID'
    $registered = Get-CimInstance Win32_Service -Filter "Name='$serviceName'"
    $existing = Get-MeterExistingConfiguration $registered.PathName $appData
    $existingConfiguration = $existing.config
    $ownerUserSid = Resolve-MeterOwnerSid $identity.User.Value ([string]$existingConfiguration.ownerUserSid)
    if ($InstallDirectory -and
        -not $install.Equals([IO.Path]::GetDirectoryName($existing.binaryPath),
            [StringComparison]::OrdinalIgnoreCase)) {
        throw 'An existing meter service cannot be moved to a different install directory during upgrade.'
    }
    if (-not $InstallDirectory) {
        # Keep the existing service location; migrating files is a separate operation.
        $install = [IO.Path]::GetDirectoryName($existing.binaryPath)
        $installedBinary = $existing.binaryPath
        $configPath = $existing.configPath
    }
    $setupReason = 'WINDOWS_PROFILE_MISMATCH'
    if ((Test-Path -LiteralPath $deviceIdFile -PathType Leaf) -and
        [Guid]::Parse((Get-Content -LiteralPath $deviceIdFile -Raw).Trim()) -ne $existing.deviceId) {
        throw 'This meter service belongs to another Windows profile or device.'
    }
}
$setupStage = 'PREPARE_FILES'
$setupReason = ''
New-Item -ItemType Directory -Path $appData, $stateDirectory, $install -Force | Out-Null
if (Test-Path -LiteralPath $deviceIdFile -PathType Leaf) {
    $deviceId = [Guid]::Parse((Get-Content -LiteralPath $deviceIdFile -Raw).Trim())
} elseif ($service) {
    $deviceId = $existing.deviceId
    [IO.File]::WriteAllText($deviceIdFile, $deviceId.ToString(), [Text.UTF8Encoding]::new($false))
} else {
    $deviceId = [Guid]::NewGuid()
    [IO.File]::WriteAllText($deviceIdFile, $deviceId.ToString(), [Text.UTF8Encoding]::new($false))
}
$configuration = [ordered]@{
    serverUrl = $uri.GetLeftPart([UriPartial]::Authority)
    codexHome = $codexHome
    pairingInbox = $pairingInbox
    stateDir = $stateDirectory
    statusFile = $statusFile
    deviceId = $deviceId.ToString()
    ownerUserSid = Resolve-MeterOwnerSid $identity.User.Value ''
}
if ($existingConfiguration) {
    # Repair executable/registration while preserving attribution and cursors.
    foreach ($field in @('codexHome', 'pairingInbox', 'stateDir', 'statusFile', 'deviceId')) {
        $configuration[$field] = $existingConfiguration.$field
    }
    $stateDirectory = [string]$configuration.stateDir
    $configuration.ownerUserSid = $ownerUserSid
}
New-Item -ItemType Directory -Path $stateDirectory -Force | Out-Null

# Stage and verify the replacement before stopping the existing service.
$setupStage = 'STAGE_BINARY'
$stagedBinary = Join-Path $install "quota-guard-meter.$([Guid]::NewGuid().ToString('N')).new"
Copy-Item -LiteralPath $sourceBinary -Destination $stagedBinary -Force
if ((Get-FileHash -LiteralPath $stagedBinary -Algorithm SHA256).Hash -ne
    (Get-FileHash -LiteralPath $sourceBinary -Algorithm SHA256).Hash) {
    throw 'The staged meter executable does not match the bundled executable.'
}
if ($service) {
    $backupBinary = Join-Path $install "quota-guard-meter.$([Guid]::NewGuid().ToString('N')).bak"
    $backupConfig = Join-Path $install "meter-config.$([Guid]::NewGuid().ToString('N')).bak"
    Copy-Item -LiteralPath $installedBinary -Destination $backupBinary -Force
    Copy-Item -LiteralPath $configPath -Destination $backupConfig -Force
    $connectionFile = Join-Path $appData 'meter-connection.json'
    if (Test-Path -LiteralPath $connectionFile -PathType Leaf) {
        $connectionBackup = Join-Path $appData "meter-connection.$([Guid]::NewGuid().ToString('N')).bak"
        Copy-Item -LiteralPath $connectionFile -Destination $connectionBackup -Force
        $connectionExisted = $true
    }
}
$wasRunning = $service -and $service.Status -ne 'Stopped'
if ($service) { $rollbackNeeded = $true }
if ($wasRunning) {
    $setupStage = 'SERVICE_STOP'
    Stop-Service -Name $serviceName -ErrorAction Stop
    $service.WaitForStatus('Stopped', [TimeSpan]::FromSeconds(20))
}
$setupStage = 'REPLACE_BINARY'
Copy-Item -LiteralPath $stagedBinary -Destination $installedBinary -Force
[IO.File]::WriteAllText($configPath, ($configuration | ConvertTo-Json -Compress),
    [Text.UTF8Encoding]::new($false))

# The device reporting token is kept under ProgramData and is not part of the UI.
$setupStage = 'PROTECT_FILES'
& icacls.exe $stateDirectory /inheritance:r /grant:r '*S-1-5-18:(OI)(CI)F' '*S-1-5-32-544:(OI)(CI)F' | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Could not protect the meter state directory.' }
& icacls.exe $install /inheritance:r /grant:r '*S-1-5-18:(OI)(CI)F' '*S-1-5-32-544:(OI)(CI)F' | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Could not protect the meter installation directory.' }

$imagePath = '"' + $installedBinary + '" service --config "' + $configPath + '"'
$setupStage = 'SERVICE_REGISTER'
if ($null -eq $service) {
    New-Service -Name $serviceName -BinaryPathName $imagePath -DisplayName 'Codex Quota Guard Meter' `
        -Description 'Reports local Codex token usage for one Windows profile.' `
        -StartupType Automatic | Out-Null
} else {
    # CIM passes the quoted image path as one value; Windows PowerShell's
    # legacy native argument quoting can strip quotes around Program Files.
    $changed = Invoke-CimMethod -InputObject $registered -MethodName Change `
        -Arguments @{ PathName = $imagePath; StartMode = 'Automatic' }
    if ($changed.ReturnValue -ne 0) { throw 'Could not update the existing meter service.' }
}
$setupStage = 'SERVICE_RECOVERY'
& sc.exe failure $serviceName reset= 86400 actions= restart/5000/restart/5000/restart/30000 | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Could not configure service recovery.' }
$setupStage = 'SERVICE_START'
Start-Service -Name $serviceName -ErrorAction Stop
$running = Get-Service -Name $serviceName
$running.WaitForStatus('Running', [TimeSpan]::FromSeconds(20))
$setupStage = 'VERIFY_INSTALL'
$setupReason = 'SERVICE_VERIFICATION_FAILED'
$registeredAfterInstall = Get-CimInstance Win32_Service -Filter "Name='$serviceName'"
$verifiedConfiguration = Get-MeterExistingConfiguration $registeredAfterInstall.PathName $appData
if (-not $verifiedConfiguration.binaryPath.Equals($installedBinary, [StringComparison]::OrdinalIgnoreCase) -or
    -not $verifiedConfiguration.configPath.Equals($configPath, [StringComparison]::OrdinalIgnoreCase) -or
    [string]$verifiedConfiguration.config.deviceId -ne $deviceId.ToString() -or
    (Resolve-MeterServerUri ([string]$verifiedConfiguration.config.serverUrl)).GetLeftPart([UriPartial]::Authority) -ne
        $uri.GetLeftPart([UriPartial]::Authority)) {
    throw 'The registered meter service does not match the configuration just installed.'
}
$actualBinaryHash = (Get-FileHash -LiteralPath $installedBinary -Algorithm SHA256).Hash
$sourceBinaryHash = (Get-FileHash -LiteralPath $sourceBinary -Algorithm SHA256).Hash
if ($actualBinaryHash -ne $sourceBinaryHash -or (Test-MeterBinary $installedBinary) -ne $binaryVersion) {
    throw 'The installed meter executable does not match the bundled version.'
}
[IO.File]::WriteAllText((Join-Path $appData 'meter-connection.json'),
    (@{serverUrl = $configuration.serverUrl;
        binaryHash = $actualBinaryHash;
        binaryVersion = $binaryVersion} | ConvertTo-Json -Compress),
    [Text.UTF8Encoding]::new($false))
$connectionRecord = Get-Content -LiteralPath (Join-Path $appData 'meter-connection.json') -Raw |
    ConvertFrom-Json
if ((Resolve-MeterServerUri ([string]$connectionRecord.serverUrl)).GetLeftPart([UriPartial]::Authority) -ne $configuration.serverUrl -or
    [string]$connectionRecord.binaryHash -ne $actualBinaryHash -or
    [string]$connectionRecord.binaryVersion -ne $binaryVersion) {
    throw 'The meter connection record does not match the installed service.'
}
$setupStage = 'SERVICE_START'
$setupReason = ''
Write-MeterSetupStatus 'SUCCEEDED' 'READY'
$rollbackNeeded = $false
Remove-Item -LiteralPath $stagedBinary -Force -ErrorAction SilentlyContinue
foreach ($backup in @($backupBinary, $backupConfig, $connectionBackup)) {
    if ($backup) { Remove-Item -LiteralPath $backup -Force -ErrorAction SilentlyContinue }
}
Write-Host "Rust meter service is running: $serviceName"
Write-Host "Device ID: $deviceId"
Write-Host "Private activity log: $(Join-Path $stateDirectory 'logs\meter.jsonl')"
Write-Host 'Reopen Quota Guard and sign in to pair this service. No computer restart was performed.'
