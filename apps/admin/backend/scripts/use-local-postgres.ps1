#requires -Version 5.1
[CmdletBinding()]
param(
    [Parameter(Mandatory)][Security.SecureString]$DatabasePassword,
    [string]$ConfigPath = 'D:\BuildCache\pingche\private\admin-backend.clixml'
)

$ErrorActionPreference = 'Stop'
$configFile = [IO.Path]::GetFullPath($ConfigPath)
if (-not (Test-Path -LiteralPath $configFile -PathType Leaf)) {
    throw 'Protected backend configuration was not found.'
}

$settings = Import-Clixml -LiteralPath $configFile
$required = @('DATABASE_URL', 'DATABASE_USERNAME', 'DATABASE_PASSWORD', 'JWT_SECRET',
    'CODEX_CREDENTIAL_ENCRYPTION_KEY', 'CODEX_LOGIN_COMMAND', 'CODEX_AUTH_WORK_DIR')
if ($settings -isnot [Collections.IDictionary]) {
    throw 'Protected backend configuration has an invalid format.'
}
foreach ($key in $required) {
    if (-not $settings.Contains($key) -or $settings[$key] -isnot [Security.SecureString]) {
        throw "Protected backend configuration is missing $key."
    }
}

# Preserve all existing JWT/Codex secrets. Only the three database fields change.
$settings['DATABASE_URL'] = ConvertTo-SecureString 'jdbc:postgresql://127.0.0.1:5432/pingche' -AsPlainText -Force
$settings['DATABASE_USERNAME'] = ConvertTo-SecureString 'postgres' -AsPlainText -Force
$settings['DATABASE_PASSWORD'] = $DatabasePassword

$backupPath = "$configFile.before-local-$(Get-Date -Format 'yyyyMMdd-HHmmss').bak"
Copy-Item -LiteralPath $configFile -Destination $backupPath -ErrorAction Stop
$settings | Export-Clixml -LiteralPath $configFile -ErrorAction Stop

$verified = Import-Clixml -LiteralPath $configFile
foreach ($key in $required) {
    if ($verified[$key] -isnot [Security.SecureString]) {
        throw 'Protected configuration verification failed; restore the backup before starting the backend.'
    }
}
Write-Host 'Protected backend database target updated to 127.0.0.1:5432/pingche.'
Write-Host "Previous protected configuration backup: $backupPath"
