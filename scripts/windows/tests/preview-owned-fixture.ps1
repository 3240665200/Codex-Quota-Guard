#requires -Version 5.1
param([ValidateSet('Server','Exit','Wait')][string]$Mode='Server',[int]$Port=0)
$ErrorActionPreference='Stop'
if($Mode -eq 'Exit'){exit 17}
if($Mode -eq 'Wait'){Start-Sleep -Seconds 30;exit 0}
$listener=[Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback,$Port)
$listener.Start()
try {
    if($env:PREVIEW_SYNTHETIC_UI -eq 'true') {
        foreach($key in @('DATABASE_PASSWORD','JWT_SECRET','OFFICIAL_SESSION_ENCRYPTION_KEY','MANAGED_PROXY_ENCRYPTION_KEY')) {
            if([Environment]::GetEnvironmentVariable($key,'Process')){throw 'SYNTHETIC_UI_SECRET_LEAK'}
        }
        Write-Output 'SYNTHETIC_UI_ENV_OK'
    }
    Start-Sleep -Seconds 30
} finally {$listener.Stop()}
