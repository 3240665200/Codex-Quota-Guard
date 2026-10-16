#requires -Version 5.1
$ErrorActionPreference = 'Stop'
$installer = Join-Path $PSScriptRoot '..\..\..\..\..\scripts\windows\install-meter-service.ps1'
$startup = Join-Path $PSScriptRoot '..\..\..\..\..\scripts\windows\ensure-meter-service.ps1'
. $installer
. $startup
$script:assertions = 0
function Assert-Equal($Actual, $Expected) {
    $script:assertions++
    if ($Actual -ne $Expected) { throw "Expected [$Expected], received [$Actual]." }
}
function Assert-Rejected([scriptblock]$Action, [string]$Pattern = '.') {
    $script:assertions++
    try { & $Action | Out-Null } catch {
        if ($_.Exception.Message -notmatch $Pattern) { throw "Unexpected rejection: $_" }
        return
    }
    throw "Expected the unsafe evidence channel to be rejected: $Action (SDDL: $sddl)."
}

# A missing secure channel is the intended RED signal, before any OS adapter runs.
if (-not (Get-Command Initialize-MeterConflictEvidenceDirectory -ErrorAction SilentlyContinue)) {
    throw 'Secure conflict evidence channel is not implemented; an elevated installer cannot establish trusted evidence.'
}

$script:strictSddl = 'O:BAG:BAD:P(A;OICI;FA;;;SY)(A;OICI;FA;;;BA)'
$script:ancestorSddl = 'O:SYG:SYD:(A;;FA;;;SY)(A;;FA;;;BA)(A;;0x1200a9;;;BU)(A;OICIIO;FA;;;CO)'
$sid = 'S-1-5-21-111-222-333-1001'
$knownFolder = [Environment]::GetFolderPath([Environment+SpecialFolder]::CommonApplicationData)
$savedProgramData = $env:ProgramData
try {
    $env:ProgramData = 'Z:\attacker-program-data'
    Assert-Equal (Get-MeterConflictEvidencePath $sid) (Join-Path $knownFolder "CodexQuotaGuardConflictEvidence\$sid\ccswitch-conflict-evidence.json")
    Assert-Equal (Get-MeterConflictEvidencePath 'S-1-12-1-111-222-333-444') (Join-Path $knownFolder 'CodexQuotaGuardConflictEvidence\S-1-12-1-111-222-333-444\ccswitch-conflict-evidence.json')
} finally { $env:ProgramData = $savedProgramData }
foreach ($invalidSid in @('', '..', 'S-1-5-21-1\..\victim', 'S-1-5-21-1:stream', 'S-1-5-021-1')) {
    Assert-Rejected { Get-MeterConflictEvidencePath $invalidSid }
}
Assert-MeterEvidenceDirectorySecurity $script:strictSddl
Assert-MeterEvidenceDirectorySecurity 'O:SYG:SYD:P(A;OICI;FA;;;SY)(A;OICI;FA;;;BA)'
foreach ($sddl in @(
    'O:BAG:BAD:(A;OICI;FA;;;SY)(A;OICI;FA;;;BA)',
    'O:BAG:BAD:P(A;OICI;FA;;;SY)(A;OICI;FA;;;BA)(A;OICI;FR;;;BU)',
    'O:BAG:BAD:P(A;OICI;FA;;;SY)(A;OICI;FA;;;BA)(A;OICI;FA;;;WD)',
    'O:BAG:BAD:P(A;OICI;FA;;;SY)',
    'O:BAG:BAD:P(A;OICI;FR;;;SY)(A;OICI;FA;;;BA)',
    'O:BAG:BAD:P(A;OICIID;FA;;;SY)(A;OICI;FA;;;BA)',
    'O:BAG:BAD:P(A;;FA;;;SY)(A;OICI;FA;;;BA)',
    "O:${sid}G:BAD:P(A;OICI;FA;;;SY)(A;OICI;FA;;;BA)",
    'O:BAG:BAD:NO_ACCESS_CONTROL')) {
    Assert-Rejected { Assert-MeterEvidenceDirectorySecurity $sddl }
}
Assert-MeterEvidenceAncestorSecurity $script:ancestorSddl
Assert-MeterEvidenceAncestorSecurity 'O:S-1-5-80-956008885-3418522649-1831038044-1853292631-2271478464G:SYD:(A;;FA;;;SY)(A;;FA;;;BA)'
foreach ($sddl in @(
    "O:${sid}G:SYD:(A;;FA;;;SY)",
    'O:SYG:SYD:(A;;FA;;;SY)(A;;WD;;;BU)',
    'O:SYG:SYD:(A;;FA;;;SY)(A;;WO;;;BU)',
    'O:SYG:SYD:(A;;FA;;;SY)(A;;0x40;;;BU)',
    'O:SYG:SYD:(A;;FA;;;SY)(A;;SD;;;BU)',
    'O:SYG:SYD:NO_ACCESS_CONTROL')) {
    Assert-Rejected { Assert-MeterEvidenceAncestorSecurity $sddl }
}

# Exercise native pinning only on a disposable temporary directory, then mock the channel.
Initialize-MeterEvidenceNative
Add-Type -TypeDefinition @'
using System;
using System.IO;
using System.Text;
using System.Runtime.InteropServices;
using Microsoft.Win32.SafeHandles;
public static class EvidenceReparseAttackFixture {
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern SafeFileHandle CreateFileW(string path, uint access, uint share, IntPtr attributes,
        uint creation, uint flags, IntPtr template);
    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern bool DeviceIoControl(SafeFileHandle handle, uint control, byte[] buffer,
        int length, IntPtr output, int outputLength, out int returned, IntPtr overlapped);
    public static int TrySetJunctionUsingAttributesOnly(string path, string target) {
        using (SafeFileHandle handle = CreateFileW(path, 0x100, 7, IntPtr.Zero, 3, 0x02200000, IntPtr.Zero)) {
            if (handle.IsInvalid) return Marshal.GetLastWin32Error();
            byte[] native = Encoding.Unicode.GetBytes(@"\??\" + target);
            byte[] printable = Encoding.Unicode.GetBytes(target);
            using (MemoryStream data = new MemoryStream()) {
                using (BinaryWriter writer = new BinaryWriter(data)) {
                    writer.Write((uint)0xA0000003); writer.Write((ushort)(12 + native.Length + printable.Length));
                    writer.Write((ushort)0); writer.Write((ushort)0); writer.Write((ushort)native.Length);
                    writer.Write((ushort)(native.Length + 2)); writer.Write((ushort)printable.Length);
                    writer.Write(native); writer.Write((ushort)0); writer.Write(printable); writer.Write((ushort)0);
                    writer.Flush(); byte[] bytes = data.ToArray(); int returned;
                    if (!DeviceIoControl(handle, 0x000900A4, bytes, bytes.Length, IntPtr.Zero, 0, out returned, IntPtr.Zero))
                        return Marshal.GetLastWin32Error();
                    return 0;
                }
            }
        }
    }
}
'@
$pinFixture = Join-Path ([IO.Path]::GetTempPath()) ('cqg-evidence-pin-' + [Guid]::NewGuid().ToString('N'))
$junctionFixture = Join-Path $pinFixture 'junction'
$nativePin = $null
$junctionPin = $null
New-Item -ItemType Directory -Path $pinFixture | Out-Null
try {
    New-Item -ItemType Junction -Path $junctionFixture -Value $pinFixture | Out-Null
    $junctionPin = Open-MeterEvidenceDirectory $junctionFixture
    Assert-Equal (((Get-MeterEvidenceDirectoryAttributes $junctionPin) -band 0x400) -ne 0) $true
    $junctionPin.Dispose(); $junctionPin = $null
    [IO.Directory]::Delete($junctionFixture)
    $occupiedDirectory = Join-Path $pinFixture 'occupied'
    New-Item -ItemType Directory -Path $occupiedDirectory | Out-Null
    $nativePin = Open-MeterEvidenceDirectory $pinFixture
    Assert-Equal (Get-MeterEvidenceDirectoryPath $nativePin) $pinFixture
    Assert-Equal (((Get-MeterEvidenceDirectoryAttributes $nativePin) -band 0x10) -ne 0) $true
    Assert-Equal ([bool](Get-MeterEvidenceDirectorySddl $nativePin)) $true
    Assert-Rejected { [IO.Directory]::Move($pinFixture, ($pinFixture + '-moved')) } 'being used|another process|process cannot|进程|占用'
    $reparseTarget = [IO.Path]::GetTempPath().TrimEnd([char]'\')
    Assert-Equal ([EvidenceReparseAttackFixture]::TrySetJunctionUsingAttributesOnly($pinFixture, $reparseTarget)) 145
    Assert-Equal (((Get-MeterEvidenceDirectoryAttributes $nativePin) -band 0x400) -ne 0) $false
    [IO.Directory]::Delete($occupiedDirectory)
    # Share flags do not lock metadata-only opens. A nonempty pinned ancestor and
    # strict child ACLs are essential: the same operation succeeds once empty.
    Assert-Equal ([EvidenceReparseAttackFixture]::TrySetJunctionUsingAttributesOnly($pinFixture, $reparseTarget)) 0
    Assert-Equal (((Get-MeterEvidenceDirectoryAttributes $nativePin) -band 0x400) -ne 0) $true
} finally {
    if ($junctionPin) { $junctionPin.Dispose() }
    if ($nativePin) { $nativePin.Dispose() }
    # Exact disposable paths only; junction deletion never traverses its target.
    foreach ($fixtureDirectory in @($pinFixture, ($pinFixture + '-moved'))) {
        $fixtureJunction = Join-Path $fixtureDirectory 'junction'
        if ([IO.Directory]::Exists($fixtureJunction)) { [IO.Directory]::Delete($fixtureJunction) }
        if ([IO.Directory]::Exists($fixtureDirectory)) { [IO.Directory]::Delete($fixtureDirectory) }
    }
}
function Get-MeterEvidenceCommonApplicationData { return 'C:\EvidenceFixture\ProgramData' }
$script:entries = @{}
$script:opened = @()
$script:closed = @()
$script:created = @()
function Reset-Channel {
    $script:entries = @{
        'C:\' = @{ Attributes = 16; Sddl = $script:ancestorSddl }
        'C:\EvidenceFixture' = @{ Attributes = 16; Sddl = $script:ancestorSddl }
        'C:\EvidenceFixture\ProgramData' = @{ Attributes = 16; Sddl = $script:ancestorSddl }
        'C:\EvidenceFixture\ProgramData\existing-anchor' = @{ Attributes = 16; Sddl = $script:ancestorSddl }
    }
    $script:opened = @(); $script:closed = @(); $script:created = @()
}
function Open-MeterEvidenceDirectory([string]$Path) {
    if (-not $script:entries.ContainsKey($Path)) { throw 'The fixture directory does not exist.' }
    $script:opened += $Path
    $pin = [pscustomobject]@{ Path = $Path }
    $pin | Add-Member -MemberType ScriptMethod -Name Dispose -Value { $script:closed += $this.Path }
    return $pin
}
function Get-MeterEvidenceDirectoryAttributes($Handle) { return $script:entries[$Handle.Path].Attributes }
function Get-MeterEvidenceDirectorySddl($Handle) { return $script:entries[$Handle.Path].Sddl }
function Get-MeterEvidenceDirectoryPath($Handle) {
    if ($script:entries[$Handle.Path].FinalPath) { return $script:entries[$Handle.Path].FinalPath }
    return $Handle.Path
}
function Get-MeterEvidenceChildDirectories([string]$Path) {
    if ($script:entries.ContainsKey((Join-Path $Path 'existing-anchor'))) { return (Join-Path $Path 'existing-anchor') }
    return @()
}
function New-MeterEvidenceProtectedDirectory([string]$Path) {
    $script:created += $Path
    # Simulate CreateDirectoryW's atomic security descriptor and existing-name race.
    if (-not $script:entries.ContainsKey($Path)) {
        $script:entries[$Path] = @{ Attributes = 16; Sddl = $script:strictSddl }
    }
}
$root = 'C:\EvidenceFixture\ProgramData\CodexQuotaGuardConflictEvidence'
$userDirectory = Join-Path $root $sid
Reset-Channel
Assert-Equal (Initialize-MeterConflictEvidenceDirectory $sid) (Join-Path $userDirectory 'ccswitch-conflict-evidence.json')
Assert-Equal $script:created.Count 2
Assert-Equal $script:opened.Count 6
Assert-Equal $script:closed.Count $script:opened.Count
Assert-Equal $script:opened[0] 'C:\'
Assert-Equal $script:opened[-1] $userDirectory
foreach ($unsafeAnchor in @('missing', 'reparse', 'untrusted-owner', 'redirected-path')) {
    Reset-Channel
    $anchorPath = 'C:\EvidenceFixture\ProgramData\existing-anchor'
    switch ($unsafeAnchor) {
        missing { $script:entries.Remove($anchorPath) }
        reparse { $script:entries[$anchorPath].Attributes = 1040 }
        untrusted-owner { $script:entries[$anchorPath].Sddl = "O:${sid}G:BAD:(A;;FA;;;SY)" }
        redirected-path { $script:entries[$anchorPath].FinalPath = 'C:\Redirected\existing-anchor' }
    }
    Assert-Rejected { Initialize-MeterConflictEvidenceDirectory $sid } 'trusted directory child'
    Assert-Equal $script:created.Count 0
    Assert-Equal $script:closed.Count $script:opened.Count
}
Reset-Channel
$script:entries['C:\EvidenceFixture\ProgramData'].FinalPath = 'C:\Redirected\ProgramData'
Assert-Rejected { Initialize-MeterConflictEvidenceDirectory $sid } 'different path'
Assert-Equal $script:created.Count 0
Assert-Equal $script:closed.Count $script:opened.Count
Reset-Channel
$script:entries[$root] = @{ Attributes = 16; Sddl = $script:strictSddl }
$script:entries[$userDirectory] = @{ Attributes = 16; Sddl = $script:strictSddl }
Initialize-MeterConflictEvidenceDirectory $sid | Out-Null
Assert-Equal $script:closed.Count $script:opened.Count

# Existing malicious directories are rejected without rewriting ACLs or children.
foreach ($unsafePath in @($root, $userDirectory)) {
    Reset-Channel
    $script:entries[$root] = @{ Attributes = 16; Sddl = $script:strictSddl }
    $script:entries[$unsafePath] = @{ Attributes = 16; Sddl = 'O:BAG:BAD:P(A;OICI;FA;;;SY)(A;OICI;FA;;;BA)(A;OICI;FA;;;BU)' }
    Assert-Rejected { Initialize-MeterConflictEvidenceDirectory $sid }
    Assert-Equal $script:entries[$unsafePath].Sddl 'O:BAG:BAD:P(A;OICI;FA;;;SY)(A;OICI;FA;;;BA)(A;OICI;FA;;;BU)'
    Assert-Equal $script:closed.Count $script:opened.Count
    if ($unsafePath -eq $root) { Assert-Equal ($script:created -contains $userDirectory) $false }
}
foreach ($reparsePath in @('C:\', 'C:\EvidenceFixture', 'C:\EvidenceFixture\ProgramData', $root, $userDirectory)) {
    Reset-Channel
    $script:entries[$root] = @{ Attributes = 16; Sddl = $script:strictSddl }
    $script:entries[$userDirectory] = @{ Attributes = 16; Sddl = $script:strictSddl }
    $script:entries[$reparsePath].Attributes = 1040
    Assert-Rejected { Initialize-MeterConflictEvidenceDirectory $sid } 'reparse'
    Assert-Equal $script:closed.Count $script:opened.Count
}
Reset-Channel
$script:entries[$root] = @{ Attributes = 0; Sddl = $script:strictSddl }
Assert-Rejected { Initialize-MeterConflictEvidenceDirectory $sid } 'non-directory'
Assert-Equal $script:closed.Count $script:opened.Count
Reset-Channel
$script:entries['C:\EvidenceFixture'].Sddl = 'O:SYG:SYD:(A;;FA;;;SY)(A;;0x40;;;BU)'
Assert-Rejected { Initialize-MeterConflictEvidenceDirectory $sid }
Assert-Equal $script:created.Count 0
Assert-Equal $script:closed.Count $script:opened.Count

# READY is subject to the same elevated preparation policy; standard users never mutate.
$script:prepared = @()
function Initialize-MeterConflictEvidenceDirectory([string]$UserSid) { $script:prepared += $UserSid }
Assert-Equal (Initialize-MeterStartupConflictEvidence $false $sid) $false
Assert-Equal $script:prepared.Count 0
Assert-Equal (Initialize-MeterStartupConflictEvidence $true $sid) $true
Assert-Equal $script:prepared.Count 1
Assert-Equal $script:prepared[0] $sid
function Initialize-MeterConflictEvidenceDirectory([string]$UserSid) { throw 'Fixture unsafe ACL.' }
Assert-Equal (Initialize-MeterStartupConflictEvidence $true $sid 3>$null) $false
$startupSource = Get-Content -LiteralPath $startup -Raw
$prepareIndex = $startupSource.IndexOf('Initialize-MeterStartupConflictEvidence $isAdmin $currentSid')
Assert-Equal ($prepareIndex -ge 0 -and $prepareIndex -lt $startupSource.IndexOf("if (`$action -eq 'READY')")) $true
Assert-Equal ($startupSource -match 'if \(\$isAdmin -and \$bundledIdentity') $true
$parseErrors = $null; $parseTokens = $null
$installerAst = [Management.Automation.Language.Parser]::ParseFile($installer, [ref]$parseTokens, [ref]$parseErrors)
Assert-Equal $parseErrors.Count 0
$prepareCommands = @($installerAst.FindAll({ param($Node)
    $Node -is [Management.Automation.Language.CommandAst] -and $Node.GetCommandName() -eq 'Initialize-MeterConflictEvidenceDirectory'
}, $true))
Assert-Equal $prepareCommands.Count 1
$parentNode = $prepareCommands[0].Parent
while ($parentNode -and $parentNode -isnot [Management.Automation.Language.TryStatementAst]) { $parentNode = $parentNode.Parent }
Assert-Equal ($null -ne $parentNode -and $parentNode.CatchClauses.Count -gt 0) $true
Write-Output "Conflict evidence security: $script:assertions assertions passed using mocked channel adapters; no service or configuration was changed."
