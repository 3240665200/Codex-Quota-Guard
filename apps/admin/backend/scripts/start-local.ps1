#requires -Version 5.1
[CmdletBinding()]
param(
    [string]$ConfigPath = 'D:\BuildCache\pingche\private\admin-backend.clixml',
    [string]$RuntimeDirectory = 'D:\BuildCache\pingche\runtime',
    [string]$LogDirectory = 'D:\BuildCache\pingche\logs',
    [ValidateRange(1024, 65535)][int]$Port = 8080,
    [string]$BindAddress = '0.0.0.0',
    [ValidateRange(15, 300)][int]$StartupTimeoutSeconds = 180,
    [string]$JavaCommand = 'java.exe',
    [string]$MavenCommand = 'mvn.cmd',
    [string]$BootstrapEmail = '',
    [Security.SecureString]$BootstrapPassword,
    [switch]$SkipBuild
)

$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($BootstrapEmail) -ne ($null -eq $BootstrapPassword)) {
    throw 'BootstrapEmail and BootstrapPassword must be provided together.'
}
$parsedAddress = $null
if (-not [System.Net.IPAddress]::TryParse($BindAddress, [ref]$parsedAddress) -or
    $parsedAddress.AddressFamily -ne [System.Net.Sockets.AddressFamily]::InterNetwork) {
    throw 'BindAddress must be a local IPv4 address.'
}
if ($BindAddress -ne '127.0.0.1' -and $BindAddress -ne '0.0.0.0') {
    $octets = $parsedAddress.GetAddressBytes()
    $privateLan = $octets[0] -eq 10 -or
        ($octets[0] -eq 172 -and $octets[1] -ge 16 -and $octets[1] -le 31) -or
        ($octets[0] -eq 192 -and $octets[1] -eq 168)
    if (-not $privateLan -or -not (Get-NetIPAddress -AddressFamily IPv4 -IPAddress $BindAddress -ErrorAction SilentlyContinue)) {
        throw 'BindAddress must be an assigned private LAN IPv4 address on this computer.'
    }
}
if (Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue) {
    throw "Port $Port is already in use. Stop the verified old backend before restarting."
}
if (-not (Test-Path -LiteralPath $ConfigPath -PathType Leaf)) {
    throw 'Missing Windows DPAPI configuration. See the backend README; do not generate replacement keys.'
}
$settings = Import-Clixml -LiteralPath $ConfigPath
$required = @('DATABASE_URL', 'DATABASE_USERNAME', 'DATABASE_PASSWORD', 'JWT_SECRET')
$runtimeKeys = $required + @('CORS_ALLOWED_ORIGINS', 'JWT_ISSUER', 'ACCESS_TOKEN_TTL',
    'REFRESH_TOKEN_TTL', 'AUTH_RATE_LIMIT_PER_MINUTE', 'MAIL_ASSISTANCE_ENCRYPTION_KEY')
# Keep old fields readable for the encrypted pre-migration backup, but never
# inject the retired Codex credential key or CLI settings into the new server.
$allowed = $runtimeKeys + @('CODEX_CREDENTIAL_ENCRYPTION_KEY', 'CODEX_LOGIN_COMMAND',
    'CODEX_AUTH_WORK_DIR')
if ($settings -isnot [System.Collections.IDictionary]) {
    throw 'Invalid protected configuration format.'
}
foreach ($key in $required) {
    if (-not $settings.Contains($key)) { throw "Missing configuration field: $key" }
}
foreach ($key in $settings.Keys) {
    if ($key -notin $allowed -or $settings[$key] -isnot [Security.SecureString]) {
        throw 'Configuration contains an unsupported field or an unprotected value.'
    }
}

$backendDirectory = Split-Path -Parent $PSScriptRoot
if (-not $SkipBuild) {
    & $MavenCommand -q -f (Join-Path $backendDirectory 'pom.xml') clean verify
    if ($LASTEXITCODE -ne 0) { throw 'Backend verification failed; no service was started.' }
}
$sourceJar = Join-Path $backendDirectory 'target\web-backend-0.1.0-SNAPSHOT.jar'
$jarHash = (Get-FileHash -LiteralPath $sourceJar -Algorithm SHA256).Hash
New-Item -ItemType Directory -Path $RuntimeDirectory, $LogDirectory -Force | Out-Null
$runtimeJar = Join-Path $RuntimeDirectory "admin-backend-$jarHash.jar"
if (-not (Test-Path -LiteralPath $runtimeJar)) {
    Copy-Item -LiteralPath $sourceJar -Destination $runtimeJar
}
if ((Get-FileHash -LiteralPath $runtimeJar -Algorithm SHA256).Hash -ne $jarHash) {
    throw 'Runtime artifact verification failed.'
}
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss-fff'
$stdout = Join-Path $LogDirectory "admin-backend-$Port-$stamp.out.log"
$stderr = Join-Path $LogDirectory "admin-backend-$Port-$stamp.err.log"
$ownedKeys = $allowed + @('WEB_SERVER_PORT', 'SERVER_ADDRESS', 'SPRING_PROFILES_ACTIVE',
    'ADMIN_BOOTSTRAP_EMAIL', 'ADMIN_BOOTSTRAP_PASSWORD', 'ADMIN_BOOTSTRAP_DISPLAY_NAME')
$originalEnvironment = @{}
try {
    foreach ($key in $ownedKeys) {
        $originalEnvironment[$key] = [Environment]::GetEnvironmentVariable($key, 'Process')
        Remove-Item -LiteralPath "Env:$key" -ErrorAction SilentlyContinue
    }
    foreach ($key in $runtimeKeys) {
        if (-not $settings.Contains($key)) { continue }
        $value = [pscredential]::new('runtime', $settings[$key]).GetNetworkCredential().Password
        [Environment]::SetEnvironmentVariable($key, $value, 'Process')
        $value = $null
    }
    $env:WEB_SERVER_PORT = [string]$Port
    $env:SERVER_ADDRESS = $BindAddress
    if (-not [string]::IsNullOrWhiteSpace($BootstrapEmail)) {
        $env:ADMIN_BOOTSTRAP_EMAIL = $BootstrapEmail
        $env:ADMIN_BOOTSTRAP_PASSWORD = [pscredential]::new('bootstrap', $BootstrapPassword).GetNetworkCredential().Password
        $env:ADMIN_BOOTSTRAP_DISPLAY_NAME = 'Admin'
    }
    $backendProcess = Start-Process -FilePath $JavaCommand -ArgumentList '-jar', "`"$runtimeJar`"" `
        -WorkingDirectory $RuntimeDirectory -WindowStyle Hidden `
        -RedirectStandardOutput $stdout -RedirectStandardError $stderr -PassThru
} finally {
    foreach ($key in $originalEnvironment.Keys) {
        if ($null -eq $originalEnvironment[$key]) {
            Remove-Item -LiteralPath "Env:$key" -ErrorAction SilentlyContinue
        } else {
            [Environment]::SetEnvironmentVariable($key, $originalEnvironment[$key], 'Process')
        }
    }
}

$deadline = (Get-Date).AddSeconds($StartupTimeoutSeconds)
do {
    if ($backendProcess.HasExited) { throw "Backend exited. See $stdout and $stderr" }
    $listener = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue |
        Where-Object OwningProcess -eq $backendProcess.Id
    if ($listener) {
        [pscustomobject]@{ Pid = $backendProcess.Id; Address = $BindAddress; Port = $Port; Jar = $runtimeJar; Log = $stdout }
        return
    }
    Start-Sleep -Milliseconds 500
} while ((Get-Date) -lt $deadline)

# Only the process created by this invocation may be stopped on startup failure.
$backendProcess.Kill()
throw "Backend startup timed out. See $stdout"
