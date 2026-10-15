#requires -Version 5.1
$ErrorActionPreference = 'Stop'
Add-Type -Path (Join-Path $PSScriptRoot 'preview-job-harness.cs')
$shell = [Diagnostics.Process]::GetCurrentProcess().MainModule.FileName
$fixture = Join-Path $PSScriptRoot 'preview-lifecycle-fixture.ps1'
$testRoot = Join-Path ([IO.Path]::GetTempPath()) ('pingche-preview-test-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $testRoot | Out-Null
$failures = @()
function Assert-Preview([bool]$Condition,[string]$Message) { if(!$Condition){throw $Message} }
function Invoke-SyntheticCase([string]$Name,[string]$Mode,[bool]$AllowBreakaway,[bool]$NestedDeny,[bool]$Survive) {
    $directory=Join-Path $testRoot $Name; New-Item -ItemType Directory -Path $directory | Out-Null
    @{ ExpectedSid=[Security.Principal.WindowsIdentity]::GetCurrent().User.Value } |
        ConvertTo-Json -Compress | Set-Content -LiteralPath (Join-Path $directory 'control.json') -Encoding UTF8
    ConvertTo-SecureString -String 'preview-dpapi-synthetic' -AsPlainText -Force |
        Export-Clixml -LiteralPath (Join-Path $directory 'synthetic-secret.clixml')
    $controller=$null; $child=$null
    try {
        $arguments='-NoLogo -NoProfile -NonInteractive -File "'+$fixture+'" -Mode '+$Mode+' -OutputDirectory "'+$directory+'"'
        $controller=[PreviewSyntheticJob]::Start($shell,$arguments,$directory,$AllowBreakaway,$NestedDeny)
        $identityPath=Join-Path $directory 'identity.json'; $deadline=[DateTime]::UtcNow.AddSeconds(15)
        while(!(Test-Path -LiteralPath $identityPath) -and [DateTime]::UtcNow -lt $deadline){Start-Sleep -Milliseconds 50}
        Assert-Preview (Test-Path -LiteralPath $identityPath) 'Synthetic controller did not publish an identity.'
        $identity=Get-Content -LiteralPath $identityPath -Raw | ConvertFrom-Json
        Assert-Preview ([bool]$identity.Pid) ('Detached worker should be created: '+$identity.SafeCode)
        $child=[Diagnostics.Process]::GetProcessById([int]$identity.Pid)
        $null=$child.Handle
        Assert-Preview ($child.StartTime.ToUniversalTime().Ticks -eq ([DateTime]$identity.CreatedUtc).ToUniversalTime().Ticks) 'Synthetic child creation time mismatch.'
        if($Mode -eq 'Detached') {
            $readyPath=Join-Path $directory 'ready.json'; $deadline=[DateTime]::UtcNow.AddSeconds(15)
            while(!(Test-Path -LiteralPath $readyPath) -and [DateTime]::UtcNow -lt $deadline){Start-Sleep -Milliseconds 50}
            Assert-Preview (Test-Path -LiteralPath $readyPath) 'Independent worker handshake is missing.'
            $ready=Get-Content -LiteralPath $readyPath -Raw | ConvertFrom-Json
            Assert-Preview ($ready.DpapiVerified -and $ready.Independent) 'Independent worker did not verify same-user DPAPI and Job identity.'
            Assert-Preview (!$controller.Contains($child.Id)) 'WMI worker still belongs to the synthetic caller Job.'
        }
        $controller.CloseJob()
        if($Survive) { Start-Sleep -Milliseconds 350; Assert-Preview (!$child.HasExited) 'Detached worker died with its former KillOnClose Job.' }
        else { Assert-Preview ($child.WaitForExit(5000)) 'Legacy child did not exit with the synthetic Job.' }
    } finally {
        if($null -ne $child){if(!$child.HasExited){$child.Kill();$child.WaitForExit(5000)|Out-Null};$child.Dispose()}
        if($null -ne $controller){$controller.Dispose()}
    }
}
foreach($case in @(
    @('legacy-dies','Legacy',$true,$false,$false),
    @('breakaway-survives','Detached',$true,$false,$true),
    @('wmi-survives-denied-caller-job','Detached',$false,$false,$true),
    @('wmi-survives-nested-caller-job','Detached',$true,$true,$true)
)) {
    try { Invoke-SyntheticCase $case[0] $case[1] $case[2] $case[3] $case[4]; Write-Output ('PASS '+$case[0]) }
    catch { $failures += [string]$case[0]; Write-Output ('FAIL '+$case[0]+': '+$_.Exception.Message) }
}
if($failures.Count -gt 0){throw ('Synthetic preview lifecycle failures: '+($failures -join ', '))}
Write-Output 'All synthetic Job lifecycle cases passed; no API/UI or real account was started.'
