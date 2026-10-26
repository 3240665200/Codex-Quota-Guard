#requires -Version 5.1
[CmdletBinding()]
param(
    [ValidateSet('', 'Detect', 'Validate', 'CheckDestination', 'Migrate', 'Complete', 'HashPayload')][string]$Operation = '',
    [string]$ResultFile = '', [string]$RequestFile = '', [string]$DestinationDirectory = '',
    [string]$ExpectedDirectory = '', [string]$ExpectedVersion = '',
    [int]$ExpectedView = 64, [string]$Version = '',
    [string]$PayloadHash = '', [string]$PayloadPath = '',
    [switch]$Confirmed, [switch]$Silent, [switch]$Passive
)
$ErrorActionPreference = 'Stop'
$script:CqgProduct = 'Codex Quota Guard'
$script:CqgPublisher = 'codexquotaguard'
$script:CqgBinary = 'codex-quota-guard.exe'
$script:CqgUninstallKey = 'Software\Microsoft\Windows\CurrentVersion\Uninstall\Codex Quota Guard'
$script:CqgRememberedKey = 'Software\codexquotaguard\Codex Quota Guard'
$script:CqgTargetVersion = $Version
$script:CqgTargetPayloadHash = $PayloadHash
# Verified 0.4.0 NSIS payload. Its only difference from the verified build is
# Tauri's documented UNK -> NSS bundle marker; see the 0.4.1 release notes.
$script:CqgRecoveryPayloads = @{
    '0.4.0' = '90c3a0cd9e1802b9d23c49c2a6a09220d2a406a175618196cb50c22d971f5acb'
}

function ConvertTo-CqgDirectory([string]$Value) {
    $directory = $Value.Trim()
    if ($directory.StartsWith('"') -and $directory.EndsWith('"') -and $directory.Length -gt 2) {
        $directory = $directory.Substring(1, $directory.Length - 2)
    }
    if ($directory -notmatch '^[A-Za-z]:\\' -or $directory.Length -gt 240 -or
        $directory.Substring(2) -match '[\x00-\x1f:"<>|?*/]' -or
        $directory -match '\\(\.|\.\.)(\\|$)' -or
        $directory -match '\\[^\\]*[ .](\\|$)' -or
        $directory -match '\\(CON|PRN|AUX|NUL|CONIN\$|CONOUT\$|COM[0-9]|LPT[0-9])(\.|\\|$)') {
        throw '安装记录中的目录无效。请先修复旧版本安装记录。'
    }
    $directory = [IO.Path]::GetFullPath($directory).TrimEnd('\')
    if ($directory -ieq ([IO.Path]::GetPathRoot($directory).TrimEnd('\'))) {
        throw '不能把磁盘根目录作为旧版本安装目录。'
    }
    $windowsDirectory = [Environment]::GetFolderPath('Windows').TrimEnd('\')
    if ($windowsDirectory -and ($directory -ieq $windowsDirectory -or
        $directory.StartsWith($windowsDirectory + '\', [StringComparison]::OrdinalIgnoreCase))) {
        throw '不能把 Windows 系统目录作为安装目录。'
    }
    $sharedRoots = @('ProgramFiles', 'ProgramFilesX86', 'CommonApplicationData',
        'UserProfile', 'ApplicationData', 'LocalApplicationData') | ForEach-Object {
        [Environment]::GetFolderPath($_).TrimEnd('\')
    }
    $sharedRoots += Split-Path -Parent ([Environment]::GetFolderPath('UserProfile'))
    foreach ($rootDirectory in $sharedRoots) {
        if ($rootDirectory -and $directory -ieq $rootDirectory) {
            throw '不能把系统或用户共享根目录作为安装目录。'
        }
    }
    return $directory
}

function Assert-CqgDirectoryTree([string]$Directory) {
    $cursor = $Directory
    while ($cursor) {
        if (Test-Path -LiteralPath $cursor) {
            $item = Get-Item -LiteralPath $cursor -Force
            if (-not $item.PSIsContainer -or ($item.Attributes -band [IO.FileAttributes]::ReparsePoint)) {
                throw '安装目录或其父目录不是普通目录，不能安全升级。'
            }
        }
        $parent = Split-Path -Parent $cursor
        if ($parent -eq $cursor) { break }
        $cursor = $parent
    }
}

function Get-CqgFileSha256([string]$Path) {
    # The 32-bit NSIS compiler may launch PowerShell without Utility-module
    # auto-loading. Hash through .NET in both build-time and runtime checks.
    $algorithm = [Security.Cryptography.SHA256]::Create()
    $stream = $null
    try {
        $stream = [IO.File]::OpenRead($Path)
        return [BitConverter]::ToString($algorithm.ComputeHash($stream)).Replace('-', '').ToLowerInvariant()
    } finally { if ($stream) { $stream.Dispose() }; $algorithm.Dispose() }
}

function Get-CqgExecutableMetadata([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { return $null }
    $item = Get-Item -LiteralPath $Path -Force
    if ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) {
        throw '旧版本程序文件不是普通文件，不能安全升级。'
    }
    $info = [Diagnostics.FileVersionInfo]::GetVersionInfo($Path)
    return [pscustomobject]@{
        ProductName = $info.ProductName; Version = $info.ProductVersion; IsRegularFile = $true
        Publisher = $info.CompanyName; Sha256 = Get-CqgFileSha256 $Path
    }
}

function Test-CqgInterruptedPayload($Record, $Metadata) {
    # Recovery never executes the mismatched binary or an old uninstaller.
    # Only current-user, newer, byte-for-byte verified payloads are accepted.
    if ($Record.Hive -cne 'HKCU' -or $Metadata.Publisher -cne $script:CqgPublisher -or
        $Record.Version -notmatch '^\d+\.\d+\.\d+$' -or $Metadata.Version -notmatch '^\d+\.\d+\.\d+$' -or
        $script:CqgTargetVersion -notmatch '^\d+\.\d+\.\d+$') { return $false }
    if ([version]$Metadata.Version -le [version]$Record.Version -or
        [version]$Metadata.Version -gt [version]$script:CqgTargetVersion) { return $false }
    $expected = if ($Metadata.Version -ceq $script:CqgTargetVersion) { $script:CqgTargetPayloadHash }
        else { $script:CqgRecoveryPayloads[$Metadata.Version] }
    return $expected -match '^[0-9a-f]{64}$' -and $Metadata.Sha256 -ceq $expected
}

function Read-CqgInstalledRecords {
    $records = @()
    $views = @(64, 32)
    if (-not [Environment]::Is64BitOperatingSystem) { $views = @(32) }
    foreach ($hive in @('HKCU', 'HKLM')) {
        # HKCU\Software is shared on WOW64: the 32-bit handle aliases the same
        # uninstall entry. Only HKLM has independent 32/64-bit storage here.
        $hiveViews = if ($hive -eq 'HKCU') { @($views[0]) } else { $views }
        foreach ($view in $hiveViews) {
            $base = $null; $arp = $null; $remembered = $null
            try {
                $registryHive = if ($hive -eq 'HKCU') { [Microsoft.Win32.RegistryHive]::CurrentUser } else { [Microsoft.Win32.RegistryHive]::LocalMachine }
                $registryView = if ($view -eq 64) { [Microsoft.Win32.RegistryView]::Registry64 } else { [Microsoft.Win32.RegistryView]::Registry32 }
                $base = [Microsoft.Win32.RegistryKey]::OpenBaseKey($registryHive, $registryView)
                $arp = $base.OpenSubKey($script:CqgUninstallKey, $false)
                $remembered = $base.OpenSubKey($script:CqgRememberedKey, $false)
                $record = [ordered]@{ Hive = $hive; View = $view; ArpPresent = ($null -ne $arp -and $arp.GetValueNames().Count -gt 0) }
                foreach ($mapping in @(@('DisplayName', 'DisplayName'), @('Publisher', 'Publisher'),
                    @('DisplayVersion', 'Version'), @('InstallLocation', 'InstallLocation'),
                    @('MainBinaryName', 'MainBinaryName'), @('UninstallString', 'UninstallString'))) {
                    $value = if ($arp) { $arp.GetValue($mapping[0], $null, [Microsoft.Win32.RegistryValueOptions]::DoNotExpandEnvironmentNames) } else { $null }
                    if ($null -ne $value -and $value -isnot [string]) { throw '旧版本注册信息类型无效。' }
                    $record[$mapping[1]] = [string]$value
                }
                $rememberedPath = if ($remembered) { $remembered.GetValue('', $null, [Microsoft.Win32.RegistryValueOptions]::DoNotExpandEnvironmentNames) } else { $null }
                if ($null -ne $rememberedPath -and $rememberedPath -isnot [string]) { throw '旧版本目录记忆类型无效。' }
                $record.RememberedPath = [string]$rememberedPath
                $records += [pscustomobject]$record
                if ($hive -eq 'HKLM') {
                    # Preserve the upstream MSI/foreign-installer conflict check,
                    # but never execute a registry-supplied uninstall command.
                    $parent = $base.OpenSubKey('Software\Microsoft\Windows\CurrentVersion\Uninstall', $false)
                    if ($parent) {
                        try {
                            foreach ($keyName in $parent.GetSubKeyNames()) {
                                if ($keyName -ceq $script:CqgProduct) { continue }
                                $other = $parent.OpenSubKey($keyName, $false)
                                if (-not $other) { continue }
                                try {
                                    if ($other.GetValue('DisplayName') -ceq $script:CqgProduct -and
                                        $other.GetValue('Publisher') -ceq $script:CqgPublisher) {
                                        $records += [pscustomobject]@{ Kind = 'foreign'; Hive = $hive; View = $view; ArpPresent = $true }
                                    }
                                } finally { $other.Dispose() }
                            }
                        } finally { $parent.Dispose() }
                    }
                }
            } finally {
                if ($remembered) { $remembered.Dispose() }
                if ($arp) { $arp.Dispose() }
                if ($base) { $base.Dispose() }
            }
        }
    }
    return $records
}

function Assert-CqgRegisteredRecord($Record, [string]$BinaryVersion = '') {
    if ($Record.Hive -notin @('HKCU', 'HKLM') -or $Record.View -notin @(32, 64) -or
        $Record.DisplayName -cne $script:CqgProduct -or $Record.Publisher -cne $script:CqgPublisher -or
        $Record.MainBinaryName -cne $script:CqgBinary -or
        $Record.Version -notmatch '^\d+\.\d+\.\d+(?:[-+][0-9A-Za-z.-]+)?$') {
        throw '旧版本产品身份或版本记录不完整，不能自动升级。'
    }
    $directory = ConvertTo-CqgDirectory $Record.InstallLocation
    if ($Record.RememberedPath -and (ConvertTo-CqgDirectory $Record.RememberedPath) -ine $directory) {
        throw '旧版本安装目录与记忆目录不一致，不能自动升级。'
    }
    Assert-CqgDirectoryTree $directory
    $uninstaller = Join-Path $directory 'uninstall.exe'
    if ($Record.UninstallString -ine ('"' + $uninstaller + '"') -and $Record.UninstallString -ine $uninstaller) {
        throw '旧版本卸载记录不是本产品目录中的固定卸载程序。'
    }
    if (-not $BinaryVersion) { $BinaryVersion = $Record.Version }
    foreach ($executable in @($uninstaller, (Join-Path $directory $script:CqgBinary))) {
        $metadata = Get-CqgExecutableMetadata $executable
        if (-not $metadata -or -not $metadata.IsRegularFile -or $metadata.ProductName -cne $script:CqgProduct) {
            throw '旧版本程序文件缺失或产品版本不匹配，不能自动升级。'
        }
        if ($metadata.Version -cne $BinaryVersion -and
            ($executable -ieq $uninstaller -or -not (Test-CqgInterruptedPayload $Record $metadata))) {
            throw '旧版本程序文件缺失或产品版本不匹配，不能自动升级。'
        }
    }
    return $directory
}

function Resolve-CqgUpgradePlan([object[]]$Records) {
    $registered = @(); $repairs = @()
    foreach ($record in $Records) {
        if ($record.Kind -eq 'foreign') {
            throw '检测到匹配的 MSI 或其他安装器旧版。请先从 Windows 已安装应用卸载旧版，再重新安装。'
        }
        if ($record.ArpPresent) {
            $directory = Assert-CqgRegisteredRecord $record
            $main = Get-CqgExecutableMetadata (Join-Path $directory $script:CqgBinary)
            $mode = if ($record.Hive -eq 'HKLM') { 'migrate' } elseif ($main.Version -cne $record.Version) { 'repair' } else { 'upgrade' }
            $registered += [pscustomobject]@{ Hive = $record.Hive; View = $record.View; Directory = $directory; Version = $main.Version; Mode = $mode }
        } elseif ($record.RememberedPath) {
            $directory = ConvertTo-CqgDirectory $record.RememberedPath
            $metadata = Get-CqgExecutableMetadata (Join-Path $directory $script:CqgBinary)
            if ($metadata) {
                Assert-CqgDirectoryTree $directory
                if (-not $metadata.IsRegularFile -or $metadata.ProductName -cne $script:CqgProduct -or
                    $metadata.Version -notmatch '^\d+\.\d+\.\d+(?:[-+][0-9A-Za-z.-]+)?$') {
                    throw '旧目录残留程序的产品身份无效。'
                }
                $repairs += [pscustomobject]@{ Hive = $record.Hive; View = $record.View; Directory = $directory; Version = $metadata.Version; Mode = 'repair' }
            }
        }
    }
    # Remembered paths left by an old uninstaller are not second ARP entries.
    $candidates = @($registered)
    foreach ($repair in $repairs) {
        if (-not @($registered | Where-Object { $_.Directory -ieq $repair.Directory }).Count) {
            if ($repair.Hive -eq 'HKLM') {
                throw '机器范围旧版仍有程序但缺少完整卸载记录。请先修复或卸载旧版后重试。'
            }
            $candidates += $repair
        }
    }
    if (-not $candidates.Count) { return [pscustomobject]@{ Mode = 'fresh'; Hive = ''; View = 64; Directory = ''; Version = ''; Views = @() } }
    $first = @($candidates | Sort-Object View -Descending)[0]
    foreach ($candidate in $candidates) {
        if ($candidate.Hive -ne $first.Hive -or $candidate.Directory -ine $first.Directory -or
            $candidate.Version -ne $first.Version -or $candidate.Mode -ne $first.Mode) {
            throw '检测到多份安装、跨范围冲突或不同版本记录。请先处理旧版本冲突后重试，安装器不会创建第二份安装。'
        }
    }
    return [pscustomobject]@{ Mode = $first.Mode; Hive = $first.Hive; View = $first.View; Directory = $first.Directory; Version = $first.Version; Views = @($candidates | ForEach-Object { $_.View } | Select-Object -Unique) }
}

function Test-CqgDestinationWritable([string]$Directory) {
    Assert-CqgDirectoryTree $Directory
    $created = $false; $probe = $null
    try {
        if (-not (Test-Path -LiteralPath $Directory -PathType Container)) {
            [IO.Directory]::CreateDirectory($Directory) | Out-Null
            $created = $true
        }
        $probe = Join-Path $Directory ('.cqg-install-check-' + [Guid]::NewGuid().ToString('N'))
        $stream = [IO.File]::Open($probe, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::None)
        $stream.Dispose()
        return $true
    } catch { return $false } finally {
        if ($probe -and [IO.File]::Exists($probe)) { [IO.File]::Delete($probe) }
        if ($created -and [IO.Directory]::Exists($Directory) -and -not [IO.Directory]::EnumerateFileSystemEntries($Directory).GetEnumerator().MoveNext()) {
            [IO.Directory]::Delete($Directory, $false)
        }
    }
}

function Invoke-CqgUninstaller([string]$Executable, [string]$Arguments, [bool]$Elevate) {
    # FileName is a validated fixed uninstall.exe. Arguments contain only /S
    # and the NSIS _?= directory, last and intentionally not quoted.
    $start = New-Object Diagnostics.ProcessStartInfo
    $start.FileName = $Executable
    $start.Arguments = $Arguments
    $start.WorkingDirectory = Split-Path -Parent $Executable
    $start.UseShellExecute = $true
    if ($Elevate) { $start.Verb = 'runas' }
    try {
        $process = [Diagnostics.Process]::Start($start)
        if (-not $process) { throw '无法启动旧版本卸载程序。' }
        try { $process.WaitForExit(); return $process.ExitCode } finally { $process.Dispose() }
    } catch { throw '旧版本卸载未完成。可能取消了 UAC 或 Windows 无法启动卸载程序。' }
}

function Test-CqgMigrationDestination([string]$ExpectedDirectory, [string]$ExpectedVersion, [int]$ExpectedView,
    [string]$DestinationDirectory) {
    $destination = ConvertTo-CqgDirectory $DestinationDirectory
    $plan = Resolve-CqgUpgradePlan -Records @(Read-CqgInstalledRecords)
    if ($plan.Mode -ne 'migrate' -or $plan.Hive -ne 'HKLM' -or $plan.View -ne $ExpectedView -or
        $plan.Directory -ine (ConvertTo-CqgDirectory $ExpectedDirectory) -or $plan.Version -ne $ExpectedVersion) {
        throw '旧版本安装记录已变化或没有可信机器安装。请重新打开安装包确认。'
    }
    if (-not (Test-CqgDestinationWritable $destination)) { throw '当前用户无法写入所选目标目录。请选择可写目录后重试。' }
    return $plan
}

function Invoke-CqgConfirmedMigration([string]$ExpectedDirectory, [string]$ExpectedVersion, [int]$ExpectedView,
    [string]$DestinationDirectory, [switch]$Confirmed, [switch]$Silent, [switch]$Passive) {
    if (-not $Confirmed -or $Silent -or $Passive) { throw '跨安装范围迁移需要在安装界面明确确认，不能静默或被动执行。' }
    $plan = Test-CqgMigrationDestination -ExpectedDirectory $ExpectedDirectory -ExpectedVersion $ExpectedVersion `
        -ExpectedView $ExpectedView -DestinationDirectory $DestinationDirectory
    $exitCode = Invoke-CqgUninstaller -Executable (Join-Path $plan.Directory 'uninstall.exe') `
        -Arguments ('/S _?=' + $plan.Directory) -Elevate $true
    if ($exitCode -ne 0) { throw '旧版本卸载返回失败，安装已停止，不会继续创建新安装。' }
    $remaining = @(Read-CqgInstalledRecords | Where-Object { $_.ArpPresent })
    if ($remaining.Count -or (Get-CqgExecutableMetadata (Join-Path $plan.Directory $script:CqgBinary))) {
        throw '旧版本卸载后仍有注册记录或主程序，安装已停止。'
    }
}

function Complete-CqgRegistryUpgrade([string]$Directory, [string]$Version) {
    $directory = ConvertTo-CqgDirectory $Directory
    $new = @(Read-CqgInstalledRecords | Where-Object { $_.Hive -eq 'HKCU' -and $_.View -eq 64 -and $_.ArpPresent })
    if ($new.Count -ne 1 -or $new[0].Version -ne $Version -or
        (Assert-CqgRegisteredRecord $new[0]) -ine $directory) {
        throw '新版本注册信息验证失败。请重新安装到原目录。'
    }
    # Never delete a supposed HKCU32 entry: it is the same shared HKCU entry
    # just written by this x64 installer, not a second installed copy.
}

function Write-CqgResult($Plan, [string]$Message = '') {
    $values = [ordered]@{ Mode = $Plan.Mode; Hive = $Plan.Hive; View = $Plan.View; Directory = $Plan.Directory; Version = $Plan.Version; Message = $Message }
    $lines = @('[Upgrade]')
    foreach ($entry in $values.GetEnumerator()) {
        $value = [string]$entry.Value
        if ($value -match '[\x00-\x1f]') { throw '安装检测结果包含无效字符。' }
        $lines += ($entry.Key + '=' + $value)
    }
    [IO.File]::WriteAllLines($ResultFile, $lines, [Text.Encoding]::Unicode)
}

function Read-CqgRequest([string]$Path) {
    $bytes = [IO.File]::ReadAllBytes($Path)
    if ($bytes.Length -gt 8192 -or $bytes.Length -lt 2 -or $bytes[0] -ne 255 -or $bytes[1] -ne 254) {
        throw '安装请求不是有效的 UTF-16 数据。'
    }
    $allowedKeys = @('DestinationDirectory', 'ExpectedDirectory', 'ExpectedVersion', 'ExpectedView')
    $result = @{}
    $header = $false
    foreach ($line in [IO.File]::ReadAllLines($Path, [Text.Encoding]::Unicode)) {
        if (-not $line) { continue }
        if (-not $header -and $line -ceq '[Request]') { $header = $true; continue }
        $separator = $line.IndexOf('=')
        if (-not $header -or $separator -lt 1) { throw '安装请求格式无效。' }
        $key = $line.Substring(0, $separator)
        $value = $line.Substring($separator + 1)
        if ($key -notin $allowedKeys -or $result.ContainsKey($key) -or $value -match '[\x00-\x1f]') {
            throw '安装请求包含未知或重复字段。'
        }
        if ($key -eq 'ExpectedView' -and $value -notin @('32', '64')) { throw '安装请求注册表视图无效。' }
        $result[$key] = $value
    }
    if (-not $header) { throw '安装请求缺少数据段。' }
    return $result
}

if ($Operation) {
    try {
        if (-not $ResultFile) { throw '安装检测结果路径缺失。' }
        if ($Operation -eq 'HashPayload') {
            $payload = Get-CqgExecutableMetadata $PayloadPath
            if (-not $payload -or $payload.Sha256 -notmatch '^[0-9a-f]{64}$') { throw '无法读取安装包主程序指纹。' }
            [IO.File]::WriteAllText($ResultFile, $payload.Sha256, [Text.Encoding]::ASCII)
            exit 0
        }
        if ($RequestFile) {
            $request = Read-CqgRequest $RequestFile
            $DestinationDirectory = $request.DestinationDirectory
            $ExpectedDirectory = $request.ExpectedDirectory
            $ExpectedVersion = $request.ExpectedVersion
            if ($request.ContainsKey('ExpectedView')) { $ExpectedView = [int]$request.ExpectedView }
        }
        if ($Operation -eq 'Detect') {
            $plan = Resolve-CqgUpgradePlan -Records @(Read-CqgInstalledRecords)
            if ($plan.Mode -eq 'migrate' -and ($Silent -or $Passive)) { throw '检测到机器范围旧版。请打开交互式安装界面确认迁移，不能静默创建第二份安装。' }
            Write-CqgResult $plan
        } elseif ($Operation -eq 'Validate') {
            $destination = ConvertTo-CqgDirectory $DestinationDirectory
            $plan = Resolve-CqgUpgradePlan -Records @(Read-CqgInstalledRecords)
            if ($plan.Mode -notin @('fresh', 'upgrade', 'repair') -or
                ($plan.Mode -ne 'fresh' -and $plan.Directory -ine $destination)) {
                throw '当前用户旧版或残留程序应在原目录升级/修复。请选择检测到的原安装目录。'
            }
            if (-not (Test-CqgDestinationWritable $destination)) { throw '当前用户无法写入所选目录。请选择可写目录后重试。' }
            Write-CqgResult $plan
        } elseif ($Operation -eq 'CheckDestination') {
            $plan = Test-CqgMigrationDestination -ExpectedDirectory $ExpectedDirectory -ExpectedVersion $ExpectedVersion `
                -ExpectedView $ExpectedView -DestinationDirectory $DestinationDirectory
            Write-CqgResult $plan
        } elseif ($Operation -eq 'Migrate') {
            Invoke-CqgConfirmedMigration -ExpectedDirectory $ExpectedDirectory -ExpectedVersion $ExpectedVersion -ExpectedView $ExpectedView `
                -DestinationDirectory $DestinationDirectory -Confirmed:$Confirmed -Silent:$Silent -Passive:$Passive
            Write-CqgResult ([pscustomobject]@{ Mode = 'fresh'; Hive = ''; View = 64; Directory = ''; Version = '' })
        } elseif ($Operation -eq 'Complete') {
            Complete-CqgRegistryUpgrade -Directory $DestinationDirectory -Version $Version
            Write-CqgResult ([pscustomobject]@{ Mode = 'complete'; Hive = 'HKCU'; View = 64; Directory = $DestinationDirectory; Version = $Version })
        }
        exit 0
    } catch {
        if ($Operation -eq 'HashPayload') { [Console]::Error.WriteLine($_.Exception.Message) }
        if ($ResultFile) { Write-CqgResult ([pscustomobject]@{ Mode = 'error'; Hive = ''; View = 64; Directory = ''; Version = '' }) $_.Exception.Message }
        exit 2
    }
}
