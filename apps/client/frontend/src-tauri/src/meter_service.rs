use serde::Serialize;

#[derive(Clone, Copy, Debug, Eq, PartialEq, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub(crate) enum MeterStartOutcome {
    Running,
    Started,
    Installed,
    NotInstalled,
    NeedsAdmin,
    ServerUnavailable,
    InstallFailed,
    Failed,
    #[cfg(not(windows))]
    Unsupported,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum ServiceState {
    Running,
    Stopped,
    StartPending,
    StopPending,
    Missing,
    Failed,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum StartAttempt {
    Started,
    AlreadyRunning,
    AccessDenied,
    Failed,
}

trait ServiceOps {
    fn state(&self) -> ServiceState;
    fn start(&self) -> StartAttempt;
    fn request_elevation(&self) -> bool;
    fn wait_running(&self) -> bool;
    fn wait_stopped(&self) -> bool;
}

trait InstallOps {
    fn bundle_ready(&self) -> bool;
    fn server_ready(&self) -> bool;
    fn has_admin_rights(&self) -> bool;
    fn install(&self) -> bool;
}

fn ensure_with_install(ops: &impl ServiceOps, installer: &impl InstallOps) -> MeterStartOutcome {
    if ops.state() != ServiceState::Missing {
        return ensure_with(ops);
    }
    if !installer.bundle_ready() {
        return MeterStartOutcome::NotInstalled;
    }
    if !installer.server_ready() {
        return MeterStartOutcome::ServerUnavailable;
    }
    if !installer.has_admin_rights() {
        return MeterStartOutcome::NeedsAdmin;
    }
    if !installer.install() || !ops.wait_running() {
        return MeterStartOutcome::InstallFailed;
    }
    MeterStartOutcome::Installed
}

fn ensure_with(ops: &impl ServiceOps) -> MeterStartOutcome {
    match ops.state() {
        ServiceState::Running => return MeterStartOutcome::Running,
        ServiceState::Missing => return MeterStartOutcome::NotInstalled,
        ServiceState::StartPending => {
            return if ops.wait_running() {
                MeterStartOutcome::Running
            } else {
                MeterStartOutcome::Failed
            };
        }
        ServiceState::StopPending if !ops.wait_stopped() => return MeterStartOutcome::Failed,
        ServiceState::Stopped | ServiceState::StopPending => {}
        ServiceState::Failed => return MeterStartOutcome::Failed,
    }

    match ops.start() {
        StartAttempt::Started => {
            if ops.wait_running() {
                MeterStartOutcome::Started
            } else {
                MeterStartOutcome::Failed
            }
        }
        StartAttempt::AlreadyRunning => {
            if ops.wait_running() {
                MeterStartOutcome::Running
            } else {
                MeterStartOutcome::Failed
            }
        }
        StartAttempt::AccessDenied => {
            if !ops.request_elevation() {
                return MeterStartOutcome::NeedsAdmin;
            }
            if ops.wait_running() {
                MeterStartOutcome::Started
            } else {
                MeterStartOutcome::Failed
            }
        }
        StartAttempt::Failed => MeterStartOutcome::Failed,
    }
}

#[cfg(windows)]
pub(crate) fn ensure_running_or_install(app: &tauri::AppHandle) -> MeterStartOutcome {
    ensure_with_install(
        &windows::WindowsServiceOps,
        &windows::WindowsInstaller::new(app),
    )
}

#[cfg(not(windows))]
pub(crate) fn ensure_running_or_install(_app: &tauri::AppHandle) -> MeterStartOutcome {
    MeterStartOutcome::Unsupported
}

#[cfg(windows)]
mod windows {
    use super::{InstallOps, ServiceOps, ServiceState, StartAttempt};
    use std::ffi::{OsStr, OsString};
    use std::fs;
    use std::io::Read;
    use std::mem::size_of;
    use std::os::windows::ffi::{OsStrExt, OsStringExt};
    use std::os::windows::process::CommandExt;
    use std::path::PathBuf;
    use std::process::Command;
    use std::ptr;
    use std::thread;
    use std::time::{Duration, Instant};
    use tauri::{path::BaseDirectory, AppHandle, Manager};
    use windows_sys::Win32::Foundation::{
        GetLastError, ERROR_ACCESS_DENIED, ERROR_SERVICE_ALREADY_RUNNING,
        ERROR_SERVICE_DOES_NOT_EXIST,
    };
    use windows_sys::Win32::System::Services::{
        CloseServiceHandle, OpenSCManagerW, OpenServiceW, QueryServiceStatusEx, StartServiceW,
        SC_HANDLE, SC_MANAGER_CONNECT, SC_MANAGER_CREATE_SERVICE, SC_STATUS_PROCESS_INFO,
        SERVICE_QUERY_STATUS, SERVICE_RUNNING, SERVICE_START, SERVICE_START_PENDING,
        SERVICE_STATUS_PROCESS, SERVICE_STOPPED, SERVICE_STOP_PENDING,
    };
    use windows_sys::Win32::System::SystemInformation::GetSystemDirectoryW;
    use windows_sys::Win32::System::Threading::CREATE_NO_WINDOW;
    use windows_sys::Win32::UI::Shell::ShellExecuteW;
    use windows_sys::Win32::UI::WindowsAndMessaging::SW_HIDE;

    const SERVICE_NAME: &str = "CodexQuotaGuardMeter";
    const WAIT_TIMEOUT: Duration = Duration::from_secs(12);

    pub(super) struct WindowsServiceOps;

    pub(super) struct WindowsInstaller {
        app: AppHandle,
        server_url: String,
    }

    impl WindowsInstaller {
        pub(super) fn new(app: &AppHandle) -> Self {
            Self {
                app: app.clone(),
                server_url: crate::server_config::load(app).url,
            }
        }

        fn resource(&self, name: &str) -> Option<PathBuf> {
            let path = self
                .app
                .path()
                .resolve(PathBuf::from("meter").join(name), BaseDirectory::Resource)
                .ok()?;
            fs::symlink_metadata(&path)
                .ok()
                .filter(|metadata| metadata.file_type().is_file())?;
            Some(path)
        }
    }

    impl InstallOps for WindowsInstaller {
        fn bundle_ready(&self) -> bool {
            self.resource("install-meter-service.ps1").is_some()
                && self.resource("quota-guard-meter.exe").is_some()
        }

        fn server_ready(&self) -> bool {
            let Ok(client) = reqwest::blocking::Client::builder()
                .no_proxy()
                .redirect(reqwest::redirect::Policy::none())
                .timeout(Duration::from_secs(3))
                .build()
            else {
                return false;
            };
            let url = format!("{}/api/v1/users/me", self.server_url);
            let Ok(response) = client.get(url).send() else {
                return false;
            };
            if response.status() != reqwest::StatusCode::UNAUTHORIZED {
                return false;
            }
            let mut body = Vec::new();
            if response.take(4097).read_to_end(&mut body).is_err() || body.len() > 4096 {
                return false;
            }
            serde_json::from_slice::<serde_json::Value>(&body)
                .ok()
                .and_then(|body| body.get("code")?.as_str().map(str::to_owned))
                .as_deref()
                == Some("AUTH_REQUIRED")
        }

        fn has_admin_rights(&self) -> bool {
            // SAFETY: Null names select this machine's active service database.
            let manager =
                unsafe { OpenSCManagerW(ptr::null(), ptr::null(), SC_MANAGER_CREATE_SERVICE) };
            if manager.is_null() {
                return false;
            }
            // SAFETY: This handle was returned by OpenSCManagerW above.
            unsafe { CloseServiceHandle(manager) };
            true
        }

        fn install(&self) -> bool {
            let (Some(script), Some(binary), Some(mut powershell)) = (
                self.resource("install-meter-service.ps1"),
                self.resource("quota-guard-meter.exe"),
                system_directory(),
            ) else {
                return false;
            };
            powershell.push("WindowsPowerShell");
            powershell.push("v1.0");
            powershell.push("powershell.exe");
            if !powershell.is_file() {
                return false;
            }
            let Ok(mut child) = Command::new(powershell)
                .args([
                    "-NoLogo",
                    "-NoProfile",
                    "-NonInteractive",
                    "-ExecutionPolicy",
                    "Bypass",
                    "-File",
                ])
                .arg(script)
                .arg("-BinaryPath")
                .arg(binary)
                .arg("-ServerUrl")
                .arg(&self.server_url)
                .creation_flags(CREATE_NO_WINDOW)
                .spawn()
            else {
                return false;
            };

            let deadline = Instant::now() + Duration::from_secs(60);
            loop {
                match child.try_wait() {
                    Ok(Some(status)) => return status.success(),
                    Ok(None) if Instant::now() < deadline => {
                        thread::sleep(Duration::from_millis(200))
                    }
                    _ => {
                        let _ = child.kill();
                        let _ = child.wait();
                        return false;
                    }
                }
            }
        }
    }

    struct ServiceHandles {
        manager: SC_HANDLE,
        service: SC_HANDLE,
    }

    impl Drop for ServiceHandles {
        fn drop(&mut self) {
            // SAFETY: Both handles were returned by SCM calls and are owned here.
            unsafe {
                CloseServiceHandle(self.service);
                CloseServiceHandle(self.manager);
            }
        }
    }

    fn wide(value: &OsStr) -> Vec<u16> {
        value.encode_wide().chain(std::iter::once(0)).collect()
    }

    fn open_service(access: u32) -> Result<ServiceHandles, u32> {
        // SAFETY: Null names select the local computer and active SCM database.
        let manager = unsafe { OpenSCManagerW(ptr::null(), ptr::null(), SC_MANAGER_CONNECT) };
        if manager.is_null() {
            // SAFETY: Called immediately after the failing Win32 API.
            return Err(unsafe { GetLastError() });
        }
        let service_name = wide(OsStr::new(SERVICE_NAME));
        // SAFETY: The SCM handle and UTF-16 name remain valid for this call.
        let service = unsafe { OpenServiceW(manager, service_name.as_ptr(), access) };
        if service.is_null() {
            // SAFETY: Called immediately after OpenServiceW failed.
            let error = unsafe { GetLastError() };
            // SAFETY: manager was successfully opened above.
            unsafe { CloseServiceHandle(manager) };
            return Err(error);
        }
        Ok(ServiceHandles { manager, service })
    }

    fn query_state() -> Result<u32, u32> {
        let handles = open_service(SERVICE_QUERY_STATUS)?;
        // SAFETY: SERVICE_STATUS_PROCESS is a plain Win32 output structure.
        let mut status: SERVICE_STATUS_PROCESS = unsafe { std::mem::zeroed() };
        let mut needed = 0u32;
        // SAFETY: The buffer is exactly the size of SERVICE_STATUS_PROCESS and the
        // service handle has SERVICE_QUERY_STATUS access.
        let success = unsafe {
            QueryServiceStatusEx(
                handles.service,
                SC_STATUS_PROCESS_INFO,
                (&mut status as *mut SERVICE_STATUS_PROCESS).cast::<u8>(),
                size_of::<SERVICE_STATUS_PROCESS>() as u32,
                &mut needed,
            )
        };
        if success == 0 {
            // SAFETY: Called immediately after QueryServiceStatusEx failed.
            return Err(unsafe { GetLastError() });
        }
        Ok(status.dwCurrentState)
    }

    fn wait_for(expected: u32) -> bool {
        let deadline = Instant::now() + WAIT_TIMEOUT;
        loop {
            if query_state().is_ok_and(|state| state == expected) {
                return true;
            }
            if Instant::now() >= deadline {
                return false;
            }
            thread::sleep(Duration::from_millis(200));
        }
    }

    fn elevate_fixed_start() -> bool {
        let Some(mut executable) = system_directory() else {
            return false;
        };
        executable.push("sc.exe");
        if !executable.is_file() {
            return false;
        }
        let verb = wide(OsStr::new("runas"));
        let file = wide(executable.as_os_str());
        let parameters = wide(OsStr::new("start CodexQuotaGuardMeter"));
        // SAFETY: All strings are NUL-terminated and live through the call.
        // Only Microsoft's System32 sc.exe and the fixed service name are elevated.
        let result = unsafe {
            ShellExecuteW(
                ptr::null_mut(),
                verb.as_ptr(),
                file.as_ptr(),
                parameters.as_ptr(),
                ptr::null(),
                SW_HIDE,
            )
        };
        (result as isize) > 32
    }

    fn system_directory() -> Option<PathBuf> {
        let mut buffer = [0u16; 32768];
        // SAFETY: The caller-owned buffer is writable and its length is accurate.
        let length = unsafe { GetSystemDirectoryW(buffer.as_mut_ptr(), buffer.len() as u32) };
        if length == 0 || length as usize >= buffer.len() {
            return None;
        }
        Some(PathBuf::from(OsString::from_wide(
            &buffer[..length as usize],
        )))
    }

    impl ServiceOps for WindowsServiceOps {
        fn state(&self) -> ServiceState {
            match query_state() {
                Ok(SERVICE_RUNNING) => ServiceState::Running,
                Ok(SERVICE_STOPPED) => ServiceState::Stopped,
                Ok(SERVICE_START_PENDING) => ServiceState::StartPending,
                Ok(SERVICE_STOP_PENDING) => ServiceState::StopPending,
                Err(ERROR_SERVICE_DOES_NOT_EXIST) => ServiceState::Missing,
                _ => ServiceState::Failed,
            }
        }

        fn start(&self) -> StartAttempt {
            let handles = match open_service(SERVICE_QUERY_STATUS | SERVICE_START) {
                Ok(handles) => handles,
                Err(ERROR_ACCESS_DENIED) => return StartAttempt::AccessDenied,
                Err(_) => return StartAttempt::Failed,
            };
            // SAFETY: The service handle includes SERVICE_START and no arguments are passed.
            if unsafe { StartServiceW(handles.service, 0, ptr::null()) } != 0 {
                return StartAttempt::Started;
            }
            // SAFETY: Called immediately after StartServiceW failed.
            match unsafe { GetLastError() } {
                ERROR_SERVICE_ALREADY_RUNNING => StartAttempt::AlreadyRunning,
                ERROR_ACCESS_DENIED => StartAttempt::AccessDenied,
                _ => StartAttempt::Failed,
            }
        }

        fn request_elevation(&self) -> bool {
            elevate_fixed_start()
        }

        fn wait_running(&self) -> bool {
            wait_for(SERVICE_RUNNING)
        }

        fn wait_stopped(&self) -> bool {
            wait_for(SERVICE_STOPPED)
        }
    }

    #[test]
    #[ignore = "requires a locally installed Windows service"]
    fn installed_service_is_observed_without_mutation() {
        let ops = WindowsServiceOps;
        assert!(matches!(
            ops.state(),
            ServiceState::Running
                | ServiceState::Stopped
                | ServiceState::StartPending
                | ServiceState::StopPending
        ));
    }

    #[test]
    #[ignore = "requires an installed service intentionally stopped by the operator"]
    fn stopped_installed_service_is_started_by_the_native_bridge() {
        let ops = WindowsServiceOps;
        assert_eq!(ops.state(), ServiceState::Stopped);
        assert_eq!(super::ensure_with(&ops), super::MeterStartOutcome::Started);
        assert_eq!(ops.state(), ServiceState::Running);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::cell::Cell;

    struct FakeInstaller {
        bundled: bool,
        server_ready: bool,
        admin: bool,
        install_succeeds: bool,
        installs: Cell<u32>,
    }

    impl FakeInstaller {
        fn ready() -> Self {
            Self {
                bundled: true,
                server_ready: true,
                admin: true,
                install_succeeds: true,
                installs: Cell::new(0),
            }
        }
    }

    impl InstallOps for FakeInstaller {
        fn bundle_ready(&self) -> bool {
            self.bundled
        }
        fn server_ready(&self) -> bool {
            self.server_ready
        }
        fn has_admin_rights(&self) -> bool {
            self.admin
        }
        fn install(&self) -> bool {
            self.installs.set(self.installs.get() + 1);
            self.install_succeeds
        }
    }

    struct FakeOps {
        state: ServiceState,
        start: StartAttempt,
        elevation_accepted: bool,
        running: bool,
        stopped: bool,
        starts: Cell<u32>,
        elevations: Cell<u32>,
    }

    impl FakeOps {
        fn new(state: ServiceState) -> Self {
            Self {
                state,
                start: StartAttempt::Started,
                elevation_accepted: false,
                running: true,
                stopped: true,
                starts: Cell::new(0),
                elevations: Cell::new(0),
            }
        }
    }

    impl ServiceOps for FakeOps {
        fn state(&self) -> ServiceState {
            self.state
        }
        fn start(&self) -> StartAttempt {
            self.starts.set(self.starts.get() + 1);
            self.start
        }
        fn request_elevation(&self) -> bool {
            self.elevations.set(self.elevations.get() + 1);
            self.elevation_accepted
        }
        fn wait_running(&self) -> bool {
            self.running
        }
        fn wait_stopped(&self) -> bool {
            self.stopped
        }
    }

    #[test]
    fn a_running_or_missing_service_never_requests_elevation() {
        let running = FakeOps::new(ServiceState::Running);
        assert_eq!(ensure_with(&running), MeterStartOutcome::Running);
        assert_eq!(running.starts.get(), 0);
        let missing = FakeOps::new(ServiceState::Missing);
        assert_eq!(ensure_with(&missing), MeterStartOutcome::NotInstalled);
        assert_eq!(missing.elevations.get(), 0);
    }

    #[test]
    fn a_stopped_service_is_started_and_remains_independent_of_the_app() {
        let stopped = FakeOps::new(ServiceState::Stopped);
        assert_eq!(ensure_with(&stopped), MeterStartOutcome::Started);
        assert_eq!(stopped.starts.get(), 1);
        assert_eq!(stopped.elevations.get(), 0);
    }

    #[test]
    fn access_denied_requests_one_admin_approval_but_never_grants_stop_rights() {
        let mut denied = FakeOps::new(ServiceState::Stopped);
        denied.start = StartAttempt::AccessDenied;
        denied.elevation_accepted = true;
        assert_eq!(ensure_with(&denied), MeterStartOutcome::Started);
        assert_eq!(denied.elevations.get(), 1);
        denied.elevation_accepted = false;
        assert_eq!(ensure_with(&denied), MeterStartOutcome::NeedsAdmin);
    }

    #[test]
    fn pending_and_failed_states_are_bounded_and_not_reported_as_running() {
        let mut pending = FakeOps::new(ServiceState::StartPending);
        pending.running = false;
        assert_eq!(ensure_with(&pending), MeterStartOutcome::Failed);
        let mut stopping = FakeOps::new(ServiceState::StopPending);
        stopping.stopped = false;
        assert_eq!(ensure_with(&stopping), MeterStartOutcome::Failed);
        let failed = FakeOps::new(ServiceState::Failed);
        assert_eq!(ensure_with(&failed), MeterStartOutcome::Failed);
        let mut start_failed = FakeOps::new(ServiceState::Stopped);
        start_failed.start = StartAttempt::Failed;
        assert_eq!(ensure_with(&start_failed), MeterStartOutcome::Failed);
        let mut already_running = FakeOps::new(ServiceState::Stopped);
        already_running.start = StartAttempt::AlreadyRunning;
        assert_eq!(ensure_with(&already_running), MeterStartOutcome::Running);
    }

    #[test]
    fn the_status_response_has_only_safe_fixed_values() {
        assert_eq!(
            serde_json::to_string(&MeterStartOutcome::NeedsAdmin).unwrap(),
            "\"NEEDS_ADMIN\""
        );
    }

    #[test]
    fn a_missing_service_is_installed_once_after_admin_and_server_checks() {
        let missing = FakeOps::new(ServiceState::Missing);
        let installer = FakeInstaller::ready();
        assert_eq!(
            ensure_with_install(&missing, &installer),
            MeterStartOutcome::Installed
        );
        assert_eq!(installer.installs.get(), 1);
        let running = FakeOps::new(ServiceState::Running);
        assert_eq!(
            ensure_with_install(&running, &installer),
            MeterStartOutcome::Running
        );
        assert_eq!(installer.installs.get(), 1);
    }

    #[test]
    fn installation_never_runs_with_wrong_server_missing_bundle_or_no_admin() {
        let missing = FakeOps::new(ServiceState::Missing);
        let mut installer = FakeInstaller::ready();
        installer.server_ready = false;
        assert_eq!(
            ensure_with_install(&missing, &installer),
            MeterStartOutcome::ServerUnavailable
        );
        installer.server_ready = true;
        installer.admin = false;
        assert_eq!(
            ensure_with_install(&missing, &installer),
            MeterStartOutcome::NeedsAdmin
        );
        installer.admin = true;
        installer.bundled = false;
        assert_eq!(
            ensure_with_install(&missing, &installer),
            MeterStartOutcome::NotInstalled
        );
        assert_eq!(installer.installs.get(), 0);
    }

    #[test]
    fn failed_installation_is_never_reported_as_running() {
        let missing = FakeOps::new(ServiceState::Missing);
        let mut installer = FakeInstaller::ready();
        installer.install_succeeds = false;
        assert_eq!(
            ensure_with_install(&missing, &installer),
            MeterStartOutcome::InstallFailed
        );
        assert_eq!(installer.installs.get(), 1);
    }
}
