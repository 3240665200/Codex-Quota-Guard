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
} finally {
    # Delete only the files created by this test; no recursive directory deletion.
    Remove-Item -LiteralPath $binary -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $fixture -ErrorAction SilentlyContinue
}
Write-Output 'Meter installer preflight tests passed without a D: drive or development tools.'
