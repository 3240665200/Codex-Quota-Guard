Rust meter binary and Windows service scripts are placed here during packaging.
The setup itself does not register the service. The installed app installs it on first elevated launch, including when the central server is offline; pairing still requires a reachable server. An administrator can also run install-meter-service.ps1 manually.
The Windows meter executable statically includes its C runtime; it needs no separate Visual C++ runtime. Packaging rejects executables that import unbundled VC++ DLLs.
Failed installation steps are recorded without credentials in the user's app data meter-setup-status.json. A retry can repair a registered service that cannot start, preserving its device identity and scanning state.
After saving a new server origin and restarting the app elevated, the service configuration is synchronized even if the service is already running.
An administrator can use show-meter-logs.ps1 to view safe rotated service records after installation.
