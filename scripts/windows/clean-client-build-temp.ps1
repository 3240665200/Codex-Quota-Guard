#requires -Version 5.1
[CmdletBinding(SupportsShouldProcess=$true)]
param()
$ErrorActionPreference='Stop'
$release='D:\BuildCache\pingche\artifacts\desktop\0.4.3'
$manifest=Get-Content -LiteralPath (Join-Path $release 'package-manifest.json') -Raw | ConvertFrom-Json
if ((Get-FileHash -LiteralPath (Join-Path $release $manifest.installer)).Hash -ne $manifest.sha256) { throw 'D-drive installer verification failed.' }
$active=@(Get-CimInstance Win32_Process | Where-Object {
    $_.Name -in 'cargo.exe','rustc.exe','makensis.exe' -or
    ($_.Name -eq 'node.exe' -and $_.CommandLine -match '(tauri\.js.*(build|bundle)|build-client\.mjs|prepare-meter\.mjs)')
})
if ($active.Count) { throw 'Wait for all desktop build processes to finish before cleaning.' }
$local=[Environment]::GetFolderPath('LocalApplicationData')
$temporary=Join-Path $local 'Temp'
$targets=@()
$tools=Join-Path $local 'tauri'
if (Test-Path -LiteralPath $tools) { $targets+=Get-Item -LiteralPath $tools -Force }
$targets+=@(Get-ChildItem -LiteralPath $temporary -Directory -Force | Where-Object {
    $_.Name -match '^cqg-package-smoke-[0-9a-f-]{36}$' -and $_.LastWriteTime -lt (Get-Date).AddMinutes(-5)
})
$before=(Get-PSDrive C).Free
foreach ($target in $targets) {
    $absolute=[IO.Path]::GetFullPath($target.FullName)
    $expected=($absolute -eq $tools) -or
        ([IO.Path]::GetDirectoryName($absolute) -eq $temporary -and $target.Name -match '^cqg-package-smoke-[0-9a-f-]{36}$')
    if (!$expected -or ($target.Attributes -band [IO.FileAttributes]::ReparsePoint)) { throw 'Unexpected cache path.' }
    $pending=New-Object 'System.Collections.Generic.Stack[string]';$pending.Push($absolute)
    while ($pending.Count) {
        foreach ($entry in Get-ChildItem -LiteralPath $pending.Pop() -Force) {
            if ($entry.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Linked cache requires manual inspection.' }
            if ($entry.PSIsContainer) { $pending.Push($entry.FullName) }
        }
    }
    $inUse=@(Get-CimInstance Win32_Process | Where-Object { $_.Name -eq 'msedgewebview2.exe' -and ([string]$_.CommandLine).Contains($absolute) })
    if ($inUse.Count) { Write-Warning ('In use; retained: '+$absolute);continue }
    if ($PSCmdlet.ShouldProcess($absolute,'Delete verified desktop build tool/probe cache')) {
        Remove-Item -LiteralPath $absolute -Recurse -Force
        Write-Host ('Removed: '+$absolute)
    }
}
Write-Host ('C-drive space recovered: {0:N2} GiB' -f (((Get-PSDrive C).Free-$before)/1GB))
Write-Host 'Installed programs, user data, Rust toolchains and system files were retained.'
