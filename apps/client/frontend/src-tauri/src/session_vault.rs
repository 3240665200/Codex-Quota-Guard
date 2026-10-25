//! The renderer's refresh token is persisted only as user-scoped DPAPI data.
//! A per-user process mutex prevents two desktop instances from racing a
//! one-time refresh token.

use serde::{Deserialize, Serialize};
use std::path::{Path, PathBuf};
use tauri::{AppHandle, Manager};
use url::Url;
use zeroize::Zeroize;

const DIRECTORY: &str = "secure-session";
const FILE_NAME: &str = "session-vault.dpapi";
const MAX_VAULT_BYTES: usize = 16 * 1024;
const MAX_SESSION_SECONDS: u64 = 365 * 24 * 60 * 60;
const ENTROPY: &[u8] = b"com.codexquotaguard.desktop.session-vault.v1";
const LOCK_NAME_PREFIX: &str = "Local\\CodexQuotaGuard.Session.";

#[derive(Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub(crate) struct SessionDto {
    pub server_origin: String,
    pub refresh_token: String,
    pub user_id: String,
    pub login_at_epoch_seconds: u64,
    pub expires_at_epoch_seconds: u64,
}

impl Drop for SessionDto {
    fn drop(&mut self) {
        self.refresh_token.zeroize();
    }
}

pub(crate) fn load(app: &AppHandle) -> Result<Option<SessionDto>, String> {
    let path = vault_path(app)?;
    let Some(bytes) = read_protected(&path)? else {
        return Ok(None);
    };
    #[cfg(windows)]
    let decoded = match unprotect(&bytes) {
        Ok(mut plaintext) => {
            let parsed = serde_json::from_slice::<SessionDto>(&plaintext).ok();
            plaintext.zeroize();
            parsed
        }
        Err(()) => None,
    };
    #[cfg(not(windows))]
    let decoded: Option<SessionDto> = {
        let _ = bytes;
        None
    };
    let Some(mut session) = decoded else {
        return Ok(None);
    };
    if validate_session(app, &session).is_err() {
        session.refresh_token.zeroize();
        return Ok(None);
    }
    Ok(Some(session))
}

pub(crate) fn save(app: &AppHandle, mut session: SessionDto) -> Result<(), String> {
    validate_session(app, &session)?;
    let path = vault_path(app)?;
    let parent = path
        .parent()
        .ok_or_else(|| "无法访问安全登录状态目录。".to_owned())?;
    prepare_secure_directory(parent)?;
    let mut plaintext =
        serde_json::to_vec(&session).map_err(|_| "无法保存登录状态。".to_owned())?;
    session.refresh_token.zeroize();
    #[cfg(windows)]
    let protected_result = protect(&plaintext);
    #[cfg(not(windows))]
    let protected: Vec<u8> = {
        plaintext.zeroize();
        return Err("当前系统不支持安全保存登录状态。".into());
    };
    plaintext.zeroize();
    #[cfg(windows)]
    let protected = protected_result.map_err(|_| "无法安全保存登录状态。".to_owned())?;
    write_atomically(&path, &protected)?;
    Ok(())
}

pub(crate) fn clear(app: &AppHandle) -> Result<(), String> {
    let path = vault_path(app)?;
    match std::fs::symlink_metadata(&path) {
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => Ok(()),
        Err(_) => Err("无法清除本机登录状态。".into()),
        Ok(metadata) if !safe_file(&metadata) => Err("本机登录状态文件无效。".into()),
        Ok(_) => std::fs::remove_file(path).map_err(|_| "无法清除本机登录状态。".into()),
    }
}

fn validate_session(app: &AppHandle, session: &SessionDto) -> Result<(), String> {
    let current = crate::server_config::load(app).url;
    let expected_origin = normalized_origin(&current)
        .ok_or_else(|| "当前服务器地址无效，无法保存登录状态。".to_owned())?;
    let actual_origin = normalized_origin(&session.server_origin)
        .ok_or_else(|| "登录状态所属服务器无效。".to_owned())?;
    if actual_origin != expected_origin {
        return Err("登录状态所属服务器与当前服务器地址不一致。".into());
    }
    if session.refresh_token.is_empty() || session.refresh_token.len() > 500 {
        return Err("登录状态无效。".into());
    }
    if uuid::Uuid::parse_str(&session.user_id).is_err() {
        return Err("登录状态无效。".into());
    }
    let now = crate::unix_seconds();
    if session.login_at_epoch_seconds > now
        || session.expires_at_epoch_seconds <= now
        || session.expires_at_epoch_seconds <= session.login_at_epoch_seconds
        || session
            .expires_at_epoch_seconds
            .saturating_sub(session.login_at_epoch_seconds)
            > MAX_SESSION_SECONDS
        || now.saturating_sub(session.login_at_epoch_seconds) > MAX_SESSION_SECONDS
    {
        return Err("登录状态有效期无效，请重新登录。".into());
    }
    Ok(())
}

fn normalized_origin(value: &str) -> Option<String> {
    let parsed = Url::parse(value).ok()?;
    if !matches!(parsed.scheme(), "http" | "https")
        || parsed.host_str().is_none()
        || !parsed.username().is_empty()
        || parsed.password().is_some()
        || parsed.query().is_some()
        || parsed.fragment().is_some()
        || parsed.path() != "/"
    {
        return None;
    }
    Some(parsed.origin().ascii_serialization())
}

fn vault_path(app: &AppHandle) -> Result<PathBuf, String> {
    app.path()
        .app_config_dir()
        .map(|directory| directory.join(DIRECTORY).join(FILE_NAME))
        .map_err(|_| "无法访问安全登录状态目录。".to_owned())
}

pub(crate) fn read_protected(path: &Path) -> Result<Option<Vec<u8>>, String> {
    let Some(parent) = path.parent() else {
        return Err("无法读取本机登录状态。".into());
    };
    if !safe_directory_chain(parent) {
        return Err("登录状态目录路径无效。".into());
    }
    let metadata = match std::fs::symlink_metadata(path) {
        Ok(metadata) if safe_file(&metadata) && metadata.len() <= MAX_VAULT_BYTES as u64 => metadata,
        Ok(_) => return Ok(None),
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(None),
        Err(_) => return Err("无法读取本机登录状态。".into()),
    };
    let _ = metadata;
    let file = std::fs::File::open(path).map_err(|_| "无法读取本机登录状态。".to_owned())?;
    let actual = file
        .metadata()
        .map_err(|_| "无法读取本机登录状态。".to_owned())?;
    if !safe_file(&actual) || actual.len() > MAX_VAULT_BYTES as u64 {
        return Ok(None);
    }
    use std::io::Read;
    let mut bytes = Vec::new();
    file.take(MAX_VAULT_BYTES as u64 + 1)
        .read_to_end(&mut bytes)
        .map_err(|_| "无法读取本机登录状态。".to_owned())?;
    if bytes.len() > MAX_VAULT_BYTES {
        bytes.zeroize();
        return Ok(None);
    }
    Ok(Some(bytes))
}

pub(crate) fn prepare_secure_directory(directory: &Path) -> Result<(), String> {
    if !directory.is_absolute() || !safe_directory_chain(directory) {
        return Err("无法安全访问登录状态目录。".into());
    }
    std::fs::create_dir_all(directory).map_err(|_| "无法创建登录状态目录。".to_owned())?;
    if !safe_directory_chain(directory) {
        return Err("无法安全访问登录状态目录。".into());
    }
    Ok(())
}

fn safe_directory_chain(directory: &Path) -> bool {
    directory.ancestors().all(|ancestor| {
        match std::fs::symlink_metadata(ancestor) {
            Ok(metadata) => metadata.is_dir() && !is_reparse(&metadata),
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => true,
            Err(_) => false,
        }
    })
}

fn safe_file(metadata: &std::fs::Metadata) -> bool {
    metadata.is_file() && !is_reparse(metadata)
}

#[cfg(windows)]
fn is_reparse(metadata: &std::fs::Metadata) -> bool {
    use std::os::windows::fs::MetadataExt;
    metadata.file_attributes() & 0x400 != 0
}

#[cfg(not(windows))]
fn is_reparse(metadata: &std::fs::Metadata) -> bool {
    metadata.file_type().is_symlink()
}

pub(crate) fn write_atomically(path: &Path, bytes: &[u8]) -> Result<(), String> {
    let parent = path
        .parent()
        .ok_or_else(|| "无法访问安全登录状态目录。".to_owned())?;
    match std::fs::symlink_metadata(path) {
        Ok(metadata) if safe_file(&metadata) => {}
        Ok(_) => return Err("本机登录状态文件无效。".into()),
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => {}
        Err(_) => return Err("无法读取本机登录状态。".into()),
    }
    let temporary = parent.join(format!(".session-vault-{}.tmp", uuid::Uuid::new_v4()));
    let result = (|| {
        use std::io::Write;
        let mut file = std::fs::OpenOptions::new()
            .write(true)
            .create_new(true)
            .open(&temporary)
            .map_err(|_| "无法保存本机登录状态。")?;
        file.write_all(bytes)
            .and_then(|()| file.sync_all())
            .map_err(|_| "无法保存本机登录状态。")?;
        drop(file);
        std::fs::rename(&temporary, path).map_err(|_| "无法提交本机登录状态。")
    })();
    if result.is_err() {
        let _ = std::fs::remove_file(&temporary);
    }
    result.map_err(str::to_owned)
}

#[cfg(windows)]
pub(crate) fn protect(plaintext: &[u8]) -> Result<Vec<u8>, ()> {
    use std::ptr;
    use windows_sys::Win32::{
        Foundation::LocalFree,
        Security::Cryptography::{
            CryptProtectData, CRYPTPROTECT_UI_FORBIDDEN, CRYPT_INTEGER_BLOB,
        },
    };
    let input = blob(plaintext);
    let entropy = blob(ENTROPY);
    let mut output = CRYPT_INTEGER_BLOB {
        cbData: 0,
        pbData: ptr::null_mut(),
    };
    let success = unsafe {
        CryptProtectData(
            &input,
            ptr::null(),
            &entropy,
            ptr::null(),
            ptr::null(),
            CRYPTPROTECT_UI_FORBIDDEN,
            &mut output,
        )
    };
    if success == 0 || output.pbData.is_null() || output.cbData as usize > MAX_VAULT_BYTES {
        if !output.pbData.is_null() {
            unsafe { LocalFree(output.pbData.cast()) };
        }
        return Err(());
    }
    let protected = unsafe { std::slice::from_raw_parts(output.pbData, output.cbData as usize) }
        .to_vec();
    unsafe { LocalFree(output.pbData.cast()) };
    Ok(protected)
}

#[cfg(windows)]
pub(crate) fn unprotect(protected: &[u8]) -> Result<Vec<u8>, ()> {
    use std::ptr;
    use windows_sys::Win32::{
        Foundation::LocalFree,
        Security::Cryptography::{
            CryptUnprotectData, CRYPTPROTECT_UI_FORBIDDEN, CRYPT_INTEGER_BLOB,
        },
    };
    let input = blob(protected);
    let entropy = blob(ENTROPY);
    let mut output = CRYPT_INTEGER_BLOB {
        cbData: 0,
        pbData: ptr::null_mut(),
    };
    let success = unsafe {
        CryptUnprotectData(
            &input,
            ptr::null_mut(),
            &entropy,
            ptr::null(),
            ptr::null(),
            CRYPTPROTECT_UI_FORBIDDEN,
            &mut output,
        )
    };
    if success == 0 || output.pbData.is_null() || output.cbData as usize > MAX_VAULT_BYTES {
        if !output.pbData.is_null() {
            unsafe { LocalFree(output.pbData.cast()) };
        }
        return Err(());
    }
    let mut plaintext =
        unsafe { std::slice::from_raw_parts(output.pbData, output.cbData as usize) }.to_vec();
    unsafe {
        std::slice::from_raw_parts_mut(output.pbData, output.cbData as usize).zeroize();
        LocalFree(output.pbData.cast());
    }
    Ok(std::mem::take(&mut plaintext))
}

#[cfg(windows)]
fn blob(data: &[u8]) -> windows_sys::Win32::Security::Cryptography::CRYPT_INTEGER_BLOB {
    windows_sys::Win32::Security::Cryptography::CRYPT_INTEGER_BLOB {
        cbData: data.len() as u32,
        pbData: data.as_ptr().cast_mut(),
    }
}

pub(crate) struct SessionProcessLock {
    #[cfg(windows)]
    handle: windows_sys::Win32::Foundation::HANDLE,
    #[cfg(windows)]
    owns_mutex: bool,
}

#[cfg(windows)]
pub(crate) fn acquire_process_lock() -> Result<SessionProcessLock, ()> {
    use std::{ffi::OsStr, os::windows::ffi::OsStrExt, ptr};
    use windows_sys::Win32::{
        Foundation::{CloseHandle, GetLastError, ERROR_ALREADY_EXISTS},
        Security::{
            Authorization::ConvertSidToStringSidW, GetTokenInformation, TokenUser, TOKEN_QUERY,
            TOKEN_USER,
        },
        System::Threading::{CreateMutexW, GetCurrentProcess, OpenProcessToken},
    };

    let mut token = ptr::null_mut();
    if unsafe { OpenProcessToken(GetCurrentProcess(), TOKEN_QUERY, &mut token) } == 0 {
        return Err(());
    }
    let result = (|| {
        let mut length = 0u32;
        unsafe { GetTokenInformation(token, TokenUser, ptr::null_mut(), 0, &mut length) };
        if length < std::mem::size_of::<TOKEN_USER>() as u32 || length > 4096 {
            return Err(());
        }
        let mut buffer = vec![0usize; (length as usize).div_ceil(std::mem::size_of::<usize>())];
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
            return Err(());
        }
        let user = unsafe { &*buffer.as_ptr().cast::<TOKEN_USER>() };
        let mut sid = ptr::null_mut();
        if unsafe { ConvertSidToStringSidW(user.User.Sid, &mut sid) } == 0 {
            return Err(());
        }
        let mut end = 0usize;
        while end < 256 && unsafe { *sid.add(end) } != 0 {
            end += 1;
        }
        let sid_text = (end < 256)
            .then(|| String::from_utf16_lossy(unsafe {
                std::slice::from_raw_parts(sid, end)
            }))
            .filter(|value| value.starts_with("S-1-") && value.len() < 256);
        unsafe { windows_sys::Win32::Foundation::LocalFree(sid.cast()) };
        let sid = sid_text.filter(|sid| sid.starts_with("S-1-") && sid.len() < 256).ok_or(())?;
        let name: Vec<u16> = OsStr::new(&format!("{LOCK_NAME_PREFIX}{sid}"))
            .encode_wide()
            .chain(Some(0))
            .collect();
        let handle = unsafe { CreateMutexW(ptr::null(), 1, name.as_ptr()) };
        if handle.is_null() {
            return Err(());
        }
        if unsafe { GetLastError() } == ERROR_ALREADY_EXISTS {
            unsafe { CloseHandle(handle) };
            return Err(());
        }
        Ok(SessionProcessLock {
            handle,
            owns_mutex: true,
        })
    })();
    unsafe { windows_sys::Win32::Foundation::CloseHandle(token) };
    result
}

#[cfg(windows)]
impl Drop for SessionProcessLock {
    fn drop(&mut self) {
        use windows_sys::Win32::{
            Foundation::CloseHandle,
            System::Threading::ReleaseMutex,
        };
        if self.owns_mutex {
            unsafe { ReleaseMutex(self.handle) };
            unsafe { CloseHandle(self.handle) };
        }
    }
}

#[cfg(not(windows))]
pub(crate) fn acquire_process_lock() -> Result<SessionProcessLock, ()> {
    Ok(SessionProcessLock {})
}

pub(crate) fn show_already_running_notice() {
    #[cfg(windows)]
    {
        use std::os::windows::ffi::OsStrExt;
        use windows_sys::Win32::UI::WindowsAndMessaging::{
            MessageBoxW, MB_ICONINFORMATION, MB_OK,
        };
        let message: Vec<u16> = std::ffi::OsStr::new(
            "已有一个桌面客户端正在使用登录状态，请在原窗口继续操作。",
        )
        .encode_wide()
        .chain(Some(0))
        .collect();
        let title: Vec<u16> = std::ffi::OsStr::new("Codex Quota Guard")
            .encode_wide()
            .chain(Some(0))
            .collect();
        unsafe {
            MessageBoxW(
                std::ptr::null_mut(),
                message.as_ptr(),
                title.as_ptr(),
                MB_OK | MB_ICONINFORMATION,
            )
        };
    }
}

#[cfg(windows)]
#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn current_user_dpapi_round_trips_and_does_not_store_plaintext() {
        let token = "refresh-token-private-canary";
        let plaintext = serde_json::json!({
            "serverOrigin": "https://meter.example",
            "refreshToken": token,
            "userId": uuid::Uuid::new_v4().to_string(),
            "loginAtEpochSeconds": 100,
            "expiresAtEpochSeconds": 200
        });
        let bytes = serde_json::to_vec(&plaintext).unwrap();
        let protected = protect(&bytes).unwrap();
        assert!(!protected
            .windows(token.len())
            .any(|window| window == token.as_bytes()));
        let mut recovered = unprotect(&protected).unwrap();
        assert_eq!(recovered, bytes);
        recovered.zeroize();
    }

    #[test]
    fn session_origin_and_expiry_are_bounded() {
        assert_eq!(
            normalized_origin("https://meter.example/").as_deref(),
            Some("https://meter.example")
        );
        assert!(normalized_origin("https://user:token@meter.example").is_none());
        assert!(normalized_origin("https://meter.example/api").is_none());
    }
}
