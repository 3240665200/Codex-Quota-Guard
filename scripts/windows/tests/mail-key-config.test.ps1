#requires -Version 5.1
$ErrorActionPreference = 'Stop'
$repository = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\..\..')).Path
$initializer = Join-Path $repository 'apps\admin\backend\scripts\initialize-mail-key.ps1'
$temporaryRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
$testDirectory = Join-Path $temporaryRoot ('cqg-mail-key-test-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $testDirectory | Out-Null
function Assert([bool]$Condition, [string]$Message) { if (-not $Condition) { throw $Message } }
try {
    Assert (Test-Path -LiteralPath $initializer -PathType Leaf) 'Mailbox key initializer is not implemented.'
    $configPath = Join-Path $testDirectory 'server.clixml'
    $settings = @{}
    foreach ($name in @('DATABASE_URL', 'DATABASE_USERNAME', 'DATABASE_PASSWORD', 'JWT_SECRET')) {
        $settings[$name] = ConvertTo-SecureString -String ('synthetic-' + $name) -AsPlainText -Force
    }
    $settings | Export-Clixml -LiteralPath $configPath
    # Reproduce a protected config owned by another administrator without changing
    # the real fixture's owner: ownership metadata must not be copied to a new file.
    function Get-Acl {
        param([string]$LiteralPath)
        $acl = Microsoft.PowerShell.Security\Get-Acl -LiteralPath $LiteralPath
        $acl.SetOwner([Security.Principal.SecurityIdentifier]::new('S-1-5-18'))
        return $acl
    }
    $result = & $initializer -ConfigPath $configPath
    Assert ($result.Status -eq 'Created') 'First setup should create a key.'
    Assert (Test-Path -LiteralPath $result.BackupPath) 'Setup must preserve a recoverable encrypted backup.'
    $backup = Import-Clixml -LiteralPath $result.BackupPath
    Assert (-not $backup.Contains('MAIL_ASSISTANCE_ENCRYPTION_KEY')) 'Backup must retain the previous configuration.'
    $updated = Import-Clixml -LiteralPath $configPath
    Assert ($updated.MAIL_ASSISTANCE_ENCRYPTION_KEY -is [Security.SecureString]) 'The stored key must be DPAPI protected.'
    $plain = [pscredential]::new('test', $updated.MAIL_ASSISTANCE_ENCRYPTION_KEY).GetNetworkCredential().Password
    Assert ([Convert]::FromBase64String($plain).Length -eq 32) 'The generated key must contain 32 bytes.'
    Assert (-not (Get-Content -LiteralPath $configPath -Raw).Contains($plain)) 'Plaintext key must not appear in the file.'
    foreach ($name in $settings.Keys) {
        $current = [pscredential]::new('test', $updated[$name]).GetNetworkCredential().Password
        Assert ($current -eq ('synthetic-' + $name)) 'Setup must not change other secrets.'
    }
    $hash = (Get-FileHash -LiteralPath $configPath).Hash
    $repeated = & $initializer -ConfigPath $configPath
    Assert ($repeated.Status -eq 'AlreadyConfigured') 'A repeat run must not rotate the existing key.'
    Assert ((Get-FileHash -LiteralPath $configPath).Hash -eq $hash) 'A repeat run must not rewrite configuration.'
    $missing = Join-Path $testDirectory 'missing.clixml'
    $failed = $false
    try { & $initializer -ConfigPath $missing | Out-Null } catch { $failed = $true }
    Assert $failed 'Missing server configuration must be rejected.'
    Assert (-not (Test-Path -LiteralPath $missing)) 'Missing configuration must not be created.'
    $launcher = Get-Content -LiteralPath (Join-Path $repository 'apps\admin\backend\scripts\start-local.ps1') -Raw
    $runtimeBlock = $launcher.Substring($launcher.IndexOf('$runtimeKeys ='), $launcher.IndexOf('$allowed =') - $launcher.IndexOf('$runtimeKeys ='))
    Assert ($runtimeBlock.Contains('MAIL_ASSISTANCE_ENCRYPTION_KEY')) 'The protected startup configuration must inject the optional mail key.'
    Write-Output 'PASS: mailbox key creation, backup, preservation and startup injection.'
} finally {
    $plain = $null
    $resolvedTestDirectory = [IO.Path]::GetFullPath($testDirectory)
    if ($resolvedTestDirectory.StartsWith($temporaryRoot, [StringComparison]::OrdinalIgnoreCase) -and
        (Split-Path -Leaf $resolvedTestDirectory).StartsWith('cqg-mail-key-test-')) {
        Remove-Item -LiteralPath $resolvedTestDirectory -Recurse -Force
    }
}
