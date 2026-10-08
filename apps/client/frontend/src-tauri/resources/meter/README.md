Rust meter binary and Windows service scripts are placed here during packaging.
The setup itself does not register the service. The installed app installs it on first elevated launch after a reachable server address has been saved, or an administrator can run install-meter-service.ps1 manually.
An administrator can use show-meter-logs.ps1 to view safe rotated service records after installation.
