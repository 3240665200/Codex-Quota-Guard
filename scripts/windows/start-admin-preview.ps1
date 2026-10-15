#requires -Version 5.1
# Local HTTP preview only. Does not package artifacts, start Docker or stop services.
[CmdletBinding()]
param(
    [string]$ConfigPath = 'D:\BuildCache\pingche\private\admin-backend.clixml',
    [string]$PreviewDirectory = 'D:\BuildCache\pingche\management-preview',
    [int]$BackendPort = 18080,
    [int]$FrontendPort = 8000,
    [string]$ProxyKernel = 'D:\codex-tools\sing-box-1.13.14\sing-box-1.13.14-windows-amd64\sing-box.exe'
)
$ErrorActionPreference = 'Stop'
$repository = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\..')).Path
foreach ($port in @($BackendPort, $FrontendPort)) {
    if ($port -lt 1024 -or $port -gt 65535 -or (Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue)) {
        throw 'Preview ports must be unused non-privileged ports.'
    }
}
if ($BackendPort -eq $FrontendPort) { throw 'Preview ports must differ.' }
$settings = Import-Clixml -LiteralPath $ConfigPath
if ($settings -isnot [System.Collections.IDictionary]) { throw 'Invalid protected configuration.' }
$runtimeKeys = @('DATABASE_URL', 'DATABASE_USERNAME', 'DATABASE_PASSWORD', 'JWT_SECRET',
    'JWT_ISSUER', 'ACCESS_TOKEN_TTL', 'REFRESH_TOKEN_TTL', 'AUTH_RATE_LIMIT_PER_MINUTE', 'MAIL_ASSISTANCE_ENCRYPTION_KEY')
foreach ($key in @('DATABASE_URL', 'DATABASE_USERNAME', 'DATABASE_PASSWORD', 'JWT_SECRET')) {
    if (!$settings.Contains($key) -or $settings[$key] -isnot [Security.SecureString]) { throw 'Missing protected configuration.' }
}

$previewDirectoryCreated = !(Test-Path -LiteralPath $PreviewDirectory)
if ($previewDirectoryCreated) { New-Item -ItemType Directory -Path $PreviewDirectory | Out-Null }
if ((Get-Item -LiteralPath $PreviewDirectory).Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Preview directory cannot be a link.' }
$acl = [System.Security.AccessControl.DirectorySecurity]::new()
$acl.SetAccessRuleProtection($true, $false)
foreach ($identity in @([System.Security.Principal.WindowsIdentity]::GetCurrent().User,
    [System.Security.Principal.SecurityIdentifier]'S-1-5-18')) {
    $acl.AddAccessRule([System.Security.AccessControl.FileSystemAccessRule]::new(
        $identity, 'FullControl', 'ContainerInherit,ObjectInherit', 'None', 'Allow'))
}
if ($previewDirectoryCreated) { Set-Acl -LiteralPath $PreviewDirectory -AclObject $acl }
$featureFile = Join-Path $PreviewDirectory 'feature-keys.clixml'
$featureKeys = @('OFFICIAL_SESSION_ENCRYPTION_KEY', 'MANAGED_PROXY_ENCRYPTION_KEY')
if (Test-Path -LiteralPath $featureFile) {
    $features = Import-Clixml -LiteralPath $featureFile
} else {
    $features = @{}
    $random = [Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        foreach ($key in $featureKeys) {
            $bytes = New-Object byte[] 32
            $random.GetBytes($bytes)
            $features[$key] = ConvertTo-SecureString -String ([Convert]::ToBase64String($bytes)) -AsPlainText -Force
            [Array]::Clear($bytes, 0, $bytes.Length)
        }
    } finally { $random.Dispose() }
    $features | Export-Clixml -LiteralPath $featureFile -NoClobber
}
if ($features -isnot [System.Collections.IDictionary]) { throw 'Invalid protected feature configuration.' }
foreach ($key in $featureKeys) {
    if (!$features.Contains($key) -or $features[$key] -isnot [Security.SecureString]) { throw 'Invalid protected feature key.' }
}

$maven = (Get-Command mvn.cmd -ErrorAction Stop).Source
$pnpm = (Get-Command pnpm.cmd -ErrorAction Stop).Source
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss-fff'
$apiLog = Join-Path $PreviewDirectory "api-$stamp.out.log"
$apiError = Join-Path $PreviewDirectory "api-$stamp.err.log"
$uiLog = Join-Path $PreviewDirectory "ui-$stamp.out.log"
$uiError = Join-Path $PreviewDirectory "ui-$stamp.err.log"
$owned = $runtimeKeys + $featureKeys + @('SERVER_ADDRESS', 'WEB_SERVER_PORT', 'APP_REQUIRE_HTTPS',
    'SPRING_PROFILES_ACTIVE', 'SERVER_FORWARD_HEADERS_STRATEGY', 'MANAGEMENT_ALLOW_LOOPBACK_HTTP',
    'MANAGED_PROXY_SING_BOX_PATH', 'CORS_ALLOWED_ORIGINS', 'HOST', 'PORT', 'ADMIN_API_TARGET', 'NODE_OPTIONS',
    'ADMIN_BOOTSTRAP_EMAIL', 'ADMIN_BOOTSTRAP_PASSWORD', 'ADMIN_BOOTSTRAP_DISPLAY_NAME')
$previous = @{}
try {
    foreach ($key in $owned) {
        $previous[$key] = [Environment]::GetEnvironmentVariable($key, 'Process')
        Remove-Item -LiteralPath "Env:$key" -ErrorAction SilentlyContinue
    }
    foreach ($key in $runtimeKeys) {
        if ($settings.Contains($key)) {
            if ($settings[$key] -isnot [Security.SecureString]) { throw 'Unprotected runtime setting.' }
            [Environment]::SetEnvironmentVariable($key, [pscredential]::new('preview', $settings[$key]).GetNetworkCredential().Password, 'Process')
        }
    }
    foreach ($key in $featureKeys) {
        [Environment]::SetEnvironmentVariable($key, [pscredential]::new('preview', $features[$key]).GetNetworkCredential().Password, 'Process')
    }
    $env:SERVER_ADDRESS = '127.0.0.1'
    $env:WEB_SERVER_PORT = [string]$BackendPort
    $env:APP_REQUIRE_HTTPS = 'false'
    $env:SERVER_FORWARD_HEADERS_STRATEGY = 'none'
    $env:MANAGEMENT_ALLOW_LOOPBACK_HTTP = 'true'
    $env:MANAGED_PROXY_SING_BOX_PATH = $ProxyKernel
    $env:CORS_ALLOWED_ORIGINS = "http://localhost:$FrontendPort,http://127.0.0.1:$FrontendPort"
    $backend = Start-Process -FilePath $maven -ArgumentList '-o', '-f',
        "`"$(Join-Path $repository 'apps\admin\backend\pom.xml')`"", '-Dmaven.test.skip=true', 'spring-boot:run' `
        -WorkingDirectory $repository -WindowStyle Hidden -RedirectStandardOutput $apiLog -RedirectStandardError $apiError -PassThru

    # The UI development server receives no database, signing or encryption secrets.
    foreach ($key in ($runtimeKeys + $featureKeys)) { Remove-Item -LiteralPath "Env:$key" -ErrorAction SilentlyContinue }
    $env:HOST = '127.0.0.1'
    $env:PORT = [string]$FrontendPort
    $env:ADMIN_API_TARGET = "http://127.0.0.1:$BackendPort"
    $hook = (Join-Path $PSScriptRoot 'preview-loopback.cjs').Replace('\', '/')
    $env:NODE_OPTIONS = (($previous['NODE_OPTIONS'] + ' --require "' + $hook + '"').Trim())
    $frontend = Start-Process -FilePath $pnpm -ArgumentList 'dev:admin' -WorkingDirectory $repository `
        -WindowStyle Hidden -RedirectStandardOutput $uiLog -RedirectStandardError $uiError -PassThru
    [pscustomobject]@{ Url = "http://localhost:$FrontendPort/#/official-accounts";
        BackendLauncherPid = $backend.Id; FrontendLauncherPid = $frontend.Id;
        BackendLog = $apiLog; FrontendLog = $uiLog; FeatureKeys = 'Windows DPAPI protected; existing keys preserved' }
} finally {
    foreach ($key in $previous.Keys) {
        if ($null -eq $previous[$key]) { Remove-Item -LiteralPath "Env:$key" -ErrorAction SilentlyContinue }
        else { [Environment]::SetEnvironmentVariable($key, $previous[$key], 'Process') }
    }
}
