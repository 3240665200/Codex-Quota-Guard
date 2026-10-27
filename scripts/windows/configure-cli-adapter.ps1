#requires -Version 5.1
[CmdletBinding()]
param(
    [ValidateSet('Install','Uninstall')][string]$Operation='Install',
    [string]$MeterExecutable=(Join-Path $PSScriptRoot 'quota-guard-meter.exe'),
    [string]$SdkDirectory=(Join-Path ([Environment]::GetFolderPath('UserProfile')) '.codemoss\dependencies\codex-sdk\node_modules\@openai\codex-sdk')
)
$ErrorActionPreference='Stop'

function Write-CliAdapterFile([string]$Root,[string]$Name,[string]$Content) {
    $target=[IO.Path]::GetFullPath((Join-Path $Root $Name))
    if (!$target.StartsWith($Root.TrimEnd('\')+'\',[StringComparison]::OrdinalIgnoreCase)) { throw 'CLI_ADAPTER_PATH_INVALID' }
    $temporary=$target+'.cqg-'+[Guid]::NewGuid().ToString('N')
    [IO.File]::WriteAllText($temporary,$Content,[Text.UTF8Encoding]::new($false))
    try { Move-Item -LiteralPath $temporary -Destination $target -Force }
    finally { if (Test-Path -LiteralPath $temporary) { Remove-Item -LiteralPath $temporary } }
}

function Set-CodexSdkAdapter([string]$Directory,[string]$Executable,[string]$Action) {
    if (!(Test-Path -LiteralPath $Directory -PathType Container)) { return 'CLI_ADAPTER_NOT_APPLICABLE' }
    $root=(Resolve-Path -LiteralPath $Directory).Path
    $packageFile=Join-Path $root 'package.json'
    if (!(Test-Path -LiteralPath $packageFile -PathType Leaf) -or (Get-Item -LiteralPath $packageFile).Length -gt 131072) { throw 'CLI_ADAPTER_SDK_INVALID' }
    $package=Get-Content -LiteralPath $packageFile -Raw | ConvertFrom-Json
    if ($package.name -ne '@openai/codex-sdk') { throw 'CLI_ADAPTER_SDK_INVALID' }
    $export=$package.exports.PSObject.Properties['.'].Value
    if (!$export -or !$export.PSObject.Properties['import']) { return 'CLI_ADAPTER_SDK_UNSUPPORTED' }
    $adapterName='quota-guard-adapter.mjs'; $adapterImport='./'+$adapterName
    $backupFile=Join-Path $root '.quota-guard-adapter.json'
    $backup=$null
    if (Test-Path -LiteralPath $backupFile -PathType Leaf) {
        if ((Get-Item -LiteralPath $backupFile).Length -gt 8192) { throw 'CLI_ADAPTER_BACKUP_INVALID' }
        $backup=Get-Content -LiteralPath $backupFile -Raw | ConvertFrom-Json
    }
    if ($Action -eq 'Uninstall') {
        if ($export.import -ne $adapterImport) { return 'CLI_ADAPTER_ALREADY_REMOVED' }
        if (!$backup -or $backup.sdkVersion -ne $package.version) { throw 'CLI_ADAPTER_RESTORE_REQUIRED' }
        $export.import=[string]$backup.originalImport
        Write-CliAdapterFile $root 'package.json' ($package|ConvertTo-Json -Depth 32)
        # Only our two exact files are removed, after restoring the SDK entry point.
        Remove-Item -LiteralPath (Join-Path $root $adapterName),$backupFile -ErrorAction SilentlyContinue
        return 'CLI_ADAPTER_REMOVED'
    }
    if ($export.import -eq $adapterImport) {
        if (!$backup -or $backup.sdkVersion -ne $package.version) { throw 'CLI_ADAPTER_BACKUP_INVALID' }
        $original=[string]$backup.originalImport
    } else {
        $original=[string]$export.import
        if ((Test-Path -LiteralPath (Join-Path $root $adapterName)) -and !$backup) { throw 'CLI_ADAPTER_ENTRY_ALREADY_EXISTS' }
    }
    if (!$original.StartsWith('./') -or $original -eq $adapterImport) { throw 'CLI_ADAPTER_IMPORT_INVALID' }
    $entry=[IO.Path]::GetFullPath((Join-Path $root $original))
    if (!$entry.StartsWith($root.TrimEnd('\')+'\',[StringComparison]::OrdinalIgnoreCase) -or !(Test-Path -LiteralPath $entry -PathType Leaf)) { throw 'CLI_ADAPTER_IMPORT_INVALID' }
    $executableFile=(Resolve-Path -LiteralPath $Executable -ErrorAction Stop).Path
    $platformPackage=Join-Path (Split-Path $root) 'codex-win32-x64'
    $binaries=@(Get-ChildItem -LiteralPath $platformPackage -Recurse -Filter codex.exe -File -ErrorAction SilentlyContinue)
    if ($binaries.Count -ne 1) { throw 'CLI_ADAPTER_CODEX_EXECUTABLE_UNAVAILABLE' }
    $template=Join-Path $PSScriptRoot 'codex-sdk-adapter.mjs.template'
    if (!(Test-Path -LiteralPath $template)) { $template=Join-Path $PSScriptRoot '..\..\apps\client\meter\integrations\codex-sdk-adapter.mjs.template' }
    $configuration=@{meterExecutable=$executableFile;codexExecutable=$binaries[0].FullName}|ConvertTo-Json -Compress
    $source=[IO.File]::ReadAllText((Resolve-Path -LiteralPath $template).Path)
    $source=$source.Replace('"__CQG_ORIGINAL_IMPORT__"',($original|ConvertTo-Json -Compress)).Replace('"__CQG_CONFIGURATION__"',$configuration)
    $saved=@{sdkVersion=$package.version;originalImport=$original;adapterSchema=1}|ConvertTo-Json -Compress
    Write-CliAdapterFile $root '.quota-guard-adapter.json' $saved
    Write-CliAdapterFile $root $adapterName $source
    $export.import=$adapterImport
    Write-CliAdapterFile $root 'package.json' ($package|ConvertTo-Json -Depth 32)
    return 'CLI_ADAPTER_INSTALLED'
}

if ($MyInvocation.InvocationName -eq '.') { return }
try { Set-CodexSdkAdapter $SdkDirectory $MeterExecutable $Operation;exit 0 }
catch { [Console]::Error.WriteLine($_.Exception.Message);exit 1 }
