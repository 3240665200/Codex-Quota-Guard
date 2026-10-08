#requires -Version 5.1
$ErrorActionPreference = 'Stop'
$viewer = Join-Path $PSScriptRoot '..\show-meter-logs.ps1'
$tempRoot = [IO.Path]::GetFullPath((Join-Path $env:TEMP "quota-guard-meter-log-test-$([Guid]::NewGuid())"))
New-Item -ItemType Directory -Path $tempRoot -Force | Out-Null
try {
    $old = [ordered]@{
        atEpochSeconds = 1791300000; kind = 'HEARTBEAT'; state = 'UP_TO_DATE'
        reportedEvents = 0; reportedTokens = 0; heartbeatSent = $true; errorCode = $null
        secretCanary = 'never-display-this-value'
    }
    $recent = [ordered]@{
        atEpochSeconds = 1791300030; kind = 'ERROR'; state = 'ERROR'
        reportedEvents = 0; reportedTokens = 0; heartbeatSent = $false
        errorCode = 'METER_BACKEND_UNAVAILABLE'; secretCanary = 'never-display-this-value'
    }
    [IO.File]::WriteAllText((Join-Path $tempRoot 'meter.1.jsonl'),
        (($old | ConvertTo-Json -Compress) + "`n"))
    [IO.File]::WriteAllText((Join-Path $tempRoot 'meter.jsonl'),
        (($recent | ConvertTo-Json -Compress) + "`n" + 'malformed-row' + "`n"))

    $latest = @(& $viewer -LogDirectory $tempRoot -Tail 1 -WarningAction SilentlyContinue)
    if ($latest.Count -ne 1 -or $latest[0].Kind -ne 'ERROR' -or
            $latest[0].ErrorCode -ne 'METER_BACKEND_UNAVAILABLE') {
        throw 'The viewer did not return the latest safe error entry.'
    }
    if (($latest | Out-String) -match 'never-display-this-value') {
        throw 'The viewer exposed an unknown log property.'
    }
    $all = @(& $viewer -LogDirectory $tempRoot -Tail 2 -WarningAction SilentlyContinue)
    if ($all.Count -ne 2 -or $all[0].Kind -ne 'HEARTBEAT' -or $all[1].Kind -ne 'ERROR') {
        throw 'The viewer did not preserve rotated log order.'
    }
    $errors = @(& $viewer -LogDirectory $tempRoot -Tail 2 -ErrorsOnly -WarningAction SilentlyContinue)
    if ($errors.Count -ne 1 -or $errors[0].Kind -ne 'ERROR') {
        throw 'The error-only filter returned an unexpected entry.'
    }
    Write-Output 'Meter log viewer tests passed.'
} finally {
    $safeRoot = [IO.Path]::GetFullPath($env:TEMP).TrimEnd('\') + '\'
    if (-not $tempRoot.StartsWith($safeRoot, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Unexpected temporary test path.'
    }
    Remove-Item -LiteralPath $tempRoot -Recurse -Force -ErrorAction SilentlyContinue
}
