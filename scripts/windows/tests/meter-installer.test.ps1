#requires -Version 5.1
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '..\install-meter-service.ps1')

function Assert-Equal($Actual, $Expected) {
    if ($Actual -ne $Expected) { throw "Expected $Expected, got $Actual" }
}

foreach ($address in @('http://10.83.220.35:8080', 'http://10.83.220.35:8080/')) {
    Assert-Equal ((Resolve-MeterServerUri $address).GetLeftPart([UriPartial]::Authority)) 'http://10.83.220.35:8080'
}
Assert-Equal ((Resolve-MeterServerUri ' http://0.0.0.0:8080 ').Host) '127.0.0.1'
Assert-MeterExpectedUser 'S-1-5-21-test' 'S-1-5-21-test'
$wrongUserRejected = $false
try { Assert-MeterExpectedUser 'S-1-5-21-other' 'S-1-5-21-test' }
catch { $wrongUserRejected = $true }
Assert-Equal $wrongUserRejected $true
Assert-Equal (Resolve-MeterOwnerSid 'S-1-5-21-100' '') 'S-1-5-21-100'
Assert-Equal (Resolve-MeterOwnerSid 'S-1-5-21-100' 'S-1-5-21-100') 'S-1-5-21-100'
$wrongOwnerRejected = $false
try { Resolve-MeterOwnerSid 'S-1-5-21-100' 'S-1-5-21-200' | Out-Null }
catch { $wrongOwnerRejected = $true }
Assert-Equal $wrongOwnerRejected $true
Assert-Equal ((Resolve-MeterServerUri 'http://0.0.0.0:8080').GetLeftPart([UriPartial]::Authority)) 'http://127.0.0.1:8080'

# A missing/unready D: must not affect installation or trigger build-tool lookup.
function Test-Path {
    [CmdletBinding()]
    param([string]$LiteralPath, [string]$PathType)
    if ($LiteralPath -match '^D:\\') { throw 'This test machine has no usable D: drive.' }
    if ($PathType) {
        return Microsoft.PowerShell.Management\Test-Path -LiteralPath $LiteralPath -PathType $PathType
    }
    return Microsoft.PowerShell.Management\Test-Path -LiteralPath $LiteralPath
}

$fixture = Join-Path ([IO.Path]::GetTempPath()) ('meter installer test ' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $fixture | Out-Null
try {
    $binary = Join-Path $fixture 'quota-guard-meter.exe'
    New-Item -ItemType File -Path $binary | Out-Null
    Assert-Equal (Resolve-MeterBundledBinary $binary $fixture) $binary
    Assert-Equal (Resolve-MeterBundledBinary '' $fixture) $binary
    $programFilesRoot = [Environment]::GetFolderPath([Environment+SpecialFolder]::ProgramFiles)
    Assert-Equal (Resolve-MeterInstallDirectory '') (Join-Path $programFilesRoot 'CodexQuotaGuard\Meter')
    Assert-Equal (Resolve-MeterInstallDirectory (Join-Path $fixture 'service')) (Join-Path $fixture 'service')
    $missing = $false
    try { Resolve-MeterBundledBinary (Join-Path $fixture 'missing.exe') $fixture | Out-Null }
    catch { $missing = $true }
    Assert-Equal $missing $true

    # Existing services must keep their device identity and state paths, even
    # when the old meter-device-id.txt has been removed from this profile.
    $appData = Join-Path $fixture 'profile data'
    New-Item -ItemType Directory -Path $appData | Out-Null
    $configPath = Join-Path $fixture 'meter-config.json'
    $expectedDeviceId = [Guid]::NewGuid()
    $existingConfig = @{
        serverUrl = 'http://127.0.0.1:8080'
        codexHome = (Join-Path $fixture '.codex')
        pairingInbox = (Join-Path $appData 'meter-pairing.json')
        stateDir = (Join-Path $fixture 'private-state')
        statusFile = (Join-Path $appData 'meter-status.json')
        deviceId = $expectedDeviceId.ToString()
    }
    [IO.File]::WriteAllText($configPath, ($existingConfig | ConvertTo-Json -Compress),
        [Text.UTF8Encoding]::new($false))
    $imagePath = '"' + $binary + '" service --config "' + $configPath + '"'
    $existing = Get-MeterExistingConfiguration $imagePath $appData
    Assert-Equal $existing.deviceId $expectedDeviceId
    Assert-Equal $existing.binaryPath $binary
    Assert-Equal $existing.configPath $configPath

    $wrongProfileRejected = $false
    try { Get-MeterExistingConfiguration $imagePath (Join-Path $fixture 'other profile') | Out-Null }
    catch { $wrongProfileRejected = $true }
    Assert-Equal $wrongProfileRejected $true

    $invalidImageRejected = $false
    try { Get-MeterExistingConfiguration 'powershell.exe -File unknown.ps1' $appData | Out-Null }
    catch { $invalidImageRejected = $true }
    Assert-Equal $invalidImageRejected $true

    $existingConfig.deviceId = ''
    [IO.File]::WriteAllText($configPath, ($existingConfig | ConvertTo-Json -Compress),
        [Text.UTF8Encoding]::new($false))
    $incompleteConfigRejected = $false
    try { Get-MeterExistingConfiguration $imagePath $appData | Out-Null }
    catch { $incompleteConfigRejected = $true }
    Assert-Equal $incompleteConfigRejected $true
} finally {
    # Delete only the files created by this test; no recursive directory deletion.
    Remove-Item -LiteralPath $configPath -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $appData -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $binary -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $fixture -ErrorAction SilentlyContinue
}
Write-Output 'Meter installer preflight tests passed without a D: drive or development tools.'
