//! Read-only observations and the one user-requested server-origin update.
//! Installation, replacement, repair, and service recovery belong to the
//! desktop installer, never to this application process.

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
    use super::{
        MeterSync, MeterVersionState, MeterVersionStatus, ServiceObservation,
    };
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
        ptr,
        thread,
        time::{Duration, Instant},
    };
    use tauri::{path::BaseDirectory, AppHandle, Manager};
    use windows_sys::Win32::{
        Foundation::{CloseHandle, GetLastError, LocalFree, HANDLE, ERROR_SERVICE_DOES_NOT_EXIST},
        Security::{
            Authorization::ConvertSidToStringSidW, GetTokenInformation, TokenUser, TOKEN_QUERY,
            TOKEN_USER,
        },
        System::{
            Services::{
                CloseServiceHandle, OpenSCManagerW, OpenServiceW, QueryServiceStatusEx,
                SC_HANDLE, SC_MANAGER_CONNECT, SC_STATUS_PROCESS_INFO, SERVICE_QUERY_STATUS,
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

    fn service() -> Result<Option<Service>, String> {
        let manager = unsafe { OpenSCManagerW(ptr::null(), ptr::null(), SC_MANAGER_CONNECT) };
        if manager.is_null() {
            return Err("无法读取 Windows 统计服务状态。".into());
        }
        let manager = Service(manager);
        let name = wide(OsStr::new(SERVICE_NAME));
        let raw = unsafe {
            OpenServiceW(
                manager.0,
                name.as_ptr(),
                SERVICE_QUERY_STATUS,
            )
        };
        if raw.is_null() {
            return if unsafe { GetLastError() } == ERROR_SERVICE_DOES_NOT_EXIST {
                Ok(None)
            } else {
                Err("无法读取 Windows 统计服务状态。".into())
            };
        }
        Ok(Some(Service(raw)))
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
        if !(0..3).all(|_| parts.next().is_some_and(|part| !part.is_empty() && part.bytes().all(|b| b.is_ascii_digit())))
            || parts.next().is_some()
            || version.len() > 64
            || !version.bytes().all(|b| b.is_ascii_alphanumeric() || matches!(b, b'.' | b'-' | b'+'))
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
        let (needs_upgrade, status) = match (configured_hash, bundled_hash) {
            (Some(current), Some(bundled)) if !current.eq_ignore_ascii_case(&bundled) => {
                (Some(true), MeterVersionState::UpgradeRequired)
            }
            (Some(_), Some(_)) if observation == ServiceObservation::Running => {
                (Some(false), MeterVersionState::Current)
            }
            _ => (None, MeterVersionState::Unknown),
        };
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
            let text = (end < 256).then(|| {
                String::from_utf16_lossy(unsafe { std::slice::from_raw_parts(sid, end) })
            });
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
        let executable = system_powershell()
            .ok_or_else(|| "无法找到 Windows PowerShell，统计程序地址未更改。".to_owned())?;
        let sid = current_user_sid()
            .ok_or_else(|| "无法校验当前 Windows 用户，统计程序地址未更改。".to_owned())?;
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
                "已取消管理员授权，服务器地址未保存。"
            } else {
                "无法启动统计程序地址更新，请检查管理员授权。"
            };
            return Err(message.into());
        }
        if info.hProcess.is_null() {
            return Err("无法确认统计程序地址是否已更新。".into());
        }
        let process = Handle(info.hProcess);
        let wait = unsafe { WaitForSingleObject(process.0, CONFIGURE_TIMEOUT.as_millis() as u32) };
        if wait != 0 {
            return Err("统计程序地址更新超时，服务器地址未保存。".into());
        }
        let mut exit_code = 1u32;
        if unsafe { GetExitCodeProcess(process.0, &mut exit_code) } == 0 || exit_code != 0 {
            return Err("统计程序地址更新失败，服务器地址未保存。".into());
        }
        Ok(MeterSync::Updated)
    }
}
