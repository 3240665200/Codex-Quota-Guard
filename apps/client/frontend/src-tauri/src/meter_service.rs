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
            | "STAGE_BINARY"
            | "SERVICE_STOP"
            | "REPLACE_BINARY"
            | "VERIFY_INSTALL"
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
        "SERVICE_VERIFICATION_FAILED" => {
            Some("统计程序升级后的服务路径、版本或校验值未通过核验，请重试更新。")
        }
        "SERVER_URL_INVALID" => Some("服务器地址格式无效，请填写完整的 http 或 https 地址。"),
        "INSTALL_DIRECTORY_UNAVAILABLE" => {
            Some("无法访问 Windows 提供的服务安装目录，请检查目录权限。")
        }
        "ROLLBACK_FAILED" => Some(
            "升级失败且旧版统计程序未能自动恢复，请在 Windows 服务中检查 CodexQuotaGuardMeter。",
        ),
        "BUNDLED_BINARY_MISSING" => Some("安装包中的统计程序未找到，请重新安装完整安装包。"),
        "EXISTING_CONFIGURATION_INVALID" => Some("无法读取现有统计程序配置，请检查服务安装目录。"),
        "WINDOWS_PROFILE_MISMATCH" => {
            Some("现有统计程序属于其他 Windows 用户或设备，不能直接改绑。")
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
        "SOURCE_BINARY" => "无法找到随包统计程序，请重新安装完整安装包。",
        "EXISTING_SERVICE_CHECK" => "现有统计程序配置无法读取，请检查服务安装目录。",
        "BINARY_CHECK" => "统计程序无法在这台电脑运行，请重新安装最新版客户端。",
        "PREPARE_FILES" => "无法准备统计程序文件，请检查安装目录权限与设备绑定。",
        "STAGE_BINARY" => "无法准备新版统计程序文件，旧版未受影响。",
        "SERVICE_STOP" => "无法停止旧版统计程序，请检查 Windows 服务状态。",
        "REPLACE_BINARY" => "无法替换旧版统计程序，请检查安装目录权限。",
        "PROTECT_FILES" => "无法设置统计程序目录权限，请检查 Windows 安全策略。",
        "SERVICE_REGISTER" => "Windows 服务注册失败，请检查管理员权限及安全软件拦截。",
        "SERVICE_RECOVERY" => "无法设置统计程序的自动恢复，请检查 Windows 服务策略。",
        "SERVICE_START" => "统计程序已注册但启动失败，请重新安装最新版后点击重试。",
        "SCRIPT_START" => "无法运行安装程序，请检查 Windows PowerShell 是否被禁用。",
        "INSTALL_TIMEOUT" => "统计程序安装超时，请检查 Windows 服务状态后重试。",
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
pub(crate) enum MeterVersionState {
    Current,
    UpgradeRequired,
    NotInstalled,
    Unknown,
    #[cfg(not(windows))]
    Unsupported,
}

#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct MeterVersionStatus {
    pub installed_version: Option<String>,
    pub bundled_version: Option<String>,
    pub needs_upgrade: Option<bool>,
    pub status: MeterVersionState,
}

#[cfg(windows)]
pub(crate) fn version_status(app: &tauri::AppHandle) -> MeterVersionStatus {
    windows::version_status(app)
}

#[cfg(not(windows))]
pub(crate) fn version_status(_app: &tauri::AppHandle) -> MeterVersionStatus {
    MeterVersionStatus {
        installed_version: None,
        bundled_version: None,
        needs_upgrade: None,
        status: MeterVersionState::Unsupported,
    }
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
    fn install_elevated(&self) -> Result<bool, ()> {
        Err(())
    }
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
        return match installer.install_elevated() {
            Ok(true) if ops.wait_running() && !installer.needs_configuration() => {
                MeterStartOutcome::Installed
            }
            Ok(_) => MeterStartOutcome::InstallFailed,
            Err(()) => MeterStartOutcome::NeedsAdmin,
        };
    }
    if !installer.install() || !ops.wait_running() || installer.needs_configuration() {
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
    use super::{
        InstallOps, MeterVersionState, MeterVersionStatus, ServiceOps, ServiceState, StartAttempt,
    };
    use sha2::{Digest, Sha256};
    use std::ffi::{OsStr, OsString};
    use std::fs;
    use std::io::Read;
    use std::mem::size_of;
    use std::os::windows::ffi::{OsStrExt, OsStringExt};
    use std::os::windows::process::CommandExt;
    use std::path::{Path, PathBuf};
    use std::process::{Command, Stdio};
    use std::ptr;
    use std::thread;
    use std::time::{Duration, Instant};
    use tauri::{path::BaseDirectory, AppHandle, Manager};
    use windows_sys::Win32::Foundation::{
        CloseHandle, GetLastError, LocalFree, ERROR_ACCESS_DENIED, ERROR_CANCELLED,
        ERROR_SERVICE_ALREADY_RUNNING, ERROR_SERVICE_DOES_NOT_EXIST, WAIT_OBJECT_0,
    };
    use windows_sys::Win32::Security::Authorization::ConvertSidToStringSidW;
    use windows_sys::Win32::Security::{GetTokenInformation, TokenUser, TOKEN_QUERY, TOKEN_USER};
    use windows_sys::Win32::System::Services::{
        CloseServiceHandle, OpenSCManagerW, OpenServiceW, QueryServiceConfigW,
        QueryServiceStatusEx, StartServiceW, QUERY_SERVICE_CONFIGW, SC_HANDLE, SC_MANAGER_CONNECT,
        SC_MANAGER_CREATE_SERVICE, SC_STATUS_PROCESS_INFO, SERVICE_QUERY_CONFIG,
        SERVICE_QUERY_STATUS, SERVICE_RUNNING, SERVICE_START, SERVICE_START_PENDING,
        SERVICE_STATUS_PROCESS, SERVICE_STOPPED, SERVICE_STOP_PENDING,
    };
    use windows_sys::Win32::System::SystemInformation::GetSystemDirectoryW;
    use windows_sys::Win32::System::Threading::{
        GetCurrentProcess, GetExitCodeProcess, OpenProcessToken, WaitForSingleObject,
        CREATE_NO_WINDOW,
    };
    use windows_sys::Win32::UI::Shell::{
        ShellExecuteExW, ShellExecuteW, SEE_MASK_NOCLOSEPROCESS, SHELLEXECUTEINFOW,
    };
    use windows_sys::Win32::UI::WindowsAndMessaging::SW_HIDE;

    const SERVICE_NAME: &str = "CodexQuotaGuardMeter";
    const WAIT_TIMEOUT: Duration = Duration::from_secs(12);
    const VERSION_TIMEOUT: Duration = Duration::from_secs(2);

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

    fn valid_version(version: &str) -> bool {
        if version.is_empty()
            || version.len() > 40
            || !version
                .bytes()
                .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'.' | b'-' | b'+'))
        {
            return false;
        }
        let core = version.split(['-', '+']).next().unwrap_or("");
        let parts: Vec<_> = core.split('.').collect();
        parts.len() == 3
            && parts
                .iter()
                .all(|part| !part.is_empty() && part.bytes().all(|byte| byte.is_ascii_digit()))
    }

    fn parse_binary_version(output: &[u8]) -> Option<String> {
        let stdout = std::str::from_utf8(output).ok()?.trim();
        let version = stdout.strip_prefix("quota-guard-meter ")?;
        valid_version(version).then(|| version.to_owned())
    }

    fn binary_version(path: &Path) -> Option<String> {
        let mut child = Command::new(path)
            .arg("--version")
            .stdin(Stdio::null())
            .stdout(Stdio::piped())
            .stderr(Stdio::null())
            .creation_flags(CREATE_NO_WINDOW)
            .spawn()
            .ok()?;
        let deadline = Instant::now() + VERSION_TIMEOUT;
        let status = loop {
            match child.try_wait() {
                Ok(Some(status)) => break status,
                Ok(None) if Instant::now() < deadline => thread::sleep(Duration::from_millis(20)),
                _ => {
                    let _ = child.kill();
                    let _ = child.wait();
                    return None;
                }
            }
        };
        if !status.success() {
            return None;
        }
        let mut output = Vec::new();
        child
            .stdout
            .take()?
            .take(129)
            .read_to_end(&mut output)
            .ok()?;
        (output.len() <= 128)
            .then(|| parse_binary_version(&output))
            .flatten()
    }

    fn compare_versions(
        service_state: ServiceState,
        installed_hash: Option<&str>,
        bundled_hash: Option<&str>,
    ) -> (Option<bool>, MeterVersionState) {
        match service_state {
            ServiceState::Missing => (None, MeterVersionState::NotInstalled),
            _ => match installed_hash.zip(bundled_hash) {
                Some((installed, bundled)) if !installed.eq_ignore_ascii_case(bundled) => {
                    (Some(true), MeterVersionState::UpgradeRequired)
                }
                Some(_) if service_state == ServiceState::Failed => {
                    (None, MeterVersionState::Unknown)
                }
                Some(_) => (Some(false), MeterVersionState::Current),
                None => (None, MeterVersionState::Unknown),
            },
        }
    }

    pub(super) fn version_status(app: &AppHandle) -> MeterVersionStatus {
        let installer = WindowsInstaller::new(app);
        let bundled = installer.resource("quota-guard-meter.exe");
        let bundled_version = bundled.as_deref().and_then(binary_version);
        let bundled_hash = bundled.as_deref().and_then(sha256_file_hex);
        let service_state = WindowsServiceOps.state();
        if service_state == ServiceState::Missing {
            return MeterVersionStatus {
                installed_version: None,
                bundled_version,
                needs_upgrade: None,
                status: MeterVersionState::NotInstalled,
            };
        }
        // Read the registered service executable itself instead of trusting a
        // cached install record: the file may have been replaced independently.
        let installed_binary = service_binary_path();
        let installed_version = installed_binary.as_deref().and_then(binary_version);
        let installed_hash = installed_binary.as_deref().and_then(sha256_file_hex);
        let (needs_upgrade, status) = compare_versions(
            service_state,
            installed_hash.as_deref(),
            bundled_hash.as_deref(),
        );
        MeterVersionStatus {
            installed_version,
            bundled_version,
            needs_upgrade,
            status,
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

    fn system_powershell() -> Option<PathBuf> {
        let mut path = system_directory()?;
        path.push("WindowsPowerShell");
        path.push("v1.0");
        path.push("powershell.exe");
        path.is_file().then_some(path)
    }

    fn current_user_sid() -> Option<String> {
        let mut token = ptr::null_mut();
        // SAFETY: GetCurrentProcess returns a pseudo-handle valid for this call.
        if unsafe { OpenProcessToken(GetCurrentProcess(), TOKEN_QUERY, &mut token) } == 0 {
            return None;
        }
        let result = (|| {
            let mut length = 0u32;
            // SAFETY: The first call only requests the required buffer length.
            unsafe { GetTokenInformation(token, TokenUser, ptr::null_mut(), 0, &mut length) };
            if length < size_of::<TOKEN_USER>() as u32 || length > 4096 {
                return None;
            }
            let words = (length as usize).div_ceil(size_of::<usize>());
            let mut buffer = vec![0usize; words];
            // SAFETY: The aligned buffer is at least `length` bytes long.
            if unsafe {
                GetTokenInformation(
                    token,
                    TokenUser,
                    buffer.as_mut_ptr().cast(),
                    length,
                    &mut length,
                )
            } == 0
            {
                return None;
            }
            // SAFETY: A successful TokenUser query writes this structure into the buffer.
            let user = unsafe { &*(buffer.as_ptr().cast::<TOKEN_USER>()) };
            let mut sid_text = ptr::null_mut();
            // SAFETY: The returned SID points into `buffer`; the API allocates sid_text.
            if unsafe { ConvertSidToStringSidW(user.User.Sid, &mut sid_text) } == 0 {
                return None;
            }
            let mut count = 0usize;
            // SAFETY: ConvertSidToStringSidW returns a NUL-terminated string.
            while count < 256 && unsafe { *sid_text.add(count) } != 0 {
                count += 1;
            }
            let parsed = (count < 256).then(|| {
                // SAFETY: `count` was bounded by the NUL terminator above.
                String::from_utf16_lossy(unsafe { std::slice::from_raw_parts(sid_text, count) })
            });
            // SAFETY: sid_text was allocated by ConvertSidToStringSidW.
            unsafe { LocalFree(sid_text.cast()) };
            parsed.filter(|sid| sid.starts_with("S-1-") && sid.len() < 256)
        })();
        // SAFETY: token was opened successfully above.
        unsafe { CloseHandle(token) };
        result
    }

    fn powershell_quote(value: &str) -> String {
        format!("\"{}\"", value.replace('"', "\\\""))
    }

    fn powershell_install_parameters(
        script: &Path,
        binary: &Path,
        server_url: &str,
        expected_user_sid: &str,
    ) -> String {
        format!(
            "-NoLogo -NoProfile -NonInteractive -ExecutionPolicy Bypass -File {} -BinaryPath {} -ServerUrl {} -ExpectedUserSid {}",
            powershell_quote(&script.to_string_lossy()),
            powershell_quote(&binary.to_string_lossy()),
            powershell_quote(server_url),
            powershell_quote(expected_user_sid)
        )
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
            let Some(installed_binary) = service_binary_path() else {
                return true;
            };
            let Some(installed_hash) = sha256_file_hex(&installed_binary) else {
                return true;
            };
            if !installed_hash.eq_ignore_ascii_case(&bundled_hash) {
                return true;
            }
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
            let (Some(script), Some(binary), Some(powershell)) = (
                self.resource("install-meter-service.ps1"),
                self.resource("quota-guard-meter.exe"),
                system_powershell(),
            ) else {
                return false;
            };
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

            let deadline = Instant::now() + Duration::from_secs(120);
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

        fn install_elevated(&self) -> Result<bool, ()> {
            let (Some(script), Some(binary), Some(powershell)) = (
                self.resource("install-meter-service.ps1"),
                self.resource("quota-guard-meter.exe"),
                system_powershell(),
            ) else {
                self.record_failure("SCRIPT_START");
                return Ok(false);
            };
            let Some(user_sid) = current_user_sid() else {
                self.record_failure("ADMIN_CHECK");
                return Ok(false);
            };
            let parameters =
                powershell_install_parameters(&script, &binary, &self.server_url, &user_sid);
            let verb = wide(OsStr::new("runas"));
            let file = wide(powershell.as_os_str());
            let parameters = wide(OsStr::new(&parameters));
            let mut info = SHELLEXECUTEINFOW {
                cbSize: size_of::<SHELLEXECUTEINFOW>() as u32,
                fMask: SEE_MASK_NOCLOSEPROCESS,
                hwnd: ptr::null_mut(),
                lpVerb: verb.as_ptr(),
                lpFile: file.as_ptr(),
                lpParameters: parameters.as_ptr(),
                lpDirectory: ptr::null(),
                nShow: SW_HIDE,
                ..Default::default()
            };
            // SAFETY: All pointers reference NUL-terminated strings and remain valid
            // until ShellExecuteExW returns. Only the fixed system PowerShell and
            // resource-relative installer are elevated.
            let result = unsafe { ShellExecuteExW(&mut info) };
            if result == 0 {
                // A cancelled UAC dialog does not mean the bundled installer failed.
                return if unsafe { GetLastError() } == ERROR_CANCELLED {
                    Err(())
                } else {
                    self.record_failure("SCRIPT_START");
                    Ok(false)
                };
            }
            if info.hProcess.is_null() {
                self.record_failure("SCRIPT_START");
                return Ok(false);
            }
            // SAFETY: hProcess is returned by ShellExecuteExW when the mask requests it.
            let wait = unsafe { WaitForSingleObject(info.hProcess, 120_000) };
            let mut exit_code = u32::MAX;
            let exited_successfully = wait == WAIT_OBJECT_0
                && unsafe { GetExitCodeProcess(info.hProcess, &mut exit_code) } != 0
                && exit_code == 0;
            // SAFETY: hProcess is an owned process handle returned by ShellExecuteExW.
            unsafe { CloseHandle(info.hProcess) };
            if wait != WAIT_OBJECT_0 {
                self.record_failure("INSTALL_TIMEOUT");
            }
            Ok(exited_successfully)
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

    fn service_binary_path() -> Option<PathBuf> {
        let handles = open_service(SERVICE_QUERY_CONFIG).ok()?;
        let mut required = 0u32;
        // SAFETY: The first call asks SCM for the required output size only.
        unsafe { QueryServiceConfigW(handles.service, ptr::null_mut(), 0, &mut required) };
        if required < size_of::<QUERY_SERVICE_CONFIGW>() as u32 || required > 1024 * 1024 {
            return None;
        }
        let mut buffer = vec![0usize; (required as usize).div_ceil(size_of::<usize>())];
        // SAFETY: The aligned output buffer is at least `required` bytes long.
        if unsafe {
            QueryServiceConfigW(
                handles.service,
                buffer.as_mut_ptr().cast(),
                required,
                &mut required,
            )
        } == 0
        {
            return None;
        }
        // SAFETY: QueryServiceConfigW populated this structure in `buffer`.
        let config = unsafe { &*(buffer.as_ptr().cast::<QUERY_SERVICE_CONFIGW>()) };
        if config.lpBinaryPathName.is_null() {
            return None;
        }
        let mut length = 0usize;
        // SAFETY: SCM returns a NUL-terminated string within the supplied buffer.
        while length < 32768 && unsafe { *config.lpBinaryPathName.add(length) } != 0 {
            length += 1;
        }
        if length == 0 || length == 32768 {
            return None;
        }
        // SAFETY: `length` stopped at the terminator and remains in the SCM buffer.
        let command_line = String::from_utf16_lossy(unsafe {
            std::slice::from_raw_parts(config.lpBinaryPathName, length)
        });
        let command_line = command_line.trim_start();
        let executable = if let Some(quoted) = command_line.strip_prefix('"') {
            let end = quoted.find('"')?;
            quoted.get(..end)?
        } else {
            command_line
                .split_once(char::is_whitespace)
                .map_or(command_line, |(path, _)| path)
        };
        (!executable.is_empty()).then(|| PathBuf::from(executable))
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
    fn version_output_requires_expected_program_and_semver() {
        assert_eq!(
            parse_binary_version(b"quota-guard-meter 0.2.0\r\n"),
            Some("0.2.0".into())
        );
        assert_eq!(parse_binary_version(b"other 0.2.0\n"), None);
        assert_eq!(parse_binary_version(b"quota-guard-meter invalid\n"), None);
        assert_eq!(
            parse_binary_version(b"quota-guard-meter 0.2.0\nextra"),
            None
        );
    }

    #[test]
    fn version_comparison_never_marks_failed_service_as_current() {
        let hash = "0123456789abcdef".repeat(4);
        assert_eq!(
            compare_versions(ServiceState::Failed, Some(&hash), Some(&hash)),
            (None, MeterVersionState::Unknown)
        );
        assert_eq!(
            compare_versions(ServiceState::Failed, Some(&hash), Some("different")),
            (Some(true), MeterVersionState::UpgradeRequired)
        );
        assert_eq!(
            compare_versions(ServiceState::Missing, Some(&hash), Some(&hash)),
            (None, MeterVersionState::NotInstalled)
        );
        assert_eq!(
            compare_versions(ServiceState::Running, Some(&hash), Some(&hash)),
            (Some(false), MeterVersionState::Current)
        );
        assert_eq!(
            compare_versions(ServiceState::Stopped, Some(&hash), Some("different")),
            (Some(true), MeterVersionState::UpgradeRequired)
        );
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
        elevated_result: Result<bool, ()>,
        installs: Cell<u32>,
        elevated_installs: Cell<u32>,
        installed: Cell<bool>,
    }

    impl FakeInstaller {
        fn ready() -> Self {
            Self {
                bundled: true,
                needs_configuration: false,
                connection: None,
                admin: true,
                install_succeeds: true,
                elevated_result: Err(()),
                installs: Cell::new(0),
                elevated_installs: Cell::new(0),
                installed: Cell::new(false),
            }
        }
    }

    impl InstallOps for FakeInstaller {
        fn bundle_ready(&self) -> bool {
            self.bundled
        }
        fn needs_configuration(&self) -> bool {
            if self.installed.get() {
                return false;
            }
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
            self.installed.set(self.install_succeeds);
            self.install_succeeds
        }
        fn install_elevated(&self) -> Result<bool, ()> {
            self.elevated_installs.set(self.elevated_installs.get() + 1);
            if self.elevated_result == Ok(true) {
                self.installed.set(true);
            }
            self.elevated_result
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
    fn outdated_running_service_requests_one_elevated_install() {
        let running = FakeOps::new(ServiceState::Running);
        let mut installer = FakeInstaller::ready();
        installer.needs_configuration = true;
        installer.admin = false;
        installer.elevated_result = Ok(true);
        assert_eq!(
            ensure_with_install(&running, &installer),
            MeterStartOutcome::Installed
        );
        assert_eq!(installer.elevated_installs.get(), 1);
        assert_eq!(installer.installs.get(), 0);
        installer.needs_configuration = false;
        assert_eq!(
            ensure_with_install(&running, &installer),
            MeterStartOutcome::Running
        );
        assert_eq!(installer.elevated_installs.get(), 1);
    }

    #[test]
    fn elevated_install_requires_running_service_and_updated_identity() {
        let mut missing = FakeOps::new(ServiceState::Missing);
        let mut installer = FakeInstaller::ready();
        installer.admin = false;
        installer.elevated_result = Ok(true);
        assert_eq!(
            ensure_with_install(&missing, &installer),
            MeterStartOutcome::Installed
        );
        missing.running = false;
        assert_eq!(
            ensure_with_install(&missing, &installer),
            MeterStartOutcome::InstallFailed
        );
        installer.elevated_result = Err(());
        assert_eq!(
            ensure_with_install(&missing, &installer),
            MeterStartOutcome::NeedsAdmin
        );
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
            MeterStartOutcome::Running
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
        assert!(setup_failure_message(
            br#"{"state":"FAILED","stage":"VERIFY_INSTALL","reason":"SERVICE_VERIFICATION_FAILED"}"#
        )
        .unwrap()
        .contains("校验值未通过核验"));
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
        assert!(error.contains("统计程序未找到"));
        assert!(!error.contains("服务器地址"));
    }
}
