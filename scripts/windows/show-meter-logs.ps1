#requires -Version 5.1
[CmdletBinding()]
param(
    [ValidateRange(1, 500)][int]$Tail = 50,
    [switch]$ErrorsOnly,
    [string]$LogDirectory = (Join-Path $env:ProgramData 'CodexQuotaGuard\Meter\logs')
)

$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath($LogDirectory)
if (-not (Test-Path -LiteralPath $root -PathType Container)) {
    throw '计费服务日志目录不存在；请确认已安装并启动带日志功能的新版 Rust 服务。'
}

$files = @()
for ($index = 5; $index -ge 1; $index--) {
    $files += Join-Path $root "meter.$index.jsonl"
}
$files += Join-Path $root 'meter.jsonl'
$allowedKinds = @('SERVICE_STARTED', 'SERVICE_STOPPED', 'BASELINE_READY',
    'UNPAIRED', 'USAGE_REPORTED', 'HEARTBEAT', 'ERROR', 'STATUS_CHANGE')
$allowedStates = @('BASELINE_READY', 'UNPAIRED', 'UP_TO_DATE', 'REPORTED', 'ERROR')
$recent = [System.Collections.Generic.Queue[object]]::new()
$skipped = 0
$found = $false

foreach ($path in $files) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { continue }
    $found = $true
    try {
        $lines = Get-Content -LiteralPath $path -ErrorAction Stop
    } catch {
        throw '无法读取计费服务日志；请以管理员身份运行 PowerShell。'
    }
    foreach ($line in $lines) {
        if (-not $line -or $line.Length -gt 4096) { $skipped++; continue }
        try {
            $entry = $line | ConvertFrom-Json -ErrorAction Stop
            $kind = [string]$entry.kind
            $timestamp = [long]$entry.atEpochSeconds
            if ($kind -notin $allowedKinds -or $timestamp -le 0) { throw 'invalid entry' }
            if ($ErrorsOnly -and $kind -ne 'ERROR') { continue }
            $state = [string]$entry.state
            if ($state -notin $allowedStates) { $state = '' }
            $errorCode = [string]$entry.errorCode
            if ($errorCode -notmatch '^[A-Z_]{1,64}$') { $errorCode = '' }
            $count = [long]$entry.reportedEvents
            $tokens = [long]$entry.reportedTokens
            if ($count -lt 0 -or $tokens -lt 0) { throw 'invalid count' }
            $when = [DateTimeOffset]::FromUnixTimeSeconds($timestamp).ToLocalTime()
            $recent.Enqueue([pscustomobject]@{
                Time = $when.ToString('yyyy-MM-dd HH:mm:ss zzz')
                Kind = $kind
                State = $state
                Events = $count
                Tokens = $tokens
                Heartbeat = [bool]$entry.heartbeatSent
                ErrorCode = $errorCode
            })
            if ($recent.Count -gt $Tail) { $null = $recent.Dequeue() }
        } catch {
            $skipped++
        }
    }
}

if (-not $found) { Write-Warning '尚无计费服务日志。' }
if ($skipped -gt 0) { Write-Warning "已跳过 $skipped 条损坏或不支持的日志记录。" }
$recent.ToArray()
