#requires -Version 5.1
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot '..\configure-cli-adapter.ps1')
function Assert-Equal($Actual,$Expected) { if ($Actual -ne $Expected) { throw "Expected $Expected, got $Actual" } }
$fixture=Join-Path ([IO.Path]::GetTempPath()) ('cqg-cli-adapter-'+[Guid]::NewGuid().ToString('N'))
$sdk=Join-Path $fixture '@openai\codex-sdk'
$binary=Join-Path $fixture 'meter with spaces\quota-guard-meter.exe'
New-Item -ItemType Directory -Path (Join-Path $sdk 'dist'),(Join-Path $fixture '@openai\codex-win32-x64\vendor\bin'),(Split-Path $binary) -Force | Out-Null
[IO.File]::WriteAllText($binary,'fixture')
[IO.File]::WriteAllText((Join-Path $fixture '@openai\codex-win32-x64\vendor\bin\codex.exe'),'fixture')
[IO.File]::WriteAllText((Join-Path $sdk 'dist\index.js'),'export class Codex {}')
$original=@{name='@openai/codex-sdk';version='0.162.1';exports=@{'.'=@{import='./dist/index.js';types='./dist/index.d.ts'}};custom='preserve-me'}
$file=Join-Path $sdk 'package.json'
[IO.File]::WriteAllText($file,($original|ConvertTo-Json -Depth 8))
try {
    Assert-Equal (Set-CodexSdkAdapter $sdk $binary 'Install') 'CLI_ADAPTER_INSTALLED'
    $saved=Get-Content -LiteralPath $file -Raw|ConvertFrom-Json
    Assert-Equal $saved.exports.PSObject.Properties['.'].Value.import './quota-guard-adapter.mjs'
    Assert-Equal $saved.custom 'preserve-me'
    $source=[IO.File]::ReadAllText((Join-Path $sdk 'quota-guard-adapter.mjs'))
    Assert-Equal ($source.Contains('__CQG_')) $false
    Assert-Equal (Set-CodexSdkAdapter $sdk $binary 'Install') 'CLI_ADAPTER_INSTALLED'
    Assert-Equal (Set-CodexSdkAdapter $sdk $binary 'Uninstall') 'CLI_ADAPTER_REMOVED'
    $saved=Get-Content -LiteralPath $file -Raw|ConvertFrom-Json
    Assert-Equal $saved.exports.PSObject.Properties['.'].Value.import './dist/index.js'
    Assert-Equal $saved.custom 'preserve-me'
    Assert-Equal (Test-Path -LiteralPath (Join-Path $sdk 'quota-guard-adapter.mjs')) $false
    # An SDK upgrade receives a fresh reversible registration, never an old package restore.
    Assert-Equal (Set-CodexSdkAdapter $sdk $binary 'Install') 'CLI_ADAPTER_INSTALLED'
    $original.version='0.164.0';[IO.File]::WriteAllText($file,($original|ConvertTo-Json -Depth 8))
    Assert-Equal (Set-CodexSdkAdapter $sdk $binary 'Install') 'CLI_ADAPTER_INSTALLED'
    Assert-Equal (Set-CodexSdkAdapter $sdk $binary 'Uninstall') 'CLI_ADAPTER_REMOVED'
    $saved=Get-Content -LiteralPath $file -Raw|ConvertFrom-Json
    Assert-Equal $saved.version '0.164.0'
    # Refuse a pre-existing unrelated entry without changing the manifest.
    [IO.File]::WriteAllText((Join-Path $sdk 'quota-guard-adapter.mjs'),'unrelated')
    $before=[IO.File]::ReadAllText($file);$refused=$false
    try { Set-CodexSdkAdapter $sdk $binary 'Install'|Out-Null } catch { $refused=$true }
    Assert-Equal $refused $true
    Assert-Equal ([IO.File]::ReadAllText($file)) $before
    Write-Host 'CLI adapter install, repeat, SDK upgrade, restore and collision checks passed.'
} finally {
    $absolute=[IO.Path]::GetFullPath($fixture)
    $expected=[IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\')+'\cqg-cli-adapter-'
    if (!$absolute.StartsWith($expected,[StringComparison]::OrdinalIgnoreCase)) { throw 'Fixture cleanup path invalid' }
    Remove-Item -LiteralPath $absolute -Recurse -Force
}
