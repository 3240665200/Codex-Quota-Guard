#requires -Version 5.1
$ErrorActionPreference = 'Stop'
$testRoot = Join-Path ([IO.Path]::GetTempPath()) ("pingche-admin-wrapper-test-" + [Guid]::NewGuid().ToString('N') + "-O'Brien")
New-Item -ItemType Directory -Path $testRoot | Out-Null
$wrapper = Join-Path $testRoot 'start-admin-local-test.ps1'
Copy-Item -LiteralPath (Join-Path $PSScriptRoot '..\start-admin-local-test.ps1') -Destination $wrapper
@'
if ($env:PINGCHE_WRAPPER_TEST_FAILURE) { throw $env:PINGCHE_WRAPPER_TEST_FAILURE }
[pscustomobject]@{RunDirectory=(Join-Path $PSScriptRoot "run's directory");StatusPath='synthetic-status.json'}
'@ | Set-Content -LiteralPath (Join-Path $testRoot 'start-admin-preview.ps1') -Encoding UTF8
@'
param([string]$RunDirectory)
[IO.File]::WriteAllText((Join-Path $PSScriptRoot 'stop-argument.txt'), $RunDirectory)
'@ | Set-Content -LiteralPath (Join-Path $testRoot 'stop-admin-preview.ps1') -Encoding UTF8

$testState = @{BrowserCalls=[Collections.Generic.List[string]]::new();PauseCalls=0;HostMessages=[Collections.Generic.List[string]]::new()}
function Start-Process { param([string]$FilePath) $testState.BrowserCalls.Add($FilePath) }
function Read-Host { param([string]$Prompt) $testState.PauseCalls++; return '' }
function Write-Host { param([object]$Object) $testState.HostMessages.Add([string]$Object) }
function Assert-Case([bool]$Value,[string]$Message) { if (!$Value) { throw $Message } }

$previousFailure = [Environment]::GetEnvironmentVariable('PINGCHE_WRAPPER_TEST_FAILURE', 'Process')
try {
    [Environment]::SetEnvironmentVariable('PINGCHE_WRAPPER_TEST_FAILURE', $null, 'Process')
    & $wrapper
    Assert-Case ($testState.BrowserCalls.Count -eq 1 -and $testState.BrowserCalls[0] -eq 'http://localhost:8000/#/user/login') 'The default wrapper did not open the login page exactly once.'
    Assert-Case ($testState.PauseCalls -eq 1) 'The double-click wrapper did not retain its stop command window.'
    $stopMessage = @($testState.HostMessages | Where-Object { $_.StartsWith('Stop this run: ') })
    Assert-Case ($stopMessage.Count -eq 1) 'The wrapper did not show one stop command.'
    & ([scriptblock]::Create($stopMessage[0].Substring('Stop this run: '.Length)))
    $actualRun = [IO.File]::ReadAllText((Join-Path $testRoot 'stop-argument.txt'))
    Assert-Case ($actualRun -ceq (Join-Path $testRoot "run's directory")) 'The copied stop command did not preserve spaces and apostrophes.'
    Write-Output 'PASS default login page, retained window and executable exact-run stop command'

    $testState.BrowserCalls.Clear(); $testState.PauseCalls = 0; $testState.HostMessages.Clear()
    & $wrapper -NoBrowser -NoPause
    Assert-Case ($testState.BrowserCalls.Count -eq 0 -and $testState.PauseCalls -eq 0) 'Noninteractive options still opened a browser or prompted.'
    Write-Output 'PASS terminal mode returns without browser or prompt'

    $testState.HostMessages.Clear()
    [Environment]::SetEnvironmentVariable('PINGCHE_WRAPPER_TEST_FAILURE', 'PREVIEW_CONFIG_MISSING', 'Process')
    $failed = $false
    try { & $wrapper -WarningAction SilentlyContinue } catch { $failed = $_.Exception.Message -eq 'PREVIEW_CONFIG_MISSING' }
    Assert-Case ($failed -and $testState.BrowserCalls.Count -eq 0 -and $testState.PauseCalls -eq 0 -and $testState.HostMessages.Count -eq 0) 'Startup failure was swallowed or a successful launch was reported.'
    Write-Output 'PASS failed startup preserves its error and skips login and stop guidance'
} finally {
    [Environment]::SetEnvironmentVariable('PINGCHE_WRAPPER_TEST_FAILURE', $previousFailure, 'Process')
}

# Exercise real preflight branches using synthetic files, never real protected config.
$starter = Join-Path $PSScriptRoot '..\start-admin-preview.ps1'
$missing = Join-Path $testRoot 'absent.clixml'
$previewDirectory = Join-Path $testRoot 'preview'
$failed = $false
try { & $starter -ConfigPath $missing -PreviewDirectory $previewDirectory -ProxyKernel $missing | Out-Null }
catch { $failed = $_.Exception.Message -eq 'PREVIEW_CONFIG_MISSING' }
Assert-Case ($failed -and !(Test-Path -LiteralPath $previewDirectory)) 'Missing config was not rejected before creating a run.'
$syntheticConfig = Join-Path $testRoot 'synthetic-config.clixml'
[IO.File]::WriteAllText($syntheticConfig, 'synthetic preflight file; not a credential')
$failed = $false
try { & $starter -ConfigPath $syntheticConfig -PreviewDirectory $previewDirectory -ProxyKernel $missing | Out-Null }
catch { $failed = $_.Exception.Message -eq 'PREVIEW_PROXY_KERNEL_MISSING' }
Assert-Case ($failed -and !(Test-Path -LiteralPath $previewDirectory)) 'Missing sing-box was not rejected before creating a run.'
Write-Output 'PASS missing config and kernel fail before any supervisor or service is started'

# Run the actual root CMD against a stub entry, including its failure pause and exit code.
$repository = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\..\..')).Path
$rootLauncher = @(Get-ChildItem -LiteralPath $repository -Filter '*.cmd' -File | Where-Object {
    (Get-Content -LiteralPath $_.FullName -Raw) -match 'scripts\\windows\\start-admin-local-test\.ps1'
})
Assert-Case ($rootLauncher.Count -eq 1) 'The root admin launcher was not uniquely identified.'
$cmdRoot = Join-Path $testRoot 'cmd fixture'
$cmdScripts = Join-Path $cmdRoot 'scripts\windows'
New-Item -ItemType Directory -Path $cmdScripts -Force | Out-Null
$cmdPath = Join-Path $cmdRoot 'start-admin.cmd'
Copy-Item -LiteralPath $rootLauncher[0].FullName -Destination $cmdPath
foreach ($expectedExit in @(0, 23)) {
    [IO.File]::WriteAllText((Join-Path $cmdScripts 'start-admin-local-test.ps1'), ('Write-Output SYNTHETIC_WRAPPER; exit ' + $expectedExit))
    $processInfo = [Diagnostics.ProcessStartInfo]::new()
    $processInfo.FileName = Join-Path $env:SystemRoot 'System32\cmd.exe'
    $processInfo.Arguments = '/d /c ""' + $cmdPath + '""'
    $processInfo.WorkingDirectory = $cmdRoot
    $processInfo.UseShellExecute = $false
    $processInfo.CreateNoWindow = $true
    $processInfo.RedirectStandardInput = $true
    $processInfo.RedirectStandardOutput = $true
    $processInfo.RedirectStandardError = $true
    $process = [Diagnostics.Process]::Start($processInfo)
    try {
        $process.StandardInput.WriteLine('continue')
        $process.StandardInput.Close()
        Assert-Case ($process.WaitForExit(10000)) 'The stubbed root CMD did not finish.'
        $stdout = $process.StandardOutput.ReadToEnd()
        $stderr = $process.StandardError.ReadToEnd()
        Assert-Case ($process.ExitCode -eq $expectedExit -and $stdout -match 'SYNTHETIC_WRAPPER' -and !$stderr) 'The root CMD did not invoke its entry or preserve its exit code.'
    } finally { $process.Dispose() }
}
Write-Output 'PASS root CMD invokes its local entry and preserves success and failure exit codes'
Write-Output 'Local admin wrapper tests passed with synthetic config and stubbed browser, prompt and stop entry.'
