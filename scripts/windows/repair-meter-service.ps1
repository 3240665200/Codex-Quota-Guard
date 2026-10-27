#requires -Version 5.1
[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][string]$ServerUrl,
    [Parameter(Mandatory=$true)][string]$ExpectedUserSid
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'install-meter-service.ps1') -ExpectedUserSid $ExpectedUserSid

function Assert-MeterRepairContext($Existing, [string]$Sid, [string]$Origin) {
    if ([string]$Existing.ownerUserSid -ne $Sid) { return 20 }
    $saved = (Resolve-MeterServerUri ([string]$Existing.serverUrl)).GetLeftPart([UriPartial]::Authority)
    if (-not $saved.Equals($Origin, [StringComparison]::OrdinalIgnoreCase)) { return 21 }
    return 0
}

if ($MyInvocation.InvocationName -eq '.') { return }
try {
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    Assert-MeterExpectedUser $identity.User.Value $ExpectedUserSid
    $principal = [Security.Principal.WindowsPrincipal]::new($identity)
    if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
        throw 'Windows administrator approval is required.'
    }
    $origin = (Resolve-MeterServerUri $ServerUrl).GetLeftPart([UriPartial]::Authority)
    $service = Get-Service -Name 'CodexQuotaGuardMeter' -ErrorAction SilentlyContinue
    if ($service) {
        $registered = Get-CimInstance Win32_Service -Filter "Name='CodexQuotaGuardMeter'"
        $existing = Get-MeterExistingConfiguration $registered.PathName (Join-Path $env:APPDATA 'com.codexquotaguard.desktop')
        $context = Assert-MeterRepairContext $existing.config $identity.User.Value $origin
        if ($context -ne 0) { exit $context }
    } elseif (Test-Path -LiteralPath (Join-Path (Resolve-MeterInstallDirectory '') 'meter-config.json')) {
        # An orphaned registration must not overwrite a custom journal location.
        exit 22
    }
    # The installer stages and verifies the binary, preserves owner/device/state,
    # enables automatic startup and SCM recovery, then verifies a fresh heartbeat.
    & (Join-Path $PSScriptRoot 'install-meter-service.ps1') -ServerUrl $origin `
        -BinaryPath (Join-Path $PSScriptRoot 'quota-guard-meter.exe') -ExpectedUserSid $ExpectedUserSid -RepairOnly
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    & (Join-Path $PSScriptRoot 'configure-cli-adapter.ps1') -Operation Install -MeterExecutable (Join-Path $PSScriptRoot 'quota-guard-meter.exe')
    exit $LASTEXITCODE
} catch {
    # Detailed exception text may contain paths; the UI displays bounded messages.
    exit 1
}
