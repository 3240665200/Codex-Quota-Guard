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

function Get-MeterEvidenceCommonApplicationData {
    # Environment variables are writable by the launching user, including during UAC.
    $directory = [Environment]::GetFolderPath([Environment+SpecialFolder]::CommonApplicationData)
    if (-not $directory -or $directory -notmatch '^[A-Za-z]:\\') {
        throw 'Windows CommonApplicationData must be an available local directory.'
    }
    return [IO.Path]::GetFullPath($directory).TrimEnd([char]'\')
}

function Get-MeterConflictEvidencePath([string]$UserSid) {
    if (-not $UserSid -or $UserSid -notmatch '^S-1-(?:\d+-)+\d+$') {
        throw 'The conflict evidence owner SID is invalid.'
    }
    $parsedSid = [Security.Principal.SecurityIdentifier]::new($UserSid)
    if ($parsedSid.Value -cne $UserSid) { throw 'The conflict evidence owner SID is not canonical.' }
    $directory = Join-Path (Get-MeterEvidenceCommonApplicationData) 'CodexQuotaGuardConflictEvidence'
    return Join-Path (Join-Path $directory $UserSid) 'ccswitch-conflict-evidence.json'
}

function Assert-MeterEvidenceDirectorySecurity([string]$Sddl) {
    $security = [Security.AccessControl.RawSecurityDescriptor]::new($Sddl)
    $trusted = @('S-1-5-18', 'S-1-5-32-544')
    if (-not $security.Owner -or $security.Owner.Value -notin $trusted -or
        -not ([int]$security.ControlFlags -band 0x1000) -or
        $null -eq $security.DiscretionaryAcl -or $security.DiscretionaryAcl.Count -ne 2) {
        throw 'The conflict evidence directory has an untrusted owner or ACL.'
    }
    $seen = @{}
    foreach ($ace in $security.DiscretionaryAcl) {
        if ($ace -isnot [Security.AccessControl.CommonAce] -or $ace.IsCallback -or
            $ace.AceQualifier -ne [Security.AccessControl.AceQualifier]::AccessAllowed -or
            [int]$ace.AceFlags -ne 3 -or $ace.AccessMask -ne 0x1f01ff -or
            $ace.SecurityIdentifier.Value -notin $trusted -or $seen.ContainsKey($ace.SecurityIdentifier.Value)) {
            throw 'The conflict evidence directory ACL must grant only SYSTEM and Administrators full control.'
        }
        $seen[$ace.SecurityIdentifier.Value] = $true
    }
}

function Assert-MeterEvidenceAncestorSecurity([string]$Sddl) {
    $security = [Security.AccessControl.RawSecurityDescriptor]::new($Sddl)
    $trusted = @('S-1-5-18', 'S-1-5-32-544',
        'S-1-5-80-956008885-3418522649-1831038044-1853292631-2271478464')
    if (-not $security.Owner -or $security.Owner.Value -notin $trusted -or $null -eq $security.DiscretionaryAcl) {
        throw 'The conflict evidence ancestor has an untrusted owner or ACL.'
    }
    foreach ($ace in $security.DiscretionaryAcl) {
        if ($ace -isnot [Security.AccessControl.CommonAce] -or $ace.IsCallback) {
            throw 'The conflict evidence ancestor has an unsupported ACL.'
        }
        if ($ace.AceQualifier -eq [Security.AccessControl.AceQualifier]::AccessAllowed -and
            -not ([int]$ace.AceFlags -band 8) -and
            $ace.SecurityIdentifier.Value -notin $trusted -and ($ace.AccessMask -band 0x100d0040)) {
            throw 'The conflict evidence ancestor allows an untrusted principal to replace or change it.'
        }
    }
}

function Initialize-MeterEvidenceNative {
    if ('CodexQuotaGuard.MeterEvidenceNative' -as [type]) { return }
    Add-Type -TypeDefinition @'
using System;
using System.ComponentModel;
using System.Runtime.InteropServices;
using System.Text;
using Microsoft.Win32.SafeHandles;
namespace CodexQuotaGuard {
    public static class MeterEvidenceNative {
        [StructLayout(LayoutKind.Sequential)]
        private struct SecurityAttributes {
            public int Length;
            public IntPtr Descriptor;
            [MarshalAs(UnmanagedType.Bool)] public bool InheritHandle;
        }
        [StructLayout(LayoutKind.Sequential)]
        private struct AttributeTagInfo { public uint Attributes; public uint ReparseTag; }
        [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        private static extern SafeFileHandle CreateFileW(string name, uint access, uint share,
            IntPtr attributes, uint creation, uint flags, IntPtr template);
        [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        private static extern bool CreateDirectoryW(string path, ref SecurityAttributes attributes);
        [DllImport("kernel32.dll", SetLastError = true)]
        private static extern bool GetFileInformationByHandleEx(SafeFileHandle handle, int infoClass,
            out AttributeTagInfo information, uint size);
        [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        private static extern uint GetFinalPathNameByHandleW(SafeFileHandle handle, StringBuilder path,
            uint length, uint flags);
        [DllImport("advapi32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        private static extern bool ConvertStringSecurityDescriptorToSecurityDescriptorW(string sddl,
            uint revision, out IntPtr descriptor, out uint size);
        [DllImport("advapi32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        private static extern bool ConvertSecurityDescriptorToStringSecurityDescriptorW(IntPtr descriptor,
            uint revision, uint information, out IntPtr sddl, out uint size);
        [DllImport("advapi32.dll")]
        private static extern uint GetSecurityInfo(SafeFileHandle handle, int type, uint information,
            out IntPtr owner, out IntPtr group, out IntPtr dacl, out IntPtr sacl, out IntPtr descriptor);
        [DllImport("kernel32.dll")] private static extern IntPtr LocalFree(IntPtr memory);
        public static SafeFileHandle OpenDirectory(string path) {
            // LIST_DIRECTORY makes the pin participate in rename/delete sharing checks.
            // Attribute-only opens bypass sharing; ancestors also retain pinned children.
            SafeFileHandle handle = CreateFileW(path, 0x00020081, 1, IntPtr.Zero, 3,
                0x02200000, IntPtr.Zero); // LIST_DIRECTORY | READ_CONTROL | READ_ATTRIBUTES; OPEN_REPARSE_POINT.
            if (handle.IsInvalid) {
                int error = Marshal.GetLastWin32Error(); handle.Dispose();
                throw new Win32Exception(error, "Could not pin conflict evidence directory: " + path);
            }
            return handle;
        }
        public static uint Attributes(SafeFileHandle handle) {
            AttributeTagInfo information;
            if (!GetFileInformationByHandleEx(handle, 9, out information, 8))
                throw new Win32Exception(Marshal.GetLastWin32Error());
            return information.Attributes;
        }
        public static string FinalPath(SafeFileHandle handle) {
            StringBuilder path = new StringBuilder(512);
            uint length = GetFinalPathNameByHandleW(handle, path, (uint)path.Capacity, 0);
            if (length == 0) throw new Win32Exception(Marshal.GetLastWin32Error());
            if (length >= path.Capacity) {
                path = new StringBuilder((int)length + 1);
                length = GetFinalPathNameByHandleW(handle, path, (uint)path.Capacity, 0);
                if (length == 0 || length >= path.Capacity) throw new Win32Exception(Marshal.GetLastWin32Error());
            }
            string result = path.ToString();
            return result.StartsWith(@"\\?\", StringComparison.Ordinal) ? result.Substring(4) : result;
        }
        public static string SecurityDescriptor(SafeFileHandle handle) {
            IntPtr owner, group, dacl, sacl, descriptor;
            uint error = GetSecurityInfo(handle, 1, 5, out owner, out group, out dacl, out sacl, out descriptor);
            if (error != 0) throw new Win32Exception((int)error);
            try {
                IntPtr sddl; uint size;
                if (!ConvertSecurityDescriptorToStringSecurityDescriptorW(descriptor, 1, 5, out sddl, out size))
                    throw new Win32Exception(Marshal.GetLastWin32Error());
                try { return Marshal.PtrToStringUni(sddl); } finally { LocalFree(sddl); }
            } finally { LocalFree(descriptor); }
        }
        public static void CreateProtectedDirectory(string path) {
            IntPtr descriptor; uint size;
            if (!ConvertStringSecurityDescriptorToSecurityDescriptorW(
                    "O:BAG:BAD:P(A;OICI;FA;;;SY)(A;OICI;FA;;;BA)", 1, out descriptor, out size))
                throw new Win32Exception(Marshal.GetLastWin32Error());
            try {
                SecurityAttributes attributes = new SecurityAttributes();
                attributes.Length = Marshal.SizeOf(typeof(SecurityAttributes));
                attributes.Descriptor = descriptor;
                if (!CreateDirectoryW(path, ref attributes)) {
                    int error = Marshal.GetLastWin32Error();
                    // Existing names are subsequently pinned and validated, never repaired.
                    if (error != 183) throw new Win32Exception(error, "Could not create protected conflict evidence directory.");
                }
            } finally { LocalFree(descriptor); }
        }
    }
}
'@
}

function Open-MeterEvidenceDirectory([string]$Path) {
    Initialize-MeterEvidenceNative
    return [CodexQuotaGuard.MeterEvidenceNative]::OpenDirectory($Path)
}
function Get-MeterEvidenceDirectoryAttributes($Handle) {
    return [CodexQuotaGuard.MeterEvidenceNative]::Attributes($Handle)
}
function Get-MeterEvidenceDirectorySddl($Handle) {
    return [CodexQuotaGuard.MeterEvidenceNative]::SecurityDescriptor($Handle)
}
function Get-MeterEvidenceDirectoryPath($Handle) {
    return [CodexQuotaGuard.MeterEvidenceNative]::FinalPath($Handle)
}
function Get-MeterEvidenceChildDirectories([string]$Path) {
    return [IO.Directory]::GetDirectories($Path)
}
function Assert-MeterEvidencePinnedPath($Handle, [string]$ExpectedPath) {
    $actual = (Get-MeterEvidenceDirectoryPath $Handle).TrimEnd([char]'\')
    if (-not $actual.Equals([IO.Path]::GetFullPath($ExpectedPath).TrimEnd([char]'\'), [StringComparison]::OrdinalIgnoreCase)) {
        throw 'The conflict evidence directory handle resolves through a different path.'
    }
}
function Open-MeterEvidenceCommonDataAnchor([string]$CommonDirectory) {
    # Sharing flags alone do not stop metadata-only junction conversion of an
    # empty directory. Keep an existing trusted child pinned before the first create.
    foreach ($child in @(Get-MeterEvidenceChildDirectories $CommonDirectory)) {
        $anchor = $null
        try {
            $anchor = Open-MeterEvidenceDirectory $child
            Assert-MeterEvidencePinnedPath $anchor $child
            $attributes = Get-MeterEvidenceDirectoryAttributes $anchor
            if (($attributes -band 0x400) -or -not ($attributes -band 0x10)) { throw 'Unsafe anchor.' }
            Assert-MeterEvidenceAncestorSecurity (Get-MeterEvidenceDirectorySddl $anchor)
            return $anchor
        } catch {
            if ($anchor) { $anchor.Dispose() }
        }
    }
    throw 'Windows CommonApplicationData has no trusted directory child to pin safely.'
}
function New-MeterEvidenceProtectedDirectory([string]$Path) {
    Initialize-MeterEvidenceNative
    [CodexQuotaGuard.MeterEvidenceNative]::CreateProtectedDirectory($Path)
}

function Initialize-MeterConflictEvidenceDirectory([string]$UserSid) {
    $evidencePath = Get-MeterConflictEvidencePath $UserSid
    $userDirectory = [IO.Path]::GetDirectoryName($evidencePath)
    $rootDirectory = [IO.Path]::GetDirectoryName($userDirectory)
    $commonDirectory = [IO.Path]::GetDirectoryName($rootDirectory)
    $volume = [IO.Path]::GetPathRoot($commonDirectory)
    $ancestors = @($volume)
    $component = $volume
    foreach ($name in $commonDirectory.Substring($volume.Length).Split([char]'\')) {
        if ($name) { $component = Join-Path $component $name; $ancestors += $component }
    }
    $pins = [Collections.Generic.List[object]]::new()
    try {
        foreach ($directory in @($ancestors) + @($rootDirectory, $userDirectory)) {
            $isChannelDirectory = $directory -eq $rootDirectory -or $directory -eq $userDirectory
            if ($isChannelDirectory) { New-MeterEvidenceProtectedDirectory $directory }
            $pin = Open-MeterEvidenceDirectory $directory
            $pins.Add($pin)
            Assert-MeterEvidencePinnedPath $pin $directory
            $attributes = Get-MeterEvidenceDirectoryAttributes $pin
            if (($attributes -band 0x400) -or -not ($attributes -band 0x10)) {
                throw 'The conflict evidence directory chain contains a reparse point or non-directory.'
            }
            $sddl = Get-MeterEvidenceDirectorySddl $pin
            if ($isChannelDirectory) { Assert-MeterEvidenceDirectorySecurity $sddl }
            else { Assert-MeterEvidenceAncestorSecurity $sddl }
            if ($directory -eq $commonDirectory) {
                $anchor = Open-MeterEvidenceCommonDataAnchor $commonDirectory
                $pins.Add($anchor)
            }
        }
        return $evidencePath
    } finally {
        for ($index = $pins.Count - 1; $index -ge 0; $index--) { $pins[$index].Dispose() }
    }
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
try {
    Initialize-MeterConflictEvidenceDirectory (Resolve-MeterOwnerSid $identity.User.Value '') | Out-Null
} catch {
    # Evidence is optional: unsafe/missing channels disable automatic conflict cleanup.
    Write-Warning ('Conflict evidence channel is unavailable; automatic conflict cleanup is disabled. ' + $_.Exception.Message)
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
