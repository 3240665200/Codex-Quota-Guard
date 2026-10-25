#requires -Version 5.1
[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][string]$ServerUrl,
    [Parameter(Mandatory=$true)][string]$ExpectedUserSid
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'install-meter-service.ps1') -ServerUrl $ServerUrl -ExpectedUserSid $ExpectedUserSid

function Assert-MeterConfigurationPermission([string]$ExpectedSid) {
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    Assert-MeterExpectedUser $identity.User.Value $ExpectedSid
    $principal = [Security.Principal.WindowsPrincipal]::new($identity)
    if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
        throw 'Changing the statistics service address requires Windows administrator approval.'
    }
    return $identity.User.Value
}

function Write-MeterConfiguredConnection([string]$Path, [string]$Origin, [string]$Binary) {
    Write-MeterConfigurationAtomic $Path @{
        serverUrl=$Origin;binaryVersion=(Test-MeterBinary $Binary)
        binaryHash=(Get-FileHash -LiteralPath $Binary -Algorithm SHA256).Hash
    }
}

# This helper only changes an existing service's reporting destination. It never
# installs a service, replaces its executable, or repairs its recovery policy.
function Invoke-MeterServerConfiguration([string]$TargetUrl, [string]$ExpectedSid) {

$lock = $null
$changed = $false
$oldConfig = $null
$oldConnection = $null
$wasRunning = $false
$serviceName = 'CodexQuotaGuardMeter'
try {
    $sid = Assert-MeterConfigurationPermission $ExpectedSid
    $origin = (Resolve-MeterServerUri $TargetUrl).GetLeftPart([UriPartial]::Authority)
    $appData = Join-Path $env:APPDATA 'com.codexquotaguard.desktop'
    $registered = Get-CimInstance Win32_Service -Filter "Name='$serviceName'"
    if (-not $registered) { throw 'Statistics service is not installed. Run the application installer.' }
    $existing = Get-MeterExistingConfiguration $registered.PathName $appData
    Resolve-MeterOwnerSid $sid ([string]$existing.config.ownerUserSid) | Out-Null
    $lock = [IO.File]::Open((Join-Path ([IO.Path]::GetDirectoryName($existing.configPath)) '.meter-setup.lock'),
        [IO.FileMode]::OpenOrCreate, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
    $registered = Get-CimInstance Win32_Service -Filter "Name='$serviceName'"
    $existing = Get-MeterExistingConfiguration $registered.PathName $appData
    Resolve-MeterOwnerSid $sid ([string]$existing.config.ownerUserSid) | Out-Null
    $oldOrigin = (Resolve-MeterServerUri ([string]$existing.config.serverUrl)).GetLeftPart([UriPartial]::Authority)
    $connectionFile = Join-Path $appData 'meter-connection.json'
    if ($oldOrigin -ceq $origin -and -not (Test-MeterStateTransition $existing.config $origin)) {
        # A prior interrupted metadata write must not leave the UI permanently
        # offline. Reconcile only this public record; never restart the service.
        Write-MeterConfiguredConnection $connectionFile $origin $existing.binaryPath
        return
    }
    $oldConfig = [IO.File]::ReadAllText($existing.configPath)
    $configuration = $oldConfig | ConvertFrom-Json
    if (Test-Path -LiteralPath $connectionFile -PathType Leaf) { $oldConnection = [IO.File]::ReadAllText($connectionFile) }
    $service = Get-Service -Name $serviceName -ErrorAction Stop
    $wasRunning = $service.Status -ne 'Stopped'
    $changed = $true
    if ($wasRunning) {
        Stop-Service -Name $serviceName -ErrorAction Stop
        $service.WaitForStatus('Stopped', [TimeSpan]::FromSeconds(20))
    }
    if (Test-MeterStateTransition $existing.config $origin) {
        $configuration.stateDir = New-MeterServerState $existing.binaryPath $existing.configPath
        $configuration.logDir = Join-Path ([string]$configuration.stateDir) 'logs'
    }
    $configuration.serverUrl = $origin
    Write-MeterConfigurationAtomic $existing.configPath $configuration
    $startedAt = [DateTime]::UtcNow
    Start-Service -Name $serviceName -ErrorAction Stop
    (Get-Service -Name $serviceName).WaitForStatus('Running', [TimeSpan]::FromSeconds(20))
    $version = Test-MeterBinary $existing.binaryPath
    $deadline = [DateTime]::UtcNow.AddSeconds(15)
    $ready = $false
    do {
        try {
            $status = Get-Content -LiteralPath ([string]$configuration.statusFile) -Raw | ConvertFrom-Json
            $written = (Get-Item -LiteralPath ([string]$configuration.statusFile)).LastWriteTimeUtc
            $ready = Test-MeterStartedStatus $status $written $version $startedAt
        } catch { }
        if (-not $ready) { Start-Sleep -Milliseconds 200 }
    } while (-not $ready -and [DateTime]::UtcNow -lt $deadline)
    if (-not $ready) { throw 'The statistics service did not confirm its new destination.' }
    Write-MeterConfiguredConnection $connectionFile $origin $existing.binaryPath
    $changed = $false
} catch {
    $failure = $_.Exception.Message
    if ($changed -and $oldConfig) {
        try {
            $current = Get-Service -Name $serviceName -ErrorAction Stop
            if ($current.Status -ne 'Stopped') {
                Stop-Service -Name $serviceName -ErrorAction Stop
                $current.WaitForStatus('Stopped', [TimeSpan]::FromSeconds(20))
            }
            Write-MeterConfigurationAtomic $existing.configPath ($oldConfig | ConvertFrom-Json)
            if ($null -ne $oldConnection) { Write-MeterConfigurationAtomic $connectionFile ($oldConnection | ConvertFrom-Json) }
            elseif (Test-Path -LiteralPath $connectionFile -PathType Leaf) { Remove-Item -LiteralPath $connectionFile }
            if ($wasRunning) { Start-Service -Name $serviceName -ErrorAction Stop }
        } catch {
            # Preserve the previous destination for manual recovery without
            # changing any accounting journal or assigning records to a new owner.
            [IO.File]::WriteAllText(($existing.configPath + '.rollback.json'), $oldConfig, [Text.UTF8Encoding]::new($false))
            $failure += ' Restoring the previous service configuration failed; a rollback configuration was retained.'
        }
    }
    throw $failure
} finally {
    if ($lock) { $lock.Dispose() }
}
}

if ($MyInvocation.InvocationName -eq '.') { return }
try {
    Invoke-MeterServerConfiguration $ServerUrl $ExpectedUserSid
    exit 0
} catch {
    [Console]::Error.WriteLine($_.Exception.Message)
    exit 1
}
