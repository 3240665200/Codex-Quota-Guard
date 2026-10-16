#requires -Version 5.1
$ErrorActionPreference = 'Stop'
$installer = Join-Path $PSScriptRoot '..\resources\meter\install-meter-service.ps1'
$startup = Join-Path $PSScriptRoot '..\resources\meter\ensure-meter-service.ps1'
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
    throw 'Expected the unsafe evidence channel to be rejected.'
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
    'O:SYG:SYD:(A;;FA;;;SY)(A;;DC;;;BU)',
    'O:SYG:SYD:(A;;FA;;;SY)(A;;SD;;;BU)',
    'O:SYG:SYD:NO_ACCESS_CONTROL')) {
    Assert-Rejected { Assert-MeterEvidenceAncestorSecurity $sddl }
}

# Compile the actual Win32 adapters, but all channel operations below are mocked.
Initialize-MeterEvidenceNative
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
Assert-Equal $script:opened.Count 5
Assert-Equal $script:closed.Count $script:opened.Count
Assert-Equal $script:opened[0] 'C:\'
Assert-Equal $script:opened[-1] $userDirectory
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
$script:entries['C:\EvidenceFixture'].Sddl = 'O:SYG:SYD:(A;;FA;;;SY)(A;;DC;;;BU)'
Assert-Rejected { Initialize-MeterConflictEvidenceDirectory $sid }
Assert-Equal $script:created.Count 0
Assert-Equal $script:closed.Count $script:opened.Count

# READY is subject to the same elevated preparation policy; standard users never mutate.
$script:prepared = @()
function Initialize-MeterConflictEvidenceDirectory([string]$UserSid) { $script:prepared += $UserSid }
Initialize-MeterStartupConflictEvidence $false $sid
Assert-Equal $script:prepared.Count 0
Initialize-MeterStartupConflictEvidence $true $sid
Assert-Equal $script:prepared.Count 1
Assert-Equal $script:prepared[0] $sid
$startupSource = Get-Content -LiteralPath $startup -Raw
Assert-Equal ($startupSource.IndexOf('Initialize-MeterStartupConflictEvidence $isAdmin $currentSid') -lt $startupSource.IndexOf("if (`$action -eq 'READY')")) $true
Assert-Equal ($startupSource -match 'if \(\$isAdmin -and \$bundledIdentity') $true
Write-Output "Conflict evidence security: $script:assertions assertions passed using mocked channel adapters; no service or configuration was changed."
