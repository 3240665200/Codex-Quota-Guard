#requires -Version 5.1
[CmdletBinding()]
param([string]$ConfigPath = 'D:\BuildCache\pingche\private\admin-backend.clixml')

$ErrorActionPreference = 'Stop'
if ([Environment]::OSVersion.Platform -ne [PlatformID]::Win32NT) {
    throw 'This helper requires Windows DPAPI. On other systems use a protected environment secret.'
}
if (-not (Test-Path -LiteralPath $ConfigPath -PathType Leaf)) {
    throw 'Existing protected backend configuration is required; no replacement database or JWT keys will be generated.'
}
$resolvedConfig = (Resolve-Path -LiteralPath $ConfigPath).Path
$settings = Import-Clixml -LiteralPath $resolvedConfig
if ($settings -isnot [System.Collections.IDictionary]) { throw 'Invalid protected configuration format.' }
foreach ($name in @('DATABASE_URL', 'DATABASE_USERNAME', 'DATABASE_PASSWORD', 'JWT_SECRET')) {
    if (-not $settings.Contains($name)) { throw 'Required backend configuration is missing.' }
}
foreach ($name in $settings.Keys) {
    if ($settings[$name] -isnot [Security.SecureString]) { throw 'Unprotected configuration values are not supported.' }
}
$keyName = 'MAIL_ASSISTANCE_ENCRYPTION_KEY'
if ($settings.Contains($keyName)) {
    try {
        $existingText = [pscredential]::new('mail-key', $settings[$keyName]).GetNetworkCredential().Password
        if ([Convert]::FromBase64String($existingText).Length -ne 32) { throw 'Invalid key length.' }
    } catch {
        throw 'The existing mailbox key is invalid; refusing to overwrite it. Restore the original protected key.'
    } finally { $existingText = $null }
    [pscustomobject]@{ Status = 'AlreadyConfigured'; ConfigPath = $resolvedConfig; BackupPath = $null }
    return
}

$directory = Split-Path -Parent $resolvedConfig
$operationId = [guid]::NewGuid().ToString('N')
$temporary = Join-Path $directory ('.mail-key-' + $operationId + '.clixml')
$backup = Join-Path $directory (([IO.Path]::GetFileNameWithoutExtension($resolvedConfig)) + '.before-mail-key-' + $operationId + '.clixml')
$keyBytes = New-Object byte[] 32
$random = [Security.Cryptography.RandomNumberGenerator]::Create()
try {
    $random.GetBytes($keyBytes)
    $keyText = [Convert]::ToBase64String($keyBytes)
    $settings[$keyName] = ConvertTo-SecureString -String $keyText -AsPlainText -Force
    $settings | Export-Clixml -LiteralPath $temporary
    # Preserve the existing DACL, not its owner/group. Copying a different owner
    # would require take-ownership privileges even when this user can update it.
    $accessSection = [Security.AccessControl.AccessControlSections]::Access
    $originalAcl = Get-Acl -LiteralPath $resolvedConfig
    $temporaryAcl = New-Object Security.AccessControl.FileSecurity
    $temporaryAcl.SetSecurityDescriptorSddlForm($originalAcl.GetSecurityDescriptorSddlForm($accessSection), $accessSection)
    Set-Acl -LiteralPath $temporary -AclObject $temporaryAcl
    $check = Import-Clixml -LiteralPath $temporary
    $checkText = [pscredential]::new('mail-key', $check[$keyName]).GetNetworkCredential().Password
    if ($checkText -ne $keyText) { throw 'Protected mailbox key verification failed.' }
    # Replace only this exact existing config, atomically retaining an encrypted backup.
    [IO.File]::Replace($temporary, $resolvedConfig, $backup)
    [pscustomobject]@{ Status = 'Created'; ConfigPath = $resolvedConfig; BackupPath = $backup }
} finally {
    $random.Dispose()
    [Array]::Clear($keyBytes, 0, $keyBytes.Length)
    $keyText = $null
    $checkText = $null
    if (Test-Path -LiteralPath $temporary -PathType Leaf) { Remove-Item -LiteralPath $temporary -Force }
}
