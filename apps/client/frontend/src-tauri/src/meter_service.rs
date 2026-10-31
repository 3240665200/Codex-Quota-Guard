//! Read-only observations and explicit user-requested maintenance.
//! Repairs run the bundled installer helper with Windows administrator approval;
//! application startup never installs or restarts the independent service.

use serde::Serialize;

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

fn assess_version(
    installed: Option<&str>,
    bundled: Option<&str>,
    current_hash: Option<&str>,
    bundled_hash: Option<&str>,
    running: bool,
) -> (Option<bool>, MeterVersionState) {
    let order = installed
        .and_then(|v| semver::Version::parse(v).ok())
        .zip(bundled.and_then(|v| semver::Version::parse(v).ok()))
        .map(|(a, b)| a.cmp(&b));
    match order {
        Some(std::cmp::Ordering::Greater) => (
            Some(false),
            if running {
                MeterVersionState::Current
            } else {
                MeterVersionState::Unknown
            },
        ),
        Some(std::cmp::Ordering::Less) => (Some(true), MeterVersionState::UpgradeRequired),
        _ => match (current_hash, bundled_hash) {
            (Some(a), Some(b)) if !a.eq_ignore_ascii_case(b) => {
                (Some(true), MeterVersionState::UpgradeRequired)
            }
            (Some(_), Some(_)) if running => (Some(false), MeterVersionState::Current),
            _ => (None, MeterVersionState::Unknown),
        },
    }
}

#[cfg(test)]
mod independent_version_tests {
    use super::*;
    #[test]
    fn a_newer_independent_service_is_current_even_with_a_different_bundled_hash() {
        assert_eq!(
            assess_version(
                Some("0.2.14"),
                Some("0.2.13"),
                Some("newer"),
                Some("bundled"),
                true
            ),
            (Some(false), MeterVersionState::Current)
        );
    }
    #[test]
    fn old_or_modified_same_version_services_still_require_repair() {
        assert_eq!(
            assess_version(Some("0.2.12"), Some("0.2.13"), Some("a"), Some("a"), true),
            (Some(true), MeterVersionState::UpgradeRequired)
        );
        assert_eq!(
            assess_version(Some("0.2.13"), Some("0.2.13"), Some("a"), Some("b"), true),
            (Some(true), MeterVersionState::UpgradeRequired)
        );
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub(crate) enum MeterSync {
    Updated,
    Unchanged,
    NotInstalled,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum ServiceObservation {
    Running,
    Stopped,
    NotInstalled,
    Unknown,
}

pub(crate) fn observe_service() -> ServiceObservation {
    #[cfg(windows)]
    {
        windows::observe_service()
    }
    #[cfg(not(windows))]
    {
        ServiceObservation::NotInstalled
    }
}

pub(crate) fn service_settings() -> (Option<bool>, Option<bool>) {
    #[cfg(windows)]
    {
        windows::service_settings()
    }
    #[cfg(not(windows))]
    {
        (None, None)
    }
}

pub(crate) fn repair_service(app: &tauri::AppHandle) -> Result<(), String> {
    #[cfg(windows)]
    {
        windows::repair_service(app)
    }
    #[cfg(not(windows))]
    {
        let _ = app;
        Err("自修复目前仅支持 Windows。".into())
    }
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

/// Update the endpoint of a service that is already installed. The fixed
/// bundled script validates the service identity and only changes its config.
/// It is intentionally never used to install or replace the service binary.
#[cfg(windows)]
pub(crate) fn configure_existing_service(
    app: &tauri::AppHandle,
    server_url: &str,
) -> Result<MeterSync, String> {
    windows::configure_existing_service(app, server_url)
}

#[cfg(not(windows))]
pub(crate) fn configure_existing_service(
    _app: &tauri::AppHandle,
    _server_url: &str,
) -> Result<MeterSync, String> {
    Ok(MeterSync::NotInstalled)
}

#[cfg(windows)]
mod windows {
    use super::{MeterSync, MeterVersionState, MeterVersionStatus, ServiceObservation};
    use sha2::{Digest, Sha256};
    use std::{
        ffi::{OsStr, OsString},
        fs::File,
        io::Read,
        mem::size_of,
        os::windows::{
            ffi::{OsStrExt, OsStringExt},
            process::CommandExt,
        },
        path::{Path, PathBuf},
        process::{Command, Stdio},
        ptr, thread,
        time::{Duration, Instant},
    };
    use tauri::{path::BaseDirectory, AppHandle, Manager};
    use windows_sys::Win32::{
        Foundation::{CloseHandle, GetLastError, LocalFree, ERROR_SERVICE_DOES_NOT_EXIST, HANDLE},
        Security::{
            Authorization::ConvertSidToStringSidW, GetTokenInformation, TokenUser, TOKEN_QUERY,
            TOKEN_USER,
        },
        System::{
            Services::{
                CloseServiceHandle, OpenSCManagerW, OpenServiceW, QueryServiceConfig2W,
                QueryServiceConfigW, QueryServiceStatusEx, QUERY_SERVICE_CONFIGW, SC_ACTION,
                SC_ACTION_RESTART, SC_HANDLE, SC_MANAGER_CONNECT, SC_STATUS_PROCESS_INFO,
                SERVICE_AUTO_START, SERVICE_CONFIG_FAILURE_ACTIONS,
                SERVICE_CONFIG_FAILURE_ACTIONS_FLAG, SERVICE_FAILURE_ACTIONSW,
                SERVICE_FAILURE_ACTIONS_FLAG, SERVICE_QUERY_CONFIG, SERVICE_QUERY_STATUS,
                SERVICE_RUNNING, SERVICE_STATUS_PROCESS,
            },
            SystemInformation::GetSystemDirectoryW,
            Threading::{
                GetCurrentProcess, GetExitCodeProcess, OpenProcessToken, WaitForSingleObject,
                CREATE_NO_WINDOW,
            },
        },
        UI::{
            Shell::{ShellExecuteExW, SEE_MASK_NOCLOSEPROCESS, SHELLEXECUTEINFOW},
            WindowsAndMessaging::SW_HIDE,
        },
    };

    const SERVICE_NAME: &str = "CodexQuotaGuardMeter";
    const CONFIGURE_TIMEOUT: Duration = Duration::from_secs(200);

    struct Service(SC_HANDLE);
    impl Drop for Service {
        fn drop(&mut self) {
            unsafe { CloseServiceHandle(self.0) };
        }
    }
    struct Handle(HANDLE);
    impl Drop for Handle {
        fn drop(&mut self) {
            unsafe { CloseHandle(self.0) };
        }
    }

    fn wide(value: &OsStr) -> Vec<u16> {
        value.encode_wide().chain(Some(0)).collect()
    }

    fn system_powershell() -> Option<PathBuf> {
        let mut buffer = vec![0u16; 32768];
        let length = unsafe { GetSystemDirectoryW(buffer.as_mut_ptr(), buffer.len() as u32) };
        if length == 0 || length as usize >= buffer.len() {
            return None;
        }
        let mut path = PathBuf::from(OsString::from_wide(&buffer[..length as usize]));
        path.push("WindowsPowerShell");
        path.push("v1.0");
        path.push("powershell.exe");
        path.is_file().then_some(path)
    }

    fn service_with_access(access: u32) -> Result<Option<Service>, String> {
        let manager = unsafe { OpenSCManagerW(ptr::null(), ptr::null(), SC_MANAGER_CONNECT) };
        if manager.is_null() {
            return Err("无法读取 Windows 统计服务状态。".into());
        }
        let manager = Service(manager);
        let name = wide(OsStr::new(SERVICE_NAME));
        let raw = unsafe { OpenServiceW(manager.0, name.as_ptr(), access) };
        if raw.is_null() {
            return if unsafe { GetLastError() } == ERROR_SERVICE_DOES_NOT_EXIST {
                Ok(None)
            } else {
                Err("无法读取 Windows 统计服务状态。".into())
            };
        }
        Ok(Some(Service(raw)))
    }

    fn service() -> Result<Option<Service>, String> {
        service_with_access(SERVICE_QUERY_STATUS)
    }

    pub(super) fn service_settings() -> (Option<bool>, Option<bool>) {
        let Ok(Some(service)) = service_with_access(SERVICE_QUERY_CONFIG) else {
            return (None, None);
        };
        // SCM configuration is bounded to 8 KiB. Word alignment is required by
        // the structures returned by Windows; no executable path is exposed.
        let mut buffer = vec![0usize; 8192 / size_of::<usize>()];
        let mut needed = 0u32;
        let automatic = if unsafe {
            QueryServiceConfigW(service.0, buffer.as_mut_ptr().cast(), 8192, &mut needed)
        } != 0
        {
            Some(
                unsafe { &*buffer.as_ptr().cast::<QUERY_SERVICE_CONFIGW>() }.dwStartType
                    == SERVICE_AUTO_START,
            )
        } else {
            None
        };
        let recovery = if unsafe {
            QueryServiceConfig2W(
                service.0,
                SERVICE_CONFIG_FAILURE_ACTIONS,
                buffer.as_mut_ptr().cast(),
                8192,
                &mut needed,
            )
        } != 0
        {
            let policy = unsafe { &*buffer.as_ptr().cast::<SERVICE_FAILURE_ACTIONSW>() };
            let start = buffer.as_ptr() as usize;
            let actions = policy.lpsaActions as usize;
            let count = policy.cActions as usize;
            let bounded = count >= 3
                && count <= 64
                && actions >= start
                && actions
                    .checked_add(count * size_of::<SC_ACTION>())
                    .is_some_and(|end| end <= start + 8192)
                && actions % std::mem::align_of::<SC_ACTION>() == 0;
            let restarts = bounded
                && unsafe { std::slice::from_raw_parts(policy.lpsaActions, count) }
                    .iter()
                    .all(|action| action.Type == SC_ACTION_RESTART && action.Delay <= 60_000);
            let mut flag = SERVICE_FAILURE_ACTIONS_FLAG::default();
            let flag_ok = unsafe {
                QueryServiceConfig2W(
                    service.0,
                    SERVICE_CONFIG_FAILURE_ACTIONS_FLAG,
                    (&mut flag as *mut SERVICE_FAILURE_ACTIONS_FLAG).cast(),
                    size_of::<SERVICE_FAILURE_ACTIONS_FLAG>() as u32,
                    &mut needed,
                )
            } != 0;
            if flag_ok {
                Some(restarts && flag.fFailureActionsOnNonCrashFailures != 0)
            } else {
                None
            }
        } else {
            None
        };
        (automatic, recovery)
    }

    pub(super) fn observe_service() -> ServiceObservation {
        let service = match service() {
            Ok(Some(service)) => service,
            Ok(None) => return ServiceObservation::NotInstalled,
            Err(_) => return ServiceObservation::Unknown,
        };
        let mut status = SERVICE_STATUS_PROCESS::default();
        let mut needed = 0u32;
        if unsafe {
            QueryServiceStatusEx(
                service.0,
                SC_STATUS_PROCESS_INFO,
                (&mut status as *mut SERVICE_STATUS_PROCESS).cast(),
                size_of::<SERVICE_STATUS_PROCESS>() as u32,
                &mut needed,
            )
        } == 0
        {
            return ServiceObservation::Unknown;
        }
        if status.dwCurrentState == SERVICE_RUNNING {
            ServiceObservation::Running
        } else {
            ServiceObservation::Stopped
        }
    }

    fn read_version(path: &Path) -> Option<String> {
        let child = Command::new(path)
            .arg("--version")
            .stdin(Stdio::null())
            .stdout(Stdio::piped())
            .stderr(Stdio::null())
            .creation_flags(CREATE_NO_WINDOW)
            .spawn()
            .ok()?;
        let mut child = child;
        let deadline = Instant::now() + Duration::from_secs(2);
        loop {
            match child.try_wait() {
                Ok(Some(status)) if status.success() => break,
                Ok(Some(_)) | Err(_) => return None,
                Ok(None) if Instant::now() < deadline => {
                    thread::sleep(Duration::from_millis(25));
                }
                Ok(None) => {
                    let _ = child.kill();
                    let _ = child.wait();
                    return None;
                }
            }
        }
        let mut output = Vec::new();
        child
            .stdout
            .take()?
            .take(257)
            .read_to_end(&mut output)
            .ok()?;
        if output.len() > 256 {
            return None;
        }
        let text = std::str::from_utf8(&output).ok()?.trim();
        let version = text.strip_prefix("quota-guard-meter ")?;
        let core = version.split(['-', '+']).next()?;
        let mut parts = core.split('.');
        if !(0..3).all(|_| {
            parts
                .next()
                .is_some_and(|part| !part.is_empty() && part.bytes().all(|b| b.is_ascii_digit()))
        }) || parts.next().is_some()
            || version.len() > 64
            || !version
                .bytes()
                .all(|b| b.is_ascii_alphanumeric() || matches!(b, b'.' | b'-' | b'+'))
        {
            return None;
        }
        Some(version.to_owned())
    }

    fn sha256(path: &Path) -> Option<String> {
        let mut file = File::open(path).ok()?;
        let mut hash = Sha256::new();
        let mut buffer = [0u8; 32768];
        loop {
            let count = file.read(&mut buffer).ok()?;
            if count == 0 {
                break;
            }
            hash.update(&buffer[..count]);
        }
        Some(format!("{:x}", hash.finalize()))
    }

    fn resource(app: &AppHandle, name: &str) -> Option<PathBuf> {
        let path = app
            .path()
            .resolve(PathBuf::from("meter").join(name), BaseDirectory::Resource)
            .ok()?;
        let metadata = std::fs::symlink_metadata(&path).ok()?;
        (metadata.is_file() && !metadata.file_type().is_symlink()).then_some(path)
    }

    pub(super) fn version_status(app: &AppHandle) -> MeterVersionStatus {
        let bundled = resource(app, "quota-guard-meter.exe");
        let bundled_version = bundled.as_deref().and_then(read_version);
        let bundled_hash = bundled.as_deref().and_then(sha256);
        let observation = observe_service();
        if observation == ServiceObservation::NotInstalled {
            return MeterVersionStatus {
                installed_version: None,
                bundled_version,
                needs_upgrade: None,
                status: MeterVersionState::NotInstalled,
            };
        }
        let installed_version = crate::meter_status::meter_status(app).version;
        let configured_hash = crate::meter_status::meter_connection_binary_hash(app);
        let (needs_upgrade, status) = super::assess_version(
            installed_version.as_deref(),
            bundled_version.as_deref(),
            configured_hash.as_deref(),
            bundled_hash.as_deref(),
            observation == ServiceObservation::Running,
        );
        MeterVersionStatus {
            installed_version,
            bundled_version,
            needs_upgrade,
            status,
        }
    }

    fn current_user_sid() -> Option<String> {
        let mut token = ptr::null_mut();
        if unsafe { OpenProcessToken(GetCurrentProcess(), TOKEN_QUERY, &mut token) } == 0 {
            return None;
        }
        let token = Handle(token);
        let result = (|| {
            let mut length = 0u32;
            unsafe { GetTokenInformation(token.0, TokenUser, ptr::null_mut(), 0, &mut length) };
            if length < size_of::<TOKEN_USER>() as u32 || length > 4096 {
                return None;
            }
            let mut buffer = vec![0usize; (length as usize).div_ceil(size_of::<usize>())];
            if unsafe {
                GetTokenInformation(
                    token.0,
                    TokenUser,
                    buffer.as_mut_ptr().cast(),
                    length,
                    &mut length,
                )
            } == 0
            {
                return None;
            }
            let user = unsafe { &*buffer.as_ptr().cast::<TOKEN_USER>() };
            let mut sid = ptr::null_mut();
            if unsafe { ConvertSidToStringSidW(user.User.Sid, &mut sid) } == 0 {
                return None;
            }
            let mut end = 0usize;
            while end < 256 && unsafe { *sid.add(end) } != 0 {
                end += 1;
            }
            let text = (end < 256)
                .then(|| String::from_utf16_lossy(unsafe { std::slice::from_raw_parts(sid, end) }));
            unsafe { LocalFree(sid.cast()) };
            text.filter(|value| value.starts_with("S-1-") && value.len() < 256)
        })();
        result
    }

    fn quote(value: &str) -> String {
        format!("\"{}\"", value.replace('"', "\\\""))
    }

    pub(super) fn configure_existing_service(
        app: &AppHandle,
        server_url: &str,
    ) -> Result<MeterSync, String> {
        let Some(_service) = service()? else {
            return Ok(MeterSync::NotInstalled);
        };
        let script = resource(app, "configure-meter-server.ps1")
            .ok_or_else(|| "统计程序地址更新脚本未随安装包提供。".to_owned())?;
        let exit_code = run_maintenance(&script, server_url)?;
        if exit_code != 0 {
            return Err("统计程序地址更新失败，服务器地址未保存。".into());
        }
        Ok(MeterSync::Updated)
    }

    pub(super) fn repair_service(app: &AppHandle) -> Result<(), String> {
        static REPAIR_LOCK: std::sync::Mutex<()> = std::sync::Mutex::new(());
        let _lock = REPAIR_LOCK
            .try_lock()
            .map_err(|_| "统计程序正在修复，请等待完成。".to_owned())?;
        let script = resource(app, "repair-meter-service.ps1")
            .ok_or_else(|| "安装包缺少修复脚本，请重新安装最新版客户端。".to_owned())?;
        match run_maintenance(&script, &crate::server_config::load(app).url)? {
            0 => Ok(()),
            20 => Err("统计服务属于其他 Windows 用户，请由原用户或管理员处理。".into()),
            21 => {
                Err("统计服务与客户端的服务器地址不同，请先在设置中保存正确地址，再修复。".into())
            }
            22 => Err(
                "服务注册已丢失但仍有历史配置。为保留原始队列，请由管理员恢复原服务配置。".into(),
            ),
            _ => Err(
                "修复未完成。原始用量队列已保留，请检查服务权限、磁盘空间和安装状态后重试。".into(),
            ),
        }
    }

    fn run_maintenance(script: &Path, server_url: &str) -> Result<u32, String> {
        let executable = system_powershell()
            .ok_or_else(|| "无法找到 Windows PowerShell，无法执行统计程序维护。".to_owned())?;
        let sid = current_user_sid()
            .ok_or_else(|| "无法校验当前 Windows 用户，无法执行统计程序维护。".to_owned())?;
        let parameters = format!(
            "-NoLogo -NoProfile -NonInteractive -ExecutionPolicy Bypass -File {} -ServerUrl {} -ExpectedUserSid {}",
            quote(&script.to_string_lossy()),
            quote(server_url),
            quote(&sid)
        );
        let executable = wide(executable.as_os_str());
        let verb = wide(OsStr::new("runas"));
        let params: Vec<u16> = parameters.encode_utf16().chain(Some(0)).collect();
        let mut info = SHELLEXECUTEINFOW {
            cbSize: size_of::<SHELLEXECUTEINFOW>() as u32,
            fMask: SEE_MASK_NOCLOSEPROCESS,
            lpVerb: verb.as_ptr(),
            lpFile: executable.as_ptr(),
            lpParameters: params.as_ptr(),
            nShow: SW_HIDE,
            ..Default::default()
        };
        if unsafe { ShellExecuteExW(&mut info) } == 0 {
            let error = unsafe { GetLastError() };
            let message = if error == 1223 {
                "已取消管理员授权，统计程序未更改。"
            } else {
                "无法启动统计程序维护，请检查管理员授权。"
            };
            return Err(message.into());
        }
        if info.hProcess.is_null() {
            return Err("无法确认统计程序维护结果，请重新自检。".into());
        }
        let process = Handle(info.hProcess);
        let wait = unsafe { WaitForSingleObject(process.0, CONFIGURE_TIMEOUT.as_millis() as u32) };
        if wait != 0 {
            return Err("统计程序维护仍未完成，请稍后重新自检，勿重复操作。".into());
        }
        let mut exit_code = 1u32;
        if unsafe { GetExitCodeProcess(process.0, &mut exit_code) } == 0 {
            return Err("无法读取统计程序维护结果，请重新自检。".into());
        }
        Ok(exit_code)
    }
}
