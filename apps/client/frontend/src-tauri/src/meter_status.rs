//! Bounded readers and a single fixed pairing inbox for the independent meter.
//! These paths contain public summaries only; service credentials stay under
//! the administrator-owned meter state directory.

use serde::{Deserialize, Serialize};
use serde_json::Value;
use std::{
    collections::VecDeque,
    fs::{self, File, OpenOptions},
    io::{Read, Write},
    path::{Path, PathBuf},
};
use tauri::{AppHandle, Manager};
use uuid::Uuid;

const ID_FILE: &str = "meter-device-id.txt";
const STATUS_FILE: &str = "meter-status.json";
const CONNECTION_FILE: &str = "meter-connection.json";
const PUBLIC_LOG_FILE: &str = "meter-runtime-logs.jsonl";
const PRIVATE_LOG_DIR: &str = "CodexQuotaGuard/Meter/logs";
const MAX_STATUS_BYTES: u64 = 4096;
const MAX_LOG_BYTES: usize = 65536;
const MAX_AUDIT_FILE_BYTES: u64 = 2 * 1024 * 1024;
const MAX_LOG_LINE_BYTES: usize = 4096;
const MAX_SAFE_NUMBER: u64 = 9_007_199_254_740_991;

#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct DeviceIdentity {
    pub device_id: Option<String>,
    pub enrollment_id: Option<String>,
}

#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct MeterStatus {
    pub state: &'static str,
    pub last_scan_epoch_seconds: Option<u64>,
    pub last_report_epoch_seconds: Option<u64>,
    pub reported_events: u64,
    pub error_code: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub warning_code: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub deferred_events: Option<u64>,
    pub review_submitted_events: u64,
    pub review_error_code: Option<String>,
    pub version: Option<String>,
}

#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct MeterRuntimeLogs {
    pub state: &'static str,
    pub entries: Vec<MeterLogEntry>,
    pub skipped_lines: u64,
}

#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct MeterLogEntry {
    pub at_epoch_seconds: u64,
    pub kind: String,
    pub state: Option<String>,
    pub reported_events: u64,
    pub reported_tokens: u64,
    pub heartbeat_sent: bool,
    pub error_code: Option<String>,
    pub cc_switch_guard: Option<String>,
}

#[derive(Clone, Debug, Deserialize, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub(crate) struct MeterPairing {
    device_id: Uuid,
    device_token: String,
    user_id: Uuid,
    server_origin: String,
}

pub(crate) fn device_identity(app: &AppHandle) -> DeviceIdentity {
    let value = app
        .path()
        .app_config_dir()
        .ok()
        .and_then(|directory| read_device_id(&directory.join(ID_FILE)));
    DeviceIdentity {
        enrollment_id: value.clone(),
        device_id: value,
    }
}

pub(crate) fn meter_status(app: &AppHandle) -> MeterStatus {
    match crate::meter_service::observe_service() {
        crate::meter_service::ServiceObservation::NotInstalled => {
            return empty_status("NOT_INSTALLED")
        }
        crate::meter_service::ServiceObservation::Running => {}
        crate::meter_service::ServiceObservation::Stopped
        | crate::meter_service::ServiceObservation::Unknown => return empty_status("OFFLINE"),
    }
    let Some(directory) = app.path().app_config_dir().ok() else {
        return empty_status("OFFLINE");
    };
    if !safe_directory(&directory) {
        return empty_status("OFFLINE");
    }
    if read_device_id(&directory.join(ID_FILE)).is_none() {
        return empty_status("NOT_INSTALLED");
    }

    // The installer maintains this non-secret connection record after a
    // successful service configuration change. Refuse stale status while it
    // points at another server origin.
    if !connection_matches(&directory.join(CONNECTION_FILE), &crate::server_config::load(app).url) {
        return empty_status("OFFLINE");
    }

    let path = directory.join(STATUS_FILE);
    let Some(bytes) = read_bounded_regular_file(&path, MAX_STATUS_BYTES) else {
        return empty_status("OFFLINE");
    };
    parse_status(&bytes)
}

pub(crate) fn meter_connection_matches(app: &AppHandle) -> bool {
    app.path().app_config_dir().ok().is_some_and(|directory| {
        safe_directory(&directory)
            && connection_matches(&directory.join(CONNECTION_FILE), &crate::server_config::load(app).url)
    })
}

fn empty_status(state: &'static str) -> MeterStatus {
    MeterStatus {
        state,
        last_scan_epoch_seconds: None,
        last_report_epoch_seconds: None,
        reported_events: 0,
        error_code: None,
        warning_code: None,
        deferred_events: None,
        review_submitted_events: 0,
        review_error_code: None,
        version: None,
    }
}

fn parse_status(bytes: &[u8]) -> MeterStatus {
    let Ok(value) = serde_json::from_slice::<Value>(bytes) else {
        return empty_status("OFFLINE");
    };
    let Some(object) = value.as_object() else {
        return empty_status("OFFLINE");
    };
    let scan = object.get("lastScanEpochSeconds").and_then(Value::as_u64);
    let now = crate::unix_seconds();
    if scan.map_or(true, |at| {
        at == 0 || at > now.saturating_add(2) || now.saturating_sub(at) > 30
    }) {
        return empty_status("OFFLINE");
    }
    let state = object.get("state").and_then(Value::as_str).unwrap_or("");
    let state = match state {
        "BASELINE_READY" => "BASELINE_READY",
        "UNPAIRED" => "UNPAIRED",
        "UP_TO_DATE" => "UP_TO_DATE",
        "REPORTED" => "REPORTED",
        "ERROR" => "ERROR",
        _ => "ERROR",
    };
    let error_code = object
        .get("errorCode")
        .and_then(Value::as_str)
        .filter(|text| valid_error(text))
        .map(str::to_owned);
    let version = object
        .get("version")
        .and_then(Value::as_str)
        .filter(|text| valid_version(text))
        .map(str::to_owned);
    let last_report = object
        .get("lastReportEpochSeconds")
        .and_then(Value::as_u64)
        .filter(|at| *at > 0);
    MeterStatus {
        state,
        last_scan_epoch_seconds: scan,
        last_report_epoch_seconds: last_report,
        reported_events: object
            .get("reportedEvents")
            .and_then(Value::as_u64)
            .unwrap_or(0)
            .min(MAX_SAFE_NUMBER),
        error_code,
        warning_code: object.get("warningCode").and_then(Value::as_str)
            .filter(|text| valid_error(text)).map(str::to_owned),
        deferred_events: object.get("deferredEvents").and_then(Value::as_u64)
            .filter(|count| *count > 0).map(|count| count.min(MAX_SAFE_NUMBER)),
        review_submitted_events: object.get("reviewSubmittedEvents").and_then(Value::as_u64).unwrap_or(0).min(MAX_SAFE_NUMBER),
        review_error_code: object.get("reviewErrorCode").and_then(Value::as_str)
            .filter(|text| valid_error(text)).map(str::to_owned),
        version,
    }
}

fn valid_error(value: &str) -> bool {
    (1..=64).contains(&value.len())
        && value
            .bytes()
            .all(|byte| byte.is_ascii_uppercase() || byte == b'_')
}

fn valid_version(value: &str) -> bool {
    value.len() <= 64
        && value
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'.' | b'-' | b'+'))
        && value.contains('.')
}

fn connection_matches(path: &Path, current_origin: &str) -> bool {
    let Some(bytes) = read_bounded_regular_file(path, MAX_STATUS_BYTES) else {
        return false;
    };
    let Ok(value) = serde_json::from_slice::<Value>(&bytes) else {
        return false;
    };
    let Some(server_url) = value.get("serverUrl").and_then(Value::as_str) else {
        return false;
    };
    origin(server_url).as_deref() == origin(current_origin).as_deref()
}

pub(crate) fn meter_connection_binary_hash(app: &AppHandle) -> Option<String> {
    let path = app
        .path()
        .app_config_dir()
        .ok()?
        .join(CONNECTION_FILE);
    let bytes = read_bounded_regular_file(&path, MAX_STATUS_BYTES)?;
    let value: Value = serde_json::from_slice(&bytes).ok()?;
    let hash = value.get("binaryHash")?.as_str()?;
    (hash.len() == 64 && hash.bytes().all(|byte| byte.is_ascii_hexdigit()))
        .then(|| hash.to_owned())
}

fn origin(value: &str) -> Option<String> {
    let parsed = url::Url::parse(value).ok()?;
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

pub(crate) fn meter_pairing_write(app: &AppHandle, pairing: MeterPairing) -> Result<(), String> {
    let directory = app
        .path()
        .app_config_dir()
        .map_err(|_| "无法读取客户端设置目录。".to_owned())?;
    let installed_id = read_device_id(&directory.join(ID_FILE))
        .and_then(|value| Uuid::parse_str(&value).ok())
        .ok_or_else(|| "统计服务尚未安装，请先完成安装。".to_owned())?;
    let config = crate::server_config::load(app);
    if pairing.device_id != installed_id
        || !valid_meter_token(&pairing.device_token)
        || origin(&pairing.server_origin).as_deref() != origin(&config.url).as_deref()
    {
        return Err("统计设备配对信息与当前设备或服务器不匹配。".into());
    }
    write_pairing_atomically(&directory.join("meter-pairing.json"), &pairing)
}

fn valid_meter_token(value: &str) -> bool {
    value.len() == 43
        && value
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'-' | b'_'))
}

fn write_pairing_atomically(path: &Path, pairing: &MeterPairing) -> Result<(), String> {
    let directory = path
        .parent()
        .ok_or_else(|| "无法读取客户端设置目录。".to_owned())?;
    if !directory.is_absolute() || !safe_directory(directory) {
        return Err("无法安全访问统计配对目录。".into());
    }
    fs::create_dir_all(directory).map_err(|_| "无法写入统计配对信息。".to_owned())?;
    if !safe_directory(directory) {
        return Err("无法安全访问统计配对目录。".into());
    }
    match fs::symlink_metadata(path) {
        Ok(metadata) if safe_file_metadata(&metadata) => {}
        Ok(_) => return Err("统计配对文件路径无效。".into()),
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => {}
        Err(_) => return Err("无法读取统计配对文件。".into()),
    }
    let temporary = directory.join(format!(".meter-pairing-{}.tmp", Uuid::new_v4()));
    let result = (|| {
        let bytes = serde_json::to_vec(pairing).map_err(|_| "无法写入统计配对信息。")?;
        let mut output = OpenOptions::new()
            .write(true)
            .create_new(true)
            .open(&temporary)
            .map_err(|_| "无法写入统计配对信息。")?;
        output
            .write_all(&bytes)
            .and_then(|()| output.sync_all())
            .map_err(|_| "无法写入统计配对信息。")?;
        drop(output);
        fs::rename(&temporary, path).map_err(|_| "无法提交统计配对信息。")
    })();
    if result.is_err() {
        let _ = fs::remove_file(&temporary);
    }
    result.map_err(str::to_owned)
}

pub(crate) fn meter_runtime_logs(app: &AppHandle) -> MeterRuntimeLogs {
    let Some(directory) = app.path().app_config_dir().ok() else {
        return unavailable_logs("NOT_AVAILABLE");
    };
    if !safe_directory(&directory) {
        return unavailable_logs("READ_FAILED");
    }
    let mut public_files = Vec::new();
    for name in [format!("{PUBLIC_LOG_FILE}.1"), PUBLIC_LOG_FILE.to_owned()] {
        match read_log_file(&directory.join(name), MAX_LOG_BYTES as u64) {
            Ok(Some(bytes)) => public_files.push(bytes),
            Err(LogReadError::AccessDenied) => return unavailable_logs("ADMIN_REQUIRED"),
            Err(_) => return unavailable_logs("READ_FAILED"),
            Ok(None) => {}
        }
    }
    if !public_files.is_empty() {
        return parse_logs(&public_files);
    }

    // Older services only wrote logs under ProgramData. Preserve the old
    // reader behavior and its administrator-required response; never change
    // those service-owned ACLs from the desktop process.
    #[cfg(windows)]
    let private = program_data().map(|root| root.join(PRIVATE_LOG_DIR));
    #[cfg(not(windows))]
    let private = None;
    let Some(private) = private else {
        return unavailable_logs("NOT_AVAILABLE");
    };
    let result = read_log_directory(&private);
    match result {
        Ok(page) => page,
        Err(LogReadError::AccessDenied) => unavailable_logs("ADMIN_REQUIRED"),
        Err(LogReadError::Missing) => unavailable_logs("NOT_AVAILABLE"),
        Err(LogReadError::Other) => unavailable_logs("READ_FAILED"),
    }
}

#[cfg(windows)]
fn program_data() -> Option<PathBuf> {
    use std::{os::windows::ffi::OsStringExt, ptr};
    use windows_sys::Win32::{
        System::Com::CoTaskMemFree,
        UI::Shell::{SHGetKnownFolderPath, FOLDERID_ProgramData},
    };
    let mut raw = ptr::null_mut();
    if unsafe { SHGetKnownFolderPath(&FOLDERID_ProgramData, 0, ptr::null_mut(), &mut raw) } < 0
        || raw.is_null()
    {
        return None;
    }
    let mut length = 0usize;
    while length < 32768 && unsafe { *raw.add(length) } != 0 {
        length += 1;
    }
    let path = (length < 32768).then(|| {
        PathBuf::from(std::ffi::OsString::from_wide(unsafe {
            std::slice::from_raw_parts(raw, length)
        }))
    });
    unsafe { CoTaskMemFree(raw.cast()) };
    path
}

#[derive(Clone, Copy, Debug)]
enum LogReadError {
    Missing,
    AccessDenied,
    Other,
}

fn unavailable_logs(state: &'static str) -> MeterRuntimeLogs {
    MeterRuntimeLogs {
        state,
        entries: Vec::new(),
        skipped_lines: 0,
    }
}

fn read_log_file(path: &Path, max_file_bytes: u64) -> Result<Option<Vec<u8>>, LogReadError> {
    let metadata = match fs::symlink_metadata(path) {
        Ok(metadata) if safe_file_metadata(&metadata) => metadata,
        Ok(_) => return Err(LogReadError::Other),
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(None),
        Err(error) if error.kind() == std::io::ErrorKind::PermissionDenied => {
            return Err(LogReadError::AccessDenied)
        }
        Err(_) => return Err(LogReadError::Other),
    };
    if metadata.len() > max_file_bytes {
        return Err(LogReadError::Other);
    }
    let mut file = File::open(path).map_err(|error| {
        if error.kind() == std::io::ErrorKind::PermissionDenied {
            LogReadError::AccessDenied
        } else {
            LogReadError::Other
        }
    })?;
    let actual = file.metadata().map_err(|_| LogReadError::Other)?;
    if !safe_file_metadata(&actual) || actual.len() > max_file_bytes {
        return Err(LogReadError::Other);
    }
    let start = actual.len().saturating_sub(MAX_LOG_BYTES as u64);
    use std::io::Seek;
    file.seek(std::io::SeekFrom::Start(start))
        .map_err(|_| LogReadError::Other)?;
    let mut bytes = Vec::new();
    file.take(MAX_LOG_BYTES as u64 + 1)
        .read_to_end(&mut bytes)
        .map_err(|_| LogReadError::Other)?;
    if bytes.len() > MAX_LOG_BYTES {
        return Err(LogReadError::Other);
    }
    if start > 0 {
        if let Some(newline) = bytes.iter().position(|byte| *byte == b'\n') {
            bytes.drain(..=newline);
        } else {
            bytes.clear();
        }
    }
    Ok(Some(bytes))
}

fn read_log_directory(directory: &Path) -> Result<MeterRuntimeLogs, LogReadError> {
    for ancestor in directory.ancestors() {
        match fs::symlink_metadata(ancestor) {
            Ok(metadata) if safe_directory_metadata(&metadata) => {}
            Ok(_) => return Err(LogReadError::Other),
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => {
                return Err(LogReadError::Missing)
            }
            Err(error) if error.kind() == std::io::ErrorKind::PermissionDenied => {
                return Err(LogReadError::AccessDenied)
            }
            Err(_) => return Err(LogReadError::Other),
        }
    }
    let mut files = Vec::new();
    for index in (0..=5).rev() {
        let name = if index == 0 {
            "meter.jsonl".to_owned()
        } else {
            format!("meter.{index}.jsonl")
        };
        let path = directory.join(name);
        match read_log_file(&path, MAX_AUDIT_FILE_BYTES) {
            Ok(Some(bytes)) => files.push(bytes),
            Ok(None) => {}
            Err(error) => return Err(error),
        }
    }
    if files.is_empty() {
        return Err(LogReadError::Missing);
    }
    Ok(parse_logs(&files))
}

fn parse_logs(files: &[Vec<u8>]) -> MeterRuntimeLogs {
    let mut entries = VecDeque::new();
    let mut skipped = 0u64;
    for bytes in files {
        let content = String::from_utf8_lossy(bytes);
        for line in content.lines() {
            if line.is_empty() {
                continue;
            }
            if line.len() > MAX_LOG_LINE_BYTES {
                skipped += 1;
                continue;
            }
            match parse_log_line(line) {
                Some(entry) => {
                    if entries.len() == 50 {
                        entries.pop_front();
                    }
                    entries.push_back(entry);
                }
                None => skipped += 1,
            }
        }
    }
    let mut entries: Vec<_> = entries.into_iter().collect();
    entries.sort_by_key(|entry| std::cmp::Reverse(entry.at_epoch_seconds));
    MeterRuntimeLogs {
        state: "READY",
        entries,
        skipped_lines: skipped,
    }
}

fn parse_log_line(line: &str) -> Option<MeterLogEntry> {
    let value: Value = serde_json::from_str(line).ok()?;
    let kind = value.get("kind")?.as_str()?;
    if !matches!(
        kind,
        "SERVICE_STARTED"
            | "SERVICE_STOPPED"
            | "BASELINE_READY"
            | "UNPAIRED"
            | "USAGE_REPORTED"
            | "HEARTBEAT"
            | "ERROR"
            | "STATUS_CHANGE"
            | "GUARD_ACTIVE"
            | "GUARD_INACTIVE"
            | "GUARD_DETECTED"
            | "GUARD_TERMINATED"
            | "GUARD_ERROR"
    ) {
        return None;
    }
    let at = value.get("atEpochSeconds")?.as_u64()?;
    if at == 0 {
        return None;
    }
    let state = value
        .get("state")
        .and_then(Value::as_str)
        .filter(|value| matches!(*value, "BASELINE_READY" | "UNPAIRED" | "UP_TO_DATE" | "REPORTED" | "ERROR"))
        .map(str::to_owned);
    let guard = value
        .get("ccSwitchGuard")
        .and_then(Value::as_str)
        .filter(|value| matches!(*value, "INACTIVE" | "CLEAR" | "TERMINATED" | "ERROR"))
        .map(str::to_owned);
    let error = value
        .get("errorCode")
        .and_then(Value::as_str)
        .filter(|text| !text.is_empty())
        .map(|text| {
            if valid_error(text) {
                text.to_owned()
            } else {
                "METER_ERROR_REDACTED".to_owned()
            }
        });
    Some(MeterLogEntry {
        at_epoch_seconds: at,
        kind: kind.to_owned(),
        state,
        reported_events: value
            .get("reportedEvents")
            .and_then(Value::as_u64)
            .unwrap_or(0)
            .min(MAX_SAFE_NUMBER),
        reported_tokens: value
            .get("reportedTokens")
            .and_then(Value::as_u64)
            .unwrap_or(0)
            .min(MAX_SAFE_NUMBER),
        heartbeat_sent: value
            .get("heartbeatSent")
            .and_then(Value::as_bool)
            .unwrap_or(false),
        error_code: error,
        cc_switch_guard: guard,
    })
}

fn read_device_id(path: &Path) -> Option<String> {
    let bytes = read_bounded_regular_file(path, 64)?;
    let value = std::str::from_utf8(&bytes).ok()?.trim();
    let id = Uuid::parse_str(value).ok()?;
    Some(id.to_string())
}

fn read_bounded_regular_file(path: &Path, max: u64) -> Option<Vec<u8>> {
    if !path.parent().is_some_and(safe_directory) {
        return None;
    }
    let path_metadata = fs::symlink_metadata(path).ok()?;
    if !safe_file_metadata(&path_metadata) || path_metadata.len() > max {
        return None;
    }
    let file = File::open(path).ok()?;
    let metadata = file.metadata().ok()?;
    if !safe_file_metadata(&metadata) || metadata.len() > max {
        return None;
    }
    let mut bytes = Vec::with_capacity(metadata.len() as usize);
    file.take(max.saturating_add(1)).read_to_end(&mut bytes).ok()?;
    (bytes.len() as u64 <= max).then_some(bytes)
}

fn safe_file_metadata(metadata: &fs::Metadata) -> bool {
    metadata.is_file() && !is_reparse(metadata)
}

fn safe_directory_metadata(metadata: &fs::Metadata) -> bool {
    metadata.is_dir() && !is_reparse(metadata)
}

fn safe_directory(path: &Path) -> bool {
    path.ancestors().all(|path| {
        match fs::symlink_metadata(path) {
            Ok(metadata) => safe_directory_metadata(&metadata),
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => true,
            Err(_) => false,
        }
    })
}

#[cfg(windows)]
fn is_reparse(metadata: &fs::Metadata) -> bool {
    use std::os::windows::fs::MetadataExt;
    metadata.file_attributes() & 0x400 != 0
}

#[cfg(not(windows))]
fn is_reparse(metadata: &fs::Metadata) -> bool {
    use std::os::unix::fs::FileTypeExt;
    metadata.file_type().is_symlink() || metadata.file_type().is_socket()
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::time::{SystemTime, UNIX_EPOCH};

    #[test]
    fn status_accepts_only_fresh_fixed_fields_and_redacts_untrusted_values() {
        let now = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap()
            .as_secs();
        let status = parse_status(
            serde_json::json!({
                "state": "REPORTED",
                "version": "1.2.3",
                "lastScanEpochSeconds": now,
                "lastReportEpochSeconds": now - 1,
                "reportedEvents": 2,
                "errorCode": "private path",
                "refreshToken": "secret"
            })
            .to_string()
            .as_bytes(),
        );
        assert_eq!(status.state, "REPORTED");
        assert_eq!(status.error_code, None);
        assert_eq!(status.version.as_deref(), Some("1.2.3"));
    }

    #[test]
    fn historical_review_warning_keeps_a_fresh_service_healthy() {
        let now = crate::unix_seconds();
        let input = serde_json::json!({
            "state":"UP_TO_DATE", "version":"0.2.7", "lastScanEpochSeconds":now,
            "reportedEvents":0, "errorCode":null,
            "warningCode":"METER_EVENT_OUTSIDE_REPORT_WINDOW", "deferredEvents":1194
        });
        let status = parse_status(input.to_string().as_bytes());
        assert_eq!(status.state, "UP_TO_DATE");
        assert_eq!(status.error_code, None);
        assert_eq!(status.warning_code.as_deref(), Some("METER_EVENT_OUTSIDE_REPORT_WINDOW"));
        assert_eq!(status.deferred_events, Some(1194));
        let mut invalid = input;
        invalid["warningCode"] = "private path/token".into();
        invalid["deferredEvents"] = serde_json::Value::from(u64::MAX);
        let status = parse_status(invalid.to_string().as_bytes());
        assert_eq!(status.warning_code, None);
        assert_eq!(status.deferred_events, Some(MAX_SAFE_NUMBER));
    }

    #[test]
    fn stale_or_invalid_status_is_offline() {
        assert_eq!(
            parse_status(br#"{"state":"REPORTED","lastScanEpochSeconds":1}"#).state,
            "OFFLINE"
        );
        assert_eq!(parse_status(b"not json").state, "OFFLINE");
    }

    #[test]
    fn log_parser_keeps_only_safe_summaries() {
        let page = parse_logs(&[format!(
            "{}\n",
            serde_json::json!({
                "atEpochSeconds": 10,
                "kind": "ERROR",
                "state": "ERROR",
                "reportedEvents": 3,
                "reportedTokens": 99,
                "heartbeatSent": false,
                "errorCode": "refresh_token=private",
                "ccSwitchGuard": "CLEAR",
                "secret": "should not surface"
            })
        )
        .into_bytes()]);
        assert_eq!(page.entries.len(), 1);
        assert_eq!(
            page.entries[0].error_code.as_deref(),
            Some("METER_ERROR_REDACTED")
        );
        assert!(!serde_json::to_string(&page).unwrap().contains("should not surface"));
    }

    #[test]
    fn log_reader_takes_a_bounded_tail_and_discards_a_partial_first_line() {
        let root = std::env::temp_dir().join(format!("meter-log-tail-test-{}", Uuid::new_v4()));
        fs::create_dir(&root).unwrap();
        let path = root.join("meter-runtime-logs.jsonl");
        let mut rows = String::new();
        for at in 1..=220 {
            rows.push_str(
                &serde_json::json!({
                    "atEpochSeconds": at,
                    "kind": "HEARTBEAT",
                    "state": "UP_TO_DATE",
                    "reportedEvents": 0,
                    "reportedTokens": 0,
                    "heartbeatSent": true,
                    "errorCode": null,
                    "ccSwitchGuard": "CLEAR",
                    "padding": "x".repeat(700)
                })
                .to_string(),
            );
            rows.push('\n');
        }
        fs::write(&path, rows).unwrap();

        let bytes = read_log_file(&path, MAX_AUDIT_FILE_BYTES)
            .unwrap()
            .unwrap();
        assert!(bytes.len() <= MAX_LOG_BYTES);
        let page = parse_logs(&[bytes]);
        assert_eq!(page.state, "READY");
        assert_eq!(page.entries.len(), 50);
        assert_eq!(page.entries[0].at_epoch_seconds, 220);
        assert!(page.entries.iter().all(|entry| entry.at_epoch_seconds > 1));
        fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn pairing_is_atomically_written_only_to_the_requested_fixed_path() {
        let root = std::env::temp_dir().join(format!("meter-pairing-test-{}", Uuid::new_v4()));
        fs::create_dir(&root).unwrap();
        let pairing = MeterPairing {
            device_id: Uuid::new_v4(),
            device_token: "A".repeat(43),
            user_id: Uuid::new_v4(),
            server_origin: "https://meter.example".into(),
        };
        let path = root.join("meter-pairing.json");
        write_pairing_atomically(&path, &pairing).unwrap();
        let value: MeterPairing = serde_json::from_slice(&fs::read(&path).unwrap()).unwrap();
        assert_eq!(value.device_id, pairing.device_id);
        assert_eq!(value.user_id, pairing.user_id);
        assert_eq!(fs::read_dir(&root).unwrap().count(), 1);
        fs::remove_dir_all(root).unwrap();
    }
}
