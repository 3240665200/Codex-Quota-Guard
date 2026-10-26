#requires -Version 5.1
[CmdletBinding(SupportsShouldProcess=$true, ConfirmImpact='Medium')]
param(
    [switch]$IncludeLegacyCaches,
    [switch]$OrganizeDist
)

$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\..')).Path.TrimEnd('\')
$legacyRoot = 'D:\BuildCache\pingche'
$plan = New-Object 'System.Collections.Generic.List[object]'
$skipped = New-Object 'System.Collections.Generic.List[string]'
$beforeFree = @{}
foreach ($drive in Get-PSDrive -PSProvider FileSystem) { $beforeFree[$drive.Name]=$drive.Free }
$tracked = @(& git -C $projectRoot -c core.quotepath=false ls-files)
if ($LASTEXITCODE -ne 0) { throw 'Cannot verify tracked project files. Run this script inside the original Git project.' }
$running = @(Get-CimInstance Win32_Process | Where-Object { $_.Name -in @('java.exe','javaw.exe','node.exe','cargo.exe','rustc.exe','makensis.exe','quota-guard-meter.exe','codex-quota-guard.exe') })

function Add-CleanupTarget([string]$Path,[string]$AllowedRoot) {
    $absolute=[IO.Path]::GetFullPath($Path).TrimEnd('\')
    $allowed=[IO.Path]::GetFullPath($AllowedRoot).TrimEnd('\')
    if (!$absolute.StartsWith($allowed+'\',[StringComparison]::OrdinalIgnoreCase) -or $absolute -eq $projectRoot -or $absolute -eq $legacyRoot) {
        throw "Unsafe cleanup target: $absolute"
    }
    $entry=Get-Item -LiteralPath $absolute -Force -ErrorAction SilentlyContinue
    if ($null -eq $entry) { return }
    if (@($plan | Where-Object Path -eq $absolute).Count) { return }
    foreach ($process in $running) {
        if (([string]$process.CommandLine).IndexOf($absolute,[StringComparison]::OrdinalIgnoreCase) -ge 0 -or
            ([string]$process.ExecutablePath).StartsWith($absolute+'\',[StringComparison]::OrdinalIgnoreCase)) {
            $skipped.Add("In use: $absolute");return
        }
    }
    if ($absolute.StartsWith($projectRoot+'\',[StringComparison]::OrdinalIgnoreCase)) {
        $relative=$absolute.Substring($projectRoot.Length+1).Replace('\','/')
        if (@($tracked | Where-Object { $_ -eq $relative -or $_.StartsWith($relative+'/',[StringComparison]::OrdinalIgnoreCase) }).Count) {
            $skipped.Add("Contains tracked source: $absolute");return
        }
    }
    $link=($entry.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0
    $parent=$entry.Parent
    if (!$parent) { $parent=$entry.Directory }
    while ($null -ne $parent) {
        if (($parent.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { throw "Linked ancestor requires manual review: $absolute" }
        $parent=$parent.Parent
    }
    $files=@();$nestedLinks=@()
    if (!$link) {
        if ($entry.PSIsContainer) {
            $pending=New-Object 'System.Collections.Generic.Stack[string]'
            $plainFiles=New-Object 'System.Collections.Generic.List[object]'
            $links=New-Object 'System.Collections.Generic.List[object]'
            $pending.Push($absolute)
            try {
                while ($pending.Count) {
                    foreach ($child in Get-ChildItem -LiteralPath $pending.Pop() -Force -ErrorAction Stop) {
                        if (($child.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { $links.Add($child) }
                        elseif ($child.PSIsContainer) { $pending.Push($child.FullName) }
                        else { $plainFiles.Add($child) }
                    }
                }
            } catch { $skipped.Add("Cannot fully inspect: $absolute");return }
            $nestedLinks=$links.ToArray();$files=$plainFiles.ToArray()
        } else { $files=@($entry) }
    }
    $plan.Add([pscustomobject]@{Path=$absolute;Directory=$entry.PSIsContainer;Link=$link;Files=$files;NestedLinks=$nestedLinks;Bytes=($files|Measure-Object Length -Sum).Sum})
}

# Rebuildable outputs only. Dependency installations, source, .git and .local are excluded.
foreach ($relative in @(
    '.cache','apps\admin\backend\target','apps\client\backend\target','apps\client\meter\target',
    'apps\client\frontend\src-tauri\target','apps\client\frontend\src-tauri\gen',
    'apps\admin\frontend\dist','apps\client\frontend\dist',
    'apps\admin\frontend\.umi','apps\admin\frontend\.umi-production','apps\admin\frontend\src\.umi','apps\admin\frontend\src\.umi-production',
    'apps\client\frontend\.build-cache','apps\admin\frontend\coverage','apps\client\frontend\coverage',
    'node_modules\.cache','apps\admin\frontend\node_modules\.cache','apps\client\frontend\node_modules\.vite'
)) { Add-CleanupTarget (Join-Path $projectRoot $relative) $projectRoot }

if ($IncludeLegacyCaches -and (Test-Path -LiteralPath $legacyRoot -PathType Container)) {
    # Never select private, qa-private, meter-service, references or source backups.
    foreach ($name in @(
        'desktop-package-target','target','meter-target','meter-target-20261025','cache',
        'authenticator-import-backend','quota-layout-backend','restriction-backend','usd-backend',
        'proxy-chain-root-target','proxy-chain-target','proxy-subscription-target',
        'docker-build-v0.3.18-20261015','docker-build-v0.3.19-20261015',
        'publish-v0.3.20-20261016','readme-preview-036-20261012','ui-preview','web-backend-run','usd-research',
        'proxy-chain-runtime-fixture','proxy-qa-20261016','qa','tmp',
        'release-0.3.28','release-0.3.29','release-0.3.30','release-0.3.31',
        'release-0.4.0','release-0.4.1','release-0.4.2',
        'release-v0.2.20-2961e33b8baa4a01b75b22a6c5f1a379','release-v0.3.22','releases','server-0.4.0','server-0.4.2'
    )) { Add-CleanupTarget (Join-Path $legacyRoot $name) $legacyRoot }
    # These are old immutable copies used by the local launcher, not the live database.
    $runtime=Join-Path $legacyRoot 'runtime'
    if (Test-Path -LiteralPath $runtime) {
        foreach ($jar in Get-ChildItem -LiteralPath $runtime -File -Filter '*.jar') { Add-CleanupTarget $jar.FullName $legacyRoot }
    }
    $oldDesktop=Join-Path $legacyRoot 'release-0.4.3\desktop\Codex Quota Guard_0.4.3_x64-setup.exe'
    $keptDesktop=Join-Path $projectRoot 'dist\desktop\0.4.3\Codex Quota Guard_0.4.3_x64-setup.exe'
    if ((Test-Path -LiteralPath $oldDesktop) -and (Test-Path -LiteralPath $keptDesktop) -and
        (Get-FileHash -LiteralPath $oldDesktop).Hash -eq (Get-FileHash -LiteralPath $keptDesktop).Hash) {
        Add-CleanupTarget (Join-Path $legacyRoot 'release-0.4.3') $legacyRoot
    }
}

if ($OrganizeDist) {
    $dist=Join-Path $projectRoot 'dist'
    # Require both retained releases before removing any legacy packaging clutter.
    foreach ($required in @('desktop\0.4.3\package-manifest.json','backend\0.4.2\package-manifest.json')) {
        if (!(Test-Path -LiteralPath (Join-Path $dist $required) -PathType Leaf)) { throw "Retained release missing: $required" }
    }
    $desktopManifest=Get-Content -LiteralPath (Join-Path $dist 'desktop\0.4.3\package-manifest.json') -Raw | ConvertFrom-Json
    $desktopFile=Join-Path $dist ('desktop\0.4.3\'+$desktopManifest.installer)
    if ((Get-FileHash -LiteralPath $desktopFile).Hash -ne $desktopManifest.sha256) { throw 'Retained desktop package hash mismatch' }
    $backendManifest=Get-Content -LiteralPath (Join-Path $dist 'backend\0.4.2\package-manifest.json') -Raw | ConvertFrom-Json
    if ((Get-FileHash -LiteralPath (Join-Path $dist 'backend\0.4.2\backend.jar')).Hash -ne $backendManifest.backendSha256) { throw 'Retained backend package hash mismatch' }
    $webRoot=[IO.Path]::GetFullPath((Join-Path $dist 'backend\0.4.2\web'))
    foreach ($asset in $backendManifest.web) {
        $assetPath=[IO.Path]::GetFullPath((Join-Path $webRoot $asset.path))
        if (!$assetPath.StartsWith($webRoot+'\',[StringComparison]::OrdinalIgnoreCase) -or
            (Get-FileHash -LiteralPath $assetPath).Hash -ne $asset.sha256) { throw 'Retained web assets failed verification' }
    }
    foreach ($entry in Get-ChildItem -LiteralPath $dist -Force) {
        if ($entry.Name -in @('desktop','backend','README.md')) { continue }
        if ($entry.Name -eq 'private-backups') { $skipped.Add("Backups retained: $($entry.FullName)");continue }
        Add-CleanupTarget $entry.FullName $dist
    }
}

$total=($plan | Measure-Object Bytes -Sum).Sum
Write-Host ("Rebuildable data selected: {0:N2} GiB across {1} targets." -f ($total/1GB),$plan.Count)
if ($WhatIfPreference) {
    foreach ($item in $plan) { Write-Host ('  '+$item.Path) }
    Write-Host 'PREVIEW ONLY. No files were changed.'
    foreach ($reason in $skipped) { Write-Warning $reason }
    return
}

$backupRoot=Join-Path $projectRoot ('.local\backups\cache-cleanup-'+(Get-Date -Format 'yyyyMMdd-HHmmss'))
$backupCount=0;$deleted=0;$index=0;$failed=New-Object 'System.Collections.Generic.List[string]'
foreach ($item in $plan) {
    $index++
    Write-Progress -Activity 'Cleaning project caches' -Status $item.Path -PercentComplete ([int](100*$index/$plan.Count))
    if (!$PSCmdlet.ShouldProcess($item.Path,'Remove rebuildable project cache')) { continue }
    try {
        # Preserve credentials, statistics journals and database snapshots found among old test outputs.
        foreach ($file in $item.Files | Where-Object { $_.Name -match '(?i)((credential|outbox|backup|journal|pairing|config|state|owner).*\.(json|jsonl|bak)$|^auth\.json$|^\.env$|\.pem$|\.key$|\.clixml$|\.dump$|\.sqlite\d?(?:-wal|-shm)?$|\.db(?:-wal|-shm)?$|\.properties$)' }) {
            $relative=$file.FullName.Substring(([IO.Path]::GetPathRoot($file.FullName)).Length)
            $destination=Join-Path (Join-Path $backupRoot ([IO.Path]::GetPathRoot($file.FullName).Substring(0,1))) $relative
            New-Item -ItemType Directory -Path ([IO.Path]::GetDirectoryName($destination)) -Force | Out-Null
            Copy-Item -LiteralPath $file.FullName -Destination $destination
            Set-Acl -LiteralPath $destination -AclObject (Get-Acl -LiteralPath $file.FullName)
            if ((Get-FileHash -LiteralPath $destination).Hash -ne (Get-FileHash -LiteralPath $file.FullName).Hash) { throw 'Backup hash mismatch' }
            $backupCount++
        }
        if ($OrganizeDist -and $item.Path.StartsWith((Join-Path $projectRoot 'dist')+'\',[StringComparison]::OrdinalIgnoreCase) -and !$item.Directory) {
            if ([IO.Path]::GetFileName($item.Path) -match '(?i)(completion|verification|cleanup-result|reset-consumption.*\.log|desktop-0\.4\.3-package\.log)') {
                $reportDirectory=Join-Path $projectRoot '.local\reports\legacy-dist'
                New-Item -ItemType Directory -Path $reportDirectory -Force | Out-Null
                Copy-Item -LiteralPath $item.Path -Destination $reportDirectory -Force
            }
            if ([IO.Path]::GetFileName($item.Path) -eq 'ssh-release-0.4.0.mjs') {
                $tools=Join-Path $projectRoot '.local\tools';New-Item -ItemType Directory -Path $tools -Force | Out-Null
                Copy-Item -LiteralPath $item.Path -Destination (Join-Path $tools 'ssh-mcp.mjs') -Force
            }
        }
        # Unlink nested links without ever traversing their targets.
        foreach ($link in $item.NestedLinks) {
            if ($link.PSIsContainer) { [IO.Directory]::Delete($link.FullName,$false) } else { Remove-Item -LiteralPath $link.FullName -Force }
        }
        if ($item.Link -and $item.Directory) { [IO.Directory]::Delete($item.Path,$false) }
        elseif ($item.Directory) { Remove-Item -LiteralPath $item.Path -Recurse -Force }
        else { Remove-Item -LiteralPath $item.Path -Force }
        $deleted++
    } catch { $failed.Add($item.Path+': '+$_.Exception.Message);Write-Warning ('Skipped: '+$item.Path) }
}
Write-Progress -Activity 'Cleaning project caches' -Completed

$freed=@{}
foreach ($drive in Get-PSDrive -PSProvider FileSystem) {
    if ($beforeFree.ContainsKey($drive.Name)) { $freed[$drive.Name]=[math]::Round(($drive.Free-$beforeFree[$drive.Name])/1GB,2) }
}
$summary=[ordered]@{completedAt=[DateTime]::UtcNow.ToString('o');deletedTargets=$deleted;preservedFiles=$backupCount;freedGiB=$freed;skipped=$skipped.ToArray();failed=$failed.ToArray()}
$reportRoot=Join-Path $projectRoot '.local\reports';New-Item -ItemType Directory -Path $reportRoot -Force | Out-Null
[IO.File]::WriteAllText((Join-Path $reportRoot 'cache-cleanup-result.json'),($summary|ConvertTo-Json -Depth 5),[Text.UTF8Encoding]::new($false))
Write-Host ($summary|ConvertTo-Json -Depth 5)
Write-Host 'Source, installed desktop/service, dependencies, current releases and private runtime configuration were retained.'
if ($failed.Count) { exit 1 }
