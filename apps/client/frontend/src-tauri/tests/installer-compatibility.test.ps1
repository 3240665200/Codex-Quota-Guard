#requires -Version 5.1
$ErrorActionPreference = 'Stop'
$helper = Join-Path $PSScriptRoot '..\windows\installer-compatibility.ps1'
if (-not (Test-Path -LiteralPath $helper -PathType Leaf)) {
    throw 'Legacy installer compatibility is not implemented: the current installer cannot resolve HKLM or alternate-view records.'
}
. $helper

# These mocks shadow the OS adapters after importing the actual production policy.
# No registry, installed file, process, service or task is modified by this test.
$script:metadata = @{}
$script:records = @()
$script:uninstallCalls = @()
$script:uninstallExit = 0
$script:leaveOldRecord = $false
$script:leaveOldBinary = $false
$script:destinationWritable = $true
$script:recordReads = 0
$script:removedRecords = @()
$script:assertions = 0

function Assert-Equal($Actual, $Expected) {
    $script:assertions++
    if ($Actual -ne $Expected) { throw "Expected [$Expected], received [$Actual]." }
}
function Assert-Rejected([scriptblock]$Action, [string]$Pattern = '.') {
    $script:assertions++
    try { & $Action } catch {
        if ($_.Exception.Message -notmatch $Pattern) { throw "Unexpected rejection: $_" }
        return
    }
    throw 'Expected this unsafe or ambiguous upgrade to be rejected.'
}
function Read-CqgInstalledRecords { $script:recordReads++; return $script:records }
function Get-CqgExecutableMetadata([string]$Path) { return $script:metadata[$Path] }
function Assert-CqgDirectoryTree([string]$Directory) {
    if ($Directory -match 'reparse') { throw 'Unsafe reparse point.' }
}
function Test-CqgDestinationWritable([string]$Directory) { return $script:destinationWritable }
function Remove-CqgRegistryRecord([string]$Hive, [int]$View, [string]$Directory) {
    $script:removedRecords += [pscustomobject]@{ Hive = $Hive; View = $View; Directory = $Directory }
    $script:records = @($script:records | Where-Object { $_.Hive -ne $Hive -or $_.View -ne $View })
}
function Invoke-CqgUninstaller([string]$Executable, [string]$Arguments, [bool]$Elevate) {
    $script:uninstallCalls += [pscustomobject]@{ Executable = $Executable; Arguments = $Arguments; Elevate = $Elevate }
    if ($script:uninstallExit -eq 0) {
        if (-not $script:leaveOldRecord) { $script:records = @() }
        if (-not $script:leaveOldBinary) {
            $script:metadata.Remove((Join-Path (Split-Path -Parent $Executable) 'codex-quota-guard.exe'))
        }
    }
    return $script:uninstallExit
}
function Reset-Fixture {
    $script:metadata = @{}
    $script:records = @()
    $script:uninstallCalls = @()
    $script:uninstallExit = 0
    $script:leaveOldRecord = $false
    $script:leaveOldBinary = $false
    $script:destinationWritable = $true
    $script:recordReads = 0
    $script:removedRecords = @()
}
function New-Record([string]$Hive = 'HKCU', [int]$View = 64,
    [string]$Directory = 'D:\Custom Quota Guard', [string]$Version = '0.3.2') {
    $script:metadata[(Join-Path $Directory 'codex-quota-guard.exe')] = [pscustomobject]@{
        ProductName = 'Codex Quota Guard'; Version = $Version; IsRegularFile = $true
    }
    $script:metadata[(Join-Path $Directory 'uninstall.exe')] = [pscustomobject]@{
        ProductName = 'Codex Quota Guard'; Version = $Version; IsRegularFile = $true
    }
    return [pscustomobject]@{
        Hive = $Hive; View = $View; ArpPresent = $true
        DisplayName = 'Codex Quota Guard'; Publisher = 'codexquotaguard'; Version = $Version
        InstallLocation = ('"' + $Directory + '"'); RememberedPath = $Directory
        MainBinaryName = 'codex-quota-guard.exe'
        UninstallString = ('"' + (Join-Path $Directory 'uninstall.exe') + '"')
    }
}
function Migrate([switch]$Confirmed, [switch]$Silent, [switch]$Passive,
    [string]$ExpectedDirectory = 'D:\Custom Quota Guard', [string]$ExpectedVersion = '0.3.3') {
    Invoke-CqgConfirmedMigration -ExpectedDirectory $ExpectedDirectory -ExpectedVersion $ExpectedVersion `
        -ExpectedView 64 -DestinationDirectory 'C:\Users\InstallerFixture\AppData\Local\Codex Quota Guard' `
        -Confirmed:$Confirmed -Silent:$Silent -Passive:$Passive
}

# NSIS passes paths as UTF-16 request data, never as PowerShell command text.
$requestFixture = Join-Path ([IO.Path]::GetTempPath()) ('cqg-request-' + [Guid]::NewGuid().ToString('N') + '.ini')
try {
    [IO.File]::WriteAllLines($requestFixture, @('[Request]',
        'DestinationDirectory=D:\配额 $guard; `literal''s directory', 'ExpectedView=64'), [Text.Encoding]::Unicode)
    $request = Read-CqgRequest $requestFixture
    Assert-Equal $request.DestinationDirectory 'D:\配额 $guard; `literal''s directory'
    Assert-Equal $request.ExpectedView '64'
    [IO.File]::WriteAllText($requestFixture, "[Request]`r`nConfirmed=1`r`nConfirmed=0", [Text.Encoding]::Unicode)
    Assert-Rejected { Read-CqgRequest $requestFixture }
    [IO.File]::WriteAllText($requestFixture, "[Request]`r`nOperation=Migrate", [Text.Encoding]::Unicode)
    Assert-Rejected { Read-CqgRequest $requestFixture }
    foreach ($flag in @('Confirmed', 'Silent', 'Passive')) {
        [IO.File]::WriteAllText($requestFixture, ("[Request]`r`n" + $flag + '=1'), [Text.Encoding]::Unicode)
        Assert-Rejected { Read-CqgRequest $requestFixture }
    }
    [IO.File]::WriteAllText($requestFixture, "[Request]`r`nConfirmed=1", [Text.Encoding]::UTF8)
    Assert-Rejected { Read-CqgRequest $requestFixture }
} finally { if ([IO.File]::Exists($requestFixture)) { [IO.File]::Delete($requestFixture) } }

foreach ($hive in @('HKCU', 'HKLM')) {
    # HKCU Software has one shared storage entry; HKLM has two distinct views.
    $fixtureViews = if ($hive -eq 'HKCU') { @(64) } else { @(64, 32) }
    foreach ($view in $fixtureViews) {
        Reset-Fixture
        $record = New-Record -Hive $hive -View $view
        $plan = Resolve-CqgUpgradePlan -Records @($record)
        Assert-Equal $plan.Hive $hive
        Assert-Equal $plan.View $view
        Assert-Equal $plan.Directory 'D:\Custom Quota Guard'
        Assert-Equal $plan.Version '0.3.2'
        Assert-Equal $plan.Mode $(if ($hive -eq 'HKCU') { 'upgrade' } else { 'migrate' })
        Assert-Equal $script:uninstallCalls.Count 0
    }
}

# Matching non-NSIS/MSI entries block installation without executing their
# registry command or silently creating another installed copy.
Reset-Fixture
$msi = [pscustomobject]@{ Kind = 'foreign'; Hive = 'HKLM'; View = 64; ArpPresent = $true }
Assert-Rejected { Resolve-CqgUpgradePlan -Records @($msi) } 'Windows'
Assert-Equal $script:uninstallCalls.Count 0

# HKLM's two independent views of the same install can describe one directory.
Reset-Fixture
$one = New-Record -Hive HKLM -View 64
$two = New-Record -Hive HKLM -View 32 -Directory 'd:\custom quota guard'
$plan = Resolve-CqgUpgradePlan -Records @($one, $two)
Assert-Equal $plan.Mode 'migrate'
Assert-Equal $plan.View 64
Assert-Equal $plan.Views.Count 2
Reset-Fixture
Assert-Rejected { Resolve-CqgUpgradePlan -Records @((New-Record), (New-Record -View 32 -Directory 'D:\Other copy')) }
Reset-Fixture
Assert-Rejected { Resolve-CqgUpgradePlan -Records @((New-Record), (New-Record -Hive HKLM)) }
Reset-Fixture
Assert-Rejected { Resolve-CqgUpgradePlan -Records @((New-Record), (New-Record -View 32 -Version '0.3.0')) }

# A remembered language-only key is not an installed version. A verified
# remaining main binary is a repair target, not a successful uninstall.
Reset-Fixture
$languageOnly = [pscustomobject]@{ Hive = 'HKCU'; View = 64; ArpPresent = $false; RememberedPath = '' }
Assert-Equal (Resolve-CqgUpgradePlan -Records @($languageOnly)).Mode 'fresh'
$remembered = New-Record
$remembered.ArpPresent = $false
$script:metadata.Remove('D:\Custom Quota Guard\uninstall.exe')
$repair = Resolve-CqgUpgradePlan -Records @($remembered)
Assert-Equal $repair.Mode 'repair'
Assert-Equal $repair.Directory 'D:\Custom Quota Guard'
Assert-Equal $script:uninstallCalls.Count 0

# Machine-scope remnants cannot be silently repaired by a current-user setup.
Reset-Fixture
$machineRemnant = New-Record -Hive HKLM
$machineRemnant.ArpPresent = $false
Assert-Rejected { Resolve-CqgUpgradePlan -Records @($machineRemnant) }

# System directories, broad shared roots, slash traversal and NT device paths
# must never become a fixed-uninstaller execution target.
foreach ($directory in @('C:\Windows', 'C:\Windows\System32\Guard',
    'C:\Program Files', 'C:\Program Files (x86)', 'C:\Users',
    'C:\Guard/../Windows', 'C:\Guard\CONIN$', 'C:\Guard\CONOUT$')) {
    Assert-Rejected { ConvertTo-CqgDirectory $directory }
}

foreach ($field in @('DisplayName', 'Publisher', 'Version', 'MainBinaryName', 'UninstallString')) {
    Reset-Fixture
    $bad = New-Record -Hive HKLM
    $bad.$field = 'unexpected identity or command'
    Assert-Rejected { Resolve-CqgUpgradePlan -Records @($bad) }
}
foreach ($location in @('D:\', '\\server\share\guard', 'relative\guard', 'D:\guard\..\Windows',
    'D:\guard" /payload', 'D:\guard:stream', 'D:\guard|command', 'D:\reparse\guard')) {
    Reset-Fixture
    $bad = New-Record -Hive HKLM
    $bad.InstallLocation = $location
    $bad.RememberedPath = $location
    Assert-Rejected { Resolve-CqgUpgradePlan -Records @($bad) }
}
Reset-Fixture
$mismatch = New-Record -Hive HKLM
$mismatch.RememberedPath = 'D:\Different remembered copy'
Assert-Rejected { Resolve-CqgUpgradePlan -Records @($mismatch) }
Reset-Fixture
$missing = New-Record -Hive HKLM
$script:metadata.Remove('D:\Custom Quota Guard\codex-quota-guard.exe')
Assert-Rejected { Resolve-CqgUpgradePlan -Records @($missing) }
Reset-Fixture
$wrongBinary = New-Record -Hive HKLM
$script:metadata['D:\Custom Quota Guard\codex-quota-guard.exe'].ProductName = 'Another application'
Assert-Rejected { Resolve-CqgUpgradePlan -Records @($wrongBinary) }

# Legal Windows filename punctuation is data, never PowerShell source.
Reset-Fixture
$punctuation = New-Record -Hive HKLM -Directory 'D:\Quota $guard; `literal''s directory' -Version '0.3.3'
$script:records = @($punctuation)
Migrate -Confirmed -ExpectedDirectory 'D:\Quota $guard; `literal''s directory'
Assert-Equal $script:uninstallCalls.Count 1
Assert-Equal $script:uninstallCalls[0].Executable 'D:\Quota $guard; `literal''s directory\uninstall.exe'
Assert-Equal $script:uninstallCalls[0].Arguments '/S _?=D:\Quota $guard; `literal''s directory'
Assert-Equal $script:uninstallCalls[0].Elevate $true

# Confirmation is required, and cached/INI values cannot grant migration.
foreach ($mode in @('unconfirmed', 'silent', 'passive')) {
    Reset-Fixture
    $script:records = @((New-Record -Hive HKLM -Version '0.3.3'))
    switch ($mode) {
        unconfirmed { Assert-Rejected { Migrate } }
        silent { Assert-Rejected { Migrate -Confirmed -Silent } }
        passive { Assert-Rejected { Migrate -Confirmed -Passive } }
    }
    Assert-Equal $script:uninstallCalls.Count 0
}
Reset-Fixture
$script:records = @((New-Record -Hive HKCU -Version '0.3.3'))
Assert-Rejected { Migrate -Confirmed }
Assert-Equal $script:uninstallCalls.Count 0
Reset-Fixture
$script:records = @((New-Record -Hive HKLM -Directory 'D:\Changed after confirmation' -Version '0.3.3'))
Assert-Rejected { Migrate -Confirmed }
Assert-Equal $script:uninstallCalls.Count 0
Reset-Fixture
$script:records = @((New-Record -Hive HKLM -Version '0.3.4'))
Assert-Rejected { Migrate -Confirmed }
Assert-Equal $script:uninstallCalls.Count 0
Reset-Fixture
$script:records = @((New-Record -Hive HKLM -Version '0.3.3'))
$script:destinationWritable = $false
Assert-Rejected { Test-CqgMigrationDestination -ExpectedDirectory 'D:\Custom Quota Guard' -ExpectedVersion '0.3.3' -ExpectedView 64 -DestinationDirectory 'D:\New target' } '当前用户'
Assert-Equal $script:uninstallCalls.Count 0
Assert-Rejected { Migrate -Confirmed }
Assert-Equal $script:uninstallCalls.Count 0

# UAC cancellation, nonzero exit, and surviving old files/records all stop.
foreach ($exitCode in @(1, 2, 1223)) {
    Reset-Fixture
    $script:records = @((New-Record -Hive HKLM -Version '0.3.3'))
    $script:uninstallExit = $exitCode
    Assert-Rejected { Migrate -Confirmed }
    Assert-Equal $script:uninstallCalls.Count 1
}
Reset-Fixture
$script:records = @((New-Record -Hive HKLM -Version '0.3.3'))
$script:leaveOldRecord = $true
Assert-Rejected { Migrate -Confirmed }
Reset-Fixture
$script:records = @((New-Record -Hive HKLM -Version '0.3.3'))
$script:leaveOldBinary = $true
Assert-Rejected { Migrate -Confirmed }
Reset-Fixture
$script:records = @((New-Record -Hive HKLM -Version '0.3.3'))
Migrate -Confirmed
Assert-Equal $script:uninstallCalls.Count 1
Assert-Equal $script:uninstallCalls[0].Arguments '/S _?=D:\Custom Quota Guard'
Assert-Equal ($script:uninstallCalls[0].Arguments -match '/UPDATE') $false
Assert-Equal ($script:recordReads -ge 2) $true

# HKCU Software is shared between registry views. Completing an installation
# must never delete the alias of the newly written uninstall entry.
Reset-Fixture
$old32 = New-Record -View 32 -Version '0.3.2'
$new64 = New-Record -View 64 -Version '0.3.4'
$script:records = @($old32, $new64)
Complete-CqgRegistryUpgrade -Directory 'D:\Custom Quota Guard' -Version '0.3.4'
Assert-Equal $script:removedRecords.Count 0
Reset-Fixture
$script:records = @((New-Record -View 32))
Assert-Rejected { Complete-CqgRegistryUpgrade -Directory 'D:\Custom Quota Guard' -Version '0.3.4' }
Assert-Equal $script:removedRecords.Count 0
Reset-Fixture
$other32 = New-Record -View 32 -Directory 'D:\Another installation'
$new64 = New-Record -View 64 -Version '0.3.4'
$script:records = @($other32, $new64)
Complete-CqgRegistryUpgrade -Directory 'D:\Custom Quota Guard' -Version '0.3.4'
Assert-Equal $script:removedRecords.Count 0
Reset-Fixture
$foreign32 = New-Record -View 32
$foreign32.Publisher = 'different publisher'
$new64 = New-Record -View 64 -Version '0.3.4'
$script:records = @($foreign32, $new64)
Complete-CqgRegistryUpgrade -Directory 'D:\Custom Quota Guard' -Version '0.3.4'
Assert-Equal $script:removedRecords.Count 0
Reset-Fixture
$alias64 = New-Record -View 64 -Version '0.3.4'
$alias32 = $alias64.PSObject.Copy()
$alias32.View = 32
$script:records = @($alias64, $alias32)
Complete-CqgRegistryUpgrade -Directory 'D:\Custom Quota Guard' -Version '0.3.4'
Assert-Equal $script:removedRecords.Count 0
Write-Output "Installer compatibility policy: $script:assertions assertions passed using mocked OS adapters."
