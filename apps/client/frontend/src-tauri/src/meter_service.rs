use serde::Serialize;

fn connection_needs_configuration(bytes: &[u8], server_url: &str, bundled_hash: &str) -> bool {
    if bytes.len() > 4096 || !is_sha256_hex(bundled_hash) {
        return true;
    }
    let Ok(connection) = serde_json::from_slice::<serde_json::Value>(bytes) else {
        return true;
    };
    let Some(configured_url) = connection
        .get("serverUrl")
        .and_then(serde_json::Value::as_str)
    else {
        return true;
    };
    let Some(configured_hash) = connection
        .get("binaryHash")
        .and_then(serde_json::Value::as_str)
    else {
        return true;
    };

    configured_url != server_url
        || !is_sha256_hex(configured_hash)
        || !configured_hash.eq_ignore_ascii_case(bundled_hash)
}

fn is_sha256_hex(hash: &str) -> bool {
    hash.len() == 64 && hash.bytes().all(|byte| byte.is_ascii_hexdigit())
}

fn setup_failure_message(bytes: &[u8]) -> Option<String> {
    if bytes.len() > 4096 {
        return None;
    }
    let status: serde_json::Value = serde_json::from_slice(bytes).ok()?;
    if status.get("state")?.as_str()? != "FAILED" {
        return None;
    }
    let stage = status.get("stage")?.as_str()?;
    if !matches!(
        stage,
        "ADMIN_CHECK"
            | "CONFIG_VALIDATE"
            | "SERVER_URL_VALIDATE"
            | "INSTALL_DIRECTORY"
            | "SOURCE_BINARY"
            | "EXISTING_SERVICE_CHECK"
            | "BINARY_CHECK"
            | "PREPARE_FILES"
            | "PROTECT_FILES"
            | "SERVICE_REGISTER"
            | "SERVICE_RECOVERY"
            | "SERVICE_START"
            | "SCRIPT_START"
            | "INSTALL_TIMEOUT"
    ) {
        return None;
    }
    let reason = status
        .get("reason")
        .and_then(serde_json::Value::as_str)
        .unwrap_or("");
    let specific = match reason {
        "SERVER_URL_INVALID" => Some("服务器地址格式无效，请填写完整的 http 或 https 地址。"),
        "INSTALL_DIRECTORY_UNAVAILABLE" => {
            Some("无法访问 Windows 提供的服务安装目录，请检查目录权限。")
        }
        "BUNDLED_BINARY_MISSING" => Some("安装包中的计费程序未找到，请重新安装完整安装包。"),
        "EXISTING_CONFIGURATION_INVALID" => Some("无法读取现有计费服务配置，请检查服务安装目录。"),
        "WINDOWS_PROFILE_MISMATCH" => {
            Some("现有计费服务属于其他 Windows 用户或设备，不能直接改绑。")
        }
        _ => None,
    };
    if let Some(message) = specific {
        return Some(format!("{message}（{stage}/{reason}）"));
    }
    let message = match stage {
        "ADMIN_CHECK" => "安装需要管理员权限，请右键以管理员身份运行客户端。",
        "CONFIG_VALIDATE" => "安装配置无效，请检查服务器地址或现有服务的用户归属。",
        "SERVER_URL_VALIDATE" => "安装程序无法解析服务器地址，请检查已保存的地址。",
        "INSTALL_DIRECTORY" => "Windows 服务安装目录不可用，请检查系统目录权限。",
        "SOURCE_BINARY" => "无法找到随包计费程序，请重新安装完整安装包。",
        "EXISTING_SERVICE_CHECK" => "现有计费服务配置无法读取，请检查服务安装目录。",
        "BINARY_CHECK" => "计费程序无法在这台电脑运行，请重新安装最新版客户端。",
        "PREPARE_FILES" => "无法准备计费服务文件，请检查安装目录权限与设备绑定。",
        "PROTECT_FILES" => "无法设置计费服务目录权限，请检查 Windows 安全策略。",
        "SERVICE_REGISTER" => "Windows 服务注册失败，请检查管理员权限及安全软件拦截。",
        "SERVICE_RECOVERY" => "无法设置计费服务的自动恢复，请检查 Windows 服务策略。",
        "SERVICE_START" => "计费服务已注册但启动失败，请重新安装最新版后点击重试。",
        "SCRIPT_START" => "无法运行安装程序，请检查 Windows PowerShell 是否被禁用。",
        "INSTALL_TIMEOUT" => "计费服务安装超时，请检查 Windows 服务状态后重试。",
        _ => return None,
    };
    Some(format!("{message}（{stage}）"))
}

pub(crate) fn setup_error(app: &tauri::AppHandle) -> Option<String> {
    use tauri::Manager;
    let file = app
        .path()
        .app_data_dir()
        .ok()?
        .join("meter-setup-status.json");
    let metadata = std::fs::symlink_metadata(&file).ok()?;
    if !metadata.is_file() || metadata.len() > 4096 {
        return None;
    }
    setup_failure_message(&std::fs::read(file).ok()?)
}

#[derive(Clone, Copy, Debug, Eq, PartialEq, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub(crate) enum MeterStartOutcome {
    Running,
    Started,
    Installed,
    NotInstalled,
    NeedsAdmin,
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
    fn needs_configuration(&self) -> bool {
        false
    }
    fn has_admin_rights(&self) -> bool;
    fn install(&self) -> bool;
}

fn ensure_with_install(ops: &impl ServiceOps, installer: &impl InstallOps) -> MeterStartOutcome {
    if ops.state() != ServiceState::Missing && !installer.needs_configuration() {
        let outcome = ensure_with(ops);
        if outcome != MeterStartOutcome::Failed || ops.state() != ServiceState::Stopped {
            return outcome;
        }
        // A prior setup may have registered a service whose executable cannot
        // start. An explicit retry can replace it while retaining its identity.
    }
    if !installer.bundle_ready() {
        return MeterStartOutcome::NotInstalled;
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
    use sha2::{Digest, Sha256};
    use std::ffi::{OsStr, OsString};
    use std::fs;
    use std::io::Read;
    use std::mem::size_of;
    use std::os::windows::ffi::{OsStrExt, OsStringExt};
    use std::os::windows::process::CommandExt;
    use std::path::{Path, PathBuf};
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

        fn record_failure(&self, stage: &str) {
            if let Ok(directory) = self.app.path().app_data_dir() {
                if fs::create_dir_all(&directory).is_ok() {
                    let summary = serde_json::json!({ "state": "FAILED", "stage": stage });
                    let _ = fs::write(
                        directory.join("meter-setup-status.json"),
                        summary.to_string(),
                    );
                }
            }
        }
    }

    fn sha256_file_hex(path: &Path) -> Option<String> {
        let mut file = fs::File::open(path).ok()?;
        let mut hasher = Sha256::new();
        let mut buffer = [0u8; 64 * 1024];
        loop {
            let bytes_read = file.read(&mut buffer).ok()?;
            if bytes_read == 0 {
                break;
            }
            hasher.update(&buffer[..bytes_read]);
        }
        Some(format!("{:x}", hasher.finalize()))
    }

    impl InstallOps for WindowsInstaller {
        fn bundle_ready(&self) -> bool {
            self.resource("install-meter-service.ps1").is_some()
                && self.resource("quota-guard-meter.exe").is_some()
        }

        fn needs_configuration(&self) -> bool {
            let Some(binary) = self.resource("quota-guard-meter.exe") else {
                return true;
            };
            let Some(bundled_hash) = sha256_file_hex(&binary) else {
                return true;
            };
            let Ok(directory) = self.app.path().app_data_dir() else {
                return true;
            };
            let file = directory.join("meter-connection.json");
            let Ok(metadata) = fs::symlink_metadata(&file) else {
                return true;
            };
            if !metadata.is_file() || metadata.len() > 4096 {
                return true;
            }
            let Ok(bytes) = fs::read(file) else {
                return true;
            };
            super::connection_needs_configuration(&bytes, &self.server_url, &bundled_hash)
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
                self.record_failure("SCRIPT_START");
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
                self.record_failure("SCRIPT_START");
                return false;
            };

            let deadline = Instant::now() + Duration::from_secs(90);
            loop {
                match child.try_wait() {
                    Ok(Some(status)) => return status.success(),
                    Ok(None) if Instant::now() < deadline => {
                        thread::sleep(Duration::from_millis(200))
                    }
                    _ => {
                        let _ = child.kill();
                        let _ = child.wait();
                        self.record_failure("INSTALL_TIMEOUT");
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
        needs_configuration: bool,
        connection: Option<(Vec<u8>, String, String)>,
        admin: bool,
        install_succeeds: bool,
        installs: Cell<u32>,
    }

    impl FakeInstaller {
        fn ready() -> Self {
            Self {
                bundled: true,
                needs_configuration: false,
                connection: None,
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
        fn needs_configuration(&self) -> bool {
            self.connection
                .as_ref()
                .map_or(self.needs_configuration, |(bytes, server_url, hash)| {
                    connection_needs_configuration(bytes, server_url, hash)
                })
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
    fn a_missing_service_can_be_installed_offline_after_admin_checks() {
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
    fn installation_never_runs_with_missing_bundle_or_no_admin() {
        let missing = FakeOps::new(ServiceState::Missing);
        let mut installer = FakeInstaller::ready();
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
    fn a_registered_service_that_cannot_start_is_repaired_with_the_bundle() {
        let mut stopped = FakeOps::new(ServiceState::Stopped);
        stopped.start = StartAttempt::Failed;
        let installer = FakeInstaller::ready();
        assert_eq!(
            ensure_with_install(&stopped, &installer),
            MeterStartOutcome::Installed
        );
        assert_eq!(stopped.starts.get(), 1);
        assert_eq!(installer.installs.get(), 1);
    }

    #[test]
    fn a_changed_server_address_is_applied_even_if_the_service_is_running() {
        let running = FakeOps::new(ServiceState::Running);
        let mut installer = FakeInstaller::ready();
        installer.needs_configuration = true;
        assert_eq!(
            ensure_with_install(&running, &installer),
            MeterStartOutcome::Installed
        );
        assert_eq!(installer.installs.get(), 1);
        installer.admin = false;
        assert_eq!(
            ensure_with_install(&running, &installer),
            MeterStartOutcome::NeedsAdmin
        );
        assert_eq!(installer.installs.get(), 1);
    }

    #[test]
    fn a_matching_url_and_bundled_hash_does_not_require_service_reconfiguration() {
        let url = "http://127.0.0.1:8080";
        let hash = "0123456789abcdef".repeat(4);
        let bytes = connection_bytes(url, Some(&hash.to_uppercase()));

        assert!(!connection_needs_configuration(&bytes, url, &hash));
    }

    #[test]
    fn missing_invalid_or_mismatched_binary_hash_requires_service_reconfiguration() {
        let url = "http://127.0.0.1:8080";
        let bundled_hash = "0123456789abcdef".repeat(4);
        let stale_hash = "fedcba9876543210".repeat(4);

        assert!(connection_needs_configuration(
            &connection_bytes(url, None),
            url,
            &bundled_hash
        ));
        assert!(connection_needs_configuration(
            &connection_bytes(url, Some("not-a-sha256")),
            url,
            &bundled_hash
        ));
        assert!(connection_needs_configuration(
            &connection_bytes(url, Some(&stale_hash)),
            url,
            &bundled_hash
        ));
        assert!(connection_needs_configuration(
            b"{invalid",
            url,
            &bundled_hash
        ));
        assert!(connection_needs_configuration(
            &connection_bytes(url, Some(&bundled_hash)),
            url,
            "invalid-bundled-hash"
        ));
    }

    #[test]
    fn a_changed_server_url_requires_service_reconfiguration_even_when_hash_matches() {
        let current_url = "http://127.0.0.1:8080";
        let previous_url = "https://quota.example.com";
        let hash = "0123456789abcdef".repeat(4);

        assert!(connection_needs_configuration(
            &connection_bytes(previous_url, Some(&hash)),
            current_url,
            &hash
        ));
    }

    #[test]
    fn a_running_service_with_the_same_url_and_old_hash_requires_admin_then_installs() {
        let running = FakeOps::new(ServiceState::Running);
        let url = "http://127.0.0.1:8080";
        let bundled_hash = "0123456789abcdef".repeat(4);
        let old_hash = "fedcba9876543210".repeat(4);
        let mut installer = FakeInstaller::ready();
        installer.connection = Some((
            connection_bytes(url, Some(&old_hash)),
            url.to_owned(),
            bundled_hash,
        ));
        installer.admin = false;

        assert_eq!(
            ensure_with_install(&running, &installer),
            MeterStartOutcome::NeedsAdmin
        );
        assert_eq!(installer.installs.get(), 0);

        installer.admin = true;
        assert_eq!(
            ensure_with_install(&running, &installer),
            MeterStartOutcome::Installed
        );
        assert_eq!(installer.installs.get(), 1);
    }

    #[test]
    fn a_running_service_with_the_current_url_and_hash_is_not_reinstalled() {
        let running = FakeOps::new(ServiceState::Running);
        let url = "http://127.0.0.1:8080";
        let hash = "0123456789abcdef".repeat(4);
        let mut installer = FakeInstaller::ready();
        installer.connection = Some((connection_bytes(url, Some(&hash)), url.to_owned(), hash));

        assert_eq!(
            ensure_with_install(&running, &installer),
            MeterStartOutcome::Running
        );
        assert_eq!(installer.installs.get(), 0);
    }

    fn connection_bytes(server_url: &str, binary_hash: Option<&str>) -> Vec<u8> {
        let mut connection = serde_json::json!({ "serverUrl": server_url });
        if let Some(binary_hash) = binary_hash {
            connection["binaryHash"] = serde_json::Value::String(binary_hash.to_owned());
        }
        serde_json::to_vec(&connection).unwrap()
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

    #[test]
    fn installation_diagnostics_expose_only_known_step_messages() {
        assert!(setup_failure_message(
            br#"{"state":"FAILED","stage":"BINARY_CHECK","password":"do-not-show"}"#
        )
        .unwrap()
        .contains("BINARY_CHECK"));
        assert!(!setup_failure_message(
            br#"{"state":"FAILED","stage":"BINARY_CHECK","password":"do-not-show"}"#
        )
        .unwrap()
        .contains("do-not-show"));
        assert!(setup_failure_message(br#"{"state":"FAILED","stage":"unknown-secret"}"#).is_none());
        assert!(setup_failure_message(br#"{"state":"SUCCEEDED","stage":"READY"}"#).is_none());
    }

    #[test]
    fn installation_diagnostics_distinguish_directory_failures_from_server_errors() {
        let error = setup_failure_message(br#"{"state":"FAILED","stage":"INSTALL_DIRECTORY","reason":"INSTALL_DIRECTORY_UNAVAILABLE"}"#).unwrap();
        assert!(error.contains("安装目录"));
        assert!(!error.contains("服务器地址"));
        let error = setup_failure_message(
            br#"{"state":"FAILED","stage":"SOURCE_BINARY","reason":"BUNDLED_BINARY_MISSING"}"#,
        )
        .unwrap();
        assert!(error.contains("计费程序未找到"));
        assert!(!error.contains("服务器地址"));
    }
}
