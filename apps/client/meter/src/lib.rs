use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::collections::BTreeMap;
use std::fs::{self, File};
use std::io::{BufRead, BufReader, Read, Seek, SeekFrom, Write};
use std::net::{IpAddr, Ipv4Addr};
use std::path::{Path, PathBuf};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

mod audit;
use audit::{AuditKind, AuditLog};

const MAX_RECORD_BYTES: usize = 4 * 1024 * 1024;
const PREFIX_BYTES: usize = 4096;

#[derive(Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct MeterConfig {
    pub server_url: String,
    pub codex_home: PathBuf,
    pub pairing_inbox: PathBuf,
    pub state_dir: PathBuf,
    pub status_file: PathBuf,
    pub device_id: uuid::Uuid,
}

impl MeterConfig {
    pub fn validate(&self) -> Result<(), String> {
        let url = reqwest::Url::parse(&self.server_url).map_err(|_| "METER_SERVER_URL_INVALID")?;
        let host = url.host_str().ok_or("METER_SERVER_URL_INVALID")?;
        let private_http = url.scheme() == "http"
            && (host.eq_ignore_ascii_case("localhost")
                || host.parse::<IpAddr>().is_ok_and(|ip| match ip {
                    IpAddr::V4(v4) => v4.is_loopback() || private_ipv4(v4),
                    IpAddr::V6(v6) => v6.is_loopback(),
                }));
        if url.scheme() != "https" && !private_http {
            return Err("METER_SERVER_URL_INVALID".into());
        }
        if !url.username().is_empty()
            || url.password().is_some()
            || url.query().is_some()
            || url.fragment().is_some()
            || url.path() != "/"
        {
            return Err("METER_SERVER_URL_INVALID".into());
        }
        if !self.codex_home.is_absolute()
            || !self.pairing_inbox.is_absolute()
            || !self.state_dir.is_absolute()
            || !self.status_file.is_absolute()
        {
            return Err("METER_PATH_INVALID".into());
        }
        Ok(())
    }
}

fn private_ipv4(address: Ipv4Addr) -> bool {
    let [a, b, _, _] = address.octets();
    a == 10 || (a == 172 && (16..=31).contains(&b)) || (a == 192 && b == 168)
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct PairingInbox {
    device_id: uuid::Uuid,
    device_token: String,
}

#[derive(Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct MeterCredential {
    pub device_id: uuid::Uuid,
    pub device_token: String,
}

pub fn consume_pairing(config: &MeterConfig) -> Result<bool, String> {
    if !config.pairing_inbox.exists() {
        return Ok(false);
    }
    let metadata =
        fs::symlink_metadata(&config.pairing_inbox).map_err(|_| "METER_PAIRING_INVALID")?;
    if !metadata.file_type().is_file() || metadata.len() > 4096 {
        return Err("METER_PAIRING_INVALID".into());
    }
    let bytes = fs::read(&config.pairing_inbox).map_err(|_| "METER_PAIRING_INVALID")?;
    let pairing: PairingInbox =
        serde_json::from_slice(&bytes).map_err(|_| "METER_PAIRING_INVALID")?;
    if pairing.device_id != config.device_id || !valid_meter_token(&pairing.device_token) {
        return Err("METER_PAIRING_INVALID".into());
    }
    let credential = MeterCredential {
        device_id: pairing.device_id,
        device_token: pairing.device_token,
    };
    save_credential(&config.state_dir, &credential)?;
    fs::remove_file(&config.pairing_inbox).map_err(|_| "METER_PAIRING_CLEANUP_FAILED")?;
    Ok(true)
}

fn valid_meter_token(value: &str) -> bool {
    value.len() == 43
        && value
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || byte == b'-' || byte == b'_')
}

fn credential_file(state_dir: &Path) -> PathBuf {
    state_dir.join("meter-credential.json")
}

pub fn load_credential(state_dir: &Path) -> Result<Option<MeterCredential>, String> {
    let path = credential_file(state_dir);
    if !path.exists() {
        return Ok(None);
    }
    let bytes = fs::read(path).map_err(|_| "METER_CREDENTIAL_READ_FAILED")?;
    if bytes.len() > 4096 {
        return Err("METER_CREDENTIAL_INVALID".into());
    }
    let credential: MeterCredential =
        serde_json::from_slice(&bytes).map_err(|_| "METER_CREDENTIAL_INVALID")?;
    if !valid_meter_token(&credential.device_token) {
        return Err("METER_CREDENTIAL_INVALID".into());
    }
    Ok(Some(credential))
}

fn save_credential(state_dir: &Path, credential: &MeterCredential) -> Result<(), String> {
    fs::create_dir_all(state_dir).map_err(|_| "METER_CREDENTIAL_WRITE_FAILED")?;
    let path = credential_file(state_dir);
    let temporary = state_dir.join(format!(".meter-credential-{}.tmp", uuid::Uuid::new_v4()));
    let bytes = serde_json::to_vec(credential).map_err(|_| "METER_CREDENTIAL_WRITE_FAILED")?;
    let result: Result<(), &str> = (|| {
        let mut output =
            File::create_new(&temporary).map_err(|_| "METER_CREDENTIAL_WRITE_FAILED")?;
        output
            .write_all(&bytes)
            .map_err(|_| "METER_CREDENTIAL_WRITE_FAILED")?;
        output
            .sync_all()
            .map_err(|_| "METER_CREDENTIAL_WRITE_FAILED")?;
        fs::rename(&temporary, &path).map_err(|_| "METER_CREDENTIAL_WRITE_FAILED")
    })();
    if result.is_err() {
        let _ = fs::remove_file(&temporary);
    }
    result.map_err(str::to_owned)
}

#[derive(Clone, Debug, Default, Deserialize, PartialEq, Eq, Serialize)]
pub struct ScanState {
    files: BTreeMap<String, FileCursor>,
}

#[derive(Clone, Debug, Deserialize, PartialEq, Eq, Serialize)]
struct FileCursor {
    offset: u64,
    prefix_len: usize,
    prefix_hash: String,
}

#[derive(Clone, Debug, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct UsageEvent {
    pub event_id: String,
    pub occurred_at: String,
    pub total_tokens: u64,
    pub source: String,
}

#[derive(Debug)]
pub struct ScanOutcome {
    pub events: Vec<UsageEvent>,
    pub next_state: ScanState,
}

pub fn scan(codex_home: &Path, previous: &ScanState) -> Result<ScanOutcome, String> {
    let sessions = codex_home.join("sessions");
    let mut next_state = previous.clone();
    let mut events = Vec::new();
    if !sessions.is_dir() {
        return Ok(ScanOutcome { events, next_state });
    }

    for file in session_files(&sessions)? {
        let key = file
            .strip_prefix(&sessions)
            .map_err(|_| "SESSION_PATH_INVALID")?
            .to_string_lossy()
            .replace('\\', "/");
        let metadata = fs::symlink_metadata(&file).map_err(|_| "SESSION_METADATA_UNAVAILABLE")?;
        if !metadata.file_type().is_file() {
            continue;
        }
        let prefix_len = (metadata.len() as usize).min(PREFIX_BYTES);
        let prefix_hash = hash_file_prefix(&file, prefix_len)?;
        let prior = previous.files.get(&key);
        let start = match prior {
            Some(cursor)
                if metadata.len() >= cursor.offset
                    && metadata.len() >= cursor.prefix_len as u64
                    && hash_file_prefix(&file, cursor.prefix_len)? == cursor.prefix_hash =>
            {
                cursor.offset
            }
            _ => 0,
        };
        let (retained_prefix_len, retained_prefix_hash) = if start == 0 {
            (prefix_len, prefix_hash)
        } else if let Some(cursor) = prior {
            (cursor.prefix_len, cursor.prefix_hash.clone())
        } else {
            (prefix_len, prefix_hash)
        };
        let mut input = File::open(&file).map_err(|_| "SESSION_READ_UNAVAILABLE")?;
        input
            .seek(SeekFrom::Start(start))
            .map_err(|_| "SESSION_READ_UNAVAILABLE")?;
        let mut reader = BufReader::new(input);
        let mut complete_offset = start;

        loop {
            let mut line = Vec::new();
            let bytes = reader
                .read_until(b'\n', &mut line)
                .map_err(|_| "SESSION_READ_UNAVAILABLE")?;
            if bytes == 0 || !line.ends_with(b"\n") {
                break;
            }
            complete_offset = complete_offset.saturating_add(bytes as u64);
            if line.len() > MAX_RECORD_BYTES {
                continue;
            }
            while matches!(line.last(), Some(b'\n' | b'\r')) {
                line.pop();
            }
            if let Some(event) = parse_direct_record(&line) {
                events.push(event);
            }
        }

        next_state.files.insert(
            key,
            FileCursor {
                offset: complete_offset,
                prefix_len: retained_prefix_len,
                prefix_hash: retained_prefix_hash,
            },
        );
    }
    Ok(ScanOutcome { events, next_state })
}

/// Establishes the first cursor from metadata only. Historical logs can be
/// multiple gigabytes and are deliberately never parsed or billed here.
pub fn baseline_codex_home(codex_home: &Path) -> Result<ScanState, String> {
    let sessions = codex_home.join("sessions");
    if !sessions.exists() {
        return Ok(ScanState::default());
    }
    if !fs::symlink_metadata(&sessions)
        .map_err(|_| "SESSION_DIRECTORY_UNAVAILABLE")?
        .file_type()
        .is_dir()
    {
        return Err("SESSION_DIRECTORY_UNAVAILABLE".into());
    }
    let mut state = ScanState::default();
    for file in session_files(&sessions)? {
        let key = file
            .strip_prefix(&sessions)
            .map_err(|_| "SESSION_PATH_INVALID")?
            .to_string_lossy()
            .replace('\\', "/");
        let metadata = fs::symlink_metadata(&file).map_err(|_| "SESSION_METADATA_UNAVAILABLE")?;
        if !metadata.file_type().is_file() {
            continue;
        }
        let prefix_len = (metadata.len() as usize).min(PREFIX_BYTES);
        state.files.insert(
            key,
            FileCursor {
                offset: metadata.len(),
                prefix_len,
                prefix_hash: hash_file_prefix(&file, prefix_len)?,
            },
        );
    }
    Ok(state)
}

fn session_files(root: &Path) -> Result<Vec<PathBuf>, String> {
    let mut pending = vec![root.to_path_buf()];
    let mut files = Vec::new();
    while let Some(directory) = pending.pop() {
        for entry in fs::read_dir(directory).map_err(|_| "SESSION_DIRECTORY_UNAVAILABLE")? {
            let entry = entry.map_err(|_| "SESSION_DIRECTORY_UNAVAILABLE")?;
            let kind = entry
                .file_type()
                .map_err(|_| "SESSION_METADATA_UNAVAILABLE")?;
            if kind.is_symlink() {
                continue;
            }
            if kind.is_dir() {
                pending.push(entry.path());
            } else if kind.is_file() && entry.path().extension().is_some_and(|ext| ext == "jsonl") {
                files.push(entry.path());
            }
        }
    }
    files.sort();
    Ok(files)
}

fn hash_file_prefix(file: &Path, length: usize) -> Result<String, String> {
    let mut input = File::open(file).map_err(|_| "SESSION_READ_UNAVAILABLE")?;
    let mut bytes = vec![0u8; length];
    input
        .read_exact(&mut bytes)
        .map_err(|_| "SESSION_READ_UNAVAILABLE")?;
    Ok(hex_digest(&bytes))
}

fn parse_direct_record(line: &[u8]) -> Option<UsageEvent> {
    let row: serde_json::Value = serde_json::from_slice(line).ok()?;
    if row.get("type")?.as_str()? != "token_usage_record" {
        return None;
    }
    let payload = row.get("payload")?;
    let tokens = payload.get("usage")?.get("total_tokens")?.as_u64()?;
    if tokens > 100_000_000 {
        return None;
    }
    let timestamp = row.get("timestamp")?.as_str()?;
    if timestamp.is_empty() {
        return None;
    }
    let identity = payload
        .get("response_id")
        .and_then(serde_json::Value::as_str)
        .filter(|id| !id.is_empty())
        .map(|id| format!("response:{id}"))
        .unwrap_or_else(|| format!("record:{}", String::from_utf8_lossy(line)));
    Some(UsageEvent {
        event_id: format!("codex-local-usage:v1:{}", hex_digest(identity.as_bytes())),
        occurred_at: timestamp.to_owned(),
        total_tokens: tokens,
        source: "TOKEN_USAGE_RECORD".to_owned(),
    })
}

fn hex_digest(bytes: &[u8]) -> String {
    Sha256::digest(bytes)
        .iter()
        .map(|byte| format!("{byte:02x}"))
        .collect()
}

pub fn load_state(path: &Path) -> Result<Option<ScanState>, String> {
    if !path.exists() {
        return Ok(None);
    }
    let bytes = fs::read(path).map_err(|_| "METER_STATE_READ_FAILED")?;
    if bytes.len() > 16 * 1024 * 1024 {
        return Err("METER_STATE_TOO_LARGE".into());
    }
    serde_json::from_slice(&bytes)
        .map(Some)
        .map_err(|_| "METER_STATE_INVALID".into())
}

pub fn save_state(path: &Path, state: &ScanState) -> Result<(), String> {
    let parent = path.parent().ok_or("METER_STATE_PATH_INVALID")?;
    fs::create_dir_all(parent).map_err(|_| "METER_STATE_WRITE_FAILED")?;
    let temporary = parent.join(format!(".meter-state-{}.tmp", uuid::Uuid::new_v4()));
    let bytes = serde_json::to_vec(state).map_err(|_| "METER_STATE_WRITE_FAILED")?;
    if bytes.len() > 16 * 1024 * 1024 {
        return Err("METER_STATE_TOO_LARGE".into());
    }
    let result: Result<(), &str> = (|| {
        let mut output = File::create_new(&temporary).map_err(|_| "METER_STATE_WRITE_FAILED")?;
        output
            .write_all(&bytes)
            .map_err(|_| "METER_STATE_WRITE_FAILED")?;
        output.sync_all().map_err(|_| "METER_STATE_WRITE_FAILED")?;
        fs::rename(&temporary, path).map_err(|_| "METER_STATE_WRITE_FAILED")
    })();
    if result.is_err() {
        let _ = fs::remove_file(&temporary);
    }
    result.map_err(str::to_owned)
}

pub fn scan_report_and_commit<F>(
    codex_home: &Path,
    state_path: &Path,
    previous: &ScanState,
    mut report: F,
) -> Result<ScanOutcome, String>
where
    F: FnMut(&[UsageEvent]) -> Result<(), String>,
{
    let outcome = scan(codex_home, previous)?;
    for batch in outcome.events.chunks(100) {
        report(batch)?;
    }
    if &outcome.next_state != previous || !state_path.exists() {
        save_state(state_path, &outcome.next_state)?;
    }
    Ok(outcome)
}

fn meter_endpoint(config: &MeterConfig, path: &str) -> Result<reqwest::Url, String> {
    reqwest::Url::parse(&config.server_url)
        .and_then(|base| base.join(path))
        .map_err(|_| "METER_SERVER_URL_INVALID".into())
}

pub fn send_events(
    client: &reqwest::blocking::Client,
    config: &MeterConfig,
    credential: &MeterCredential,
    events: &[UsageEvent],
) -> Result<(), String> {
    let response = client
        .post(meter_endpoint(config, "/api/v1/meter/usage/events")?)
        .header("X-Quota-Guard-Meter", &credential.device_token)
        .json(&serde_json::json!({ "events": events }))
        .send()
        .map_err(|_| "METER_BACKEND_UNAVAILABLE")?;
    match response.status().as_u16() {
        200..=299 => Ok(()),
        401 | 403 => Err("METER_PAIRING_EXPIRED".into()),
        400..=499 => Err("METER_REPORT_REJECTED".into()),
        _ => Err("METER_BACKEND_UNAVAILABLE".into()),
    }
}

pub fn send_heartbeat(
    client: &reqwest::blocking::Client,
    config: &MeterConfig,
    credential: &MeterCredential,
) -> Result<(), String> {
    let response = client
        .post(meter_endpoint(config, "/api/v1/meter/heartbeat")?)
        .header("X-Quota-Guard-Meter", &credential.device_token)
        .send()
        .map_err(|_| "METER_BACKEND_UNAVAILABLE")?;
    match response.status().as_u16() {
        200..=299 => Ok(()),
        401 | 403 => Err("METER_PAIRING_EXPIRED".into()),
        _ => Err("METER_BACKEND_UNAVAILABLE".into()),
    }
}

#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct MeterStatus {
    pub state: String,
    pub last_scan_epoch_seconds: u64,
    pub last_report_epoch_seconds: Option<u64>,
    pub reported_events: usize,
    pub error_code: Option<String>,
}

pub struct MeterRuntime {
    config: MeterConfig,
    client: reqwest::blocking::Client,
    audit: AuditLog,
    last_logged_state: Option<String>,
    last_logged_error: Option<String>,
    last_heartbeat: Option<Instant>,
    last_report_epoch_seconds: Option<u64>,
}

impl MeterRuntime {
    pub fn new(config: MeterConfig) -> Result<Self, String> {
        config.validate()?;
        let client = reqwest::blocking::Client::builder()
            .no_proxy()
            .connect_timeout(Duration::from_secs(5))
            .timeout(Duration::from_secs(10))
            .build()
            .map_err(|_| "METER_HTTP_UNAVAILABLE")?;
        let audit = AuditLog::new(&config.state_dir)?;
        audit.append(AuditKind::ServiceStarted, None, 0, false)?;
        Ok(Self {
            config,
            client,
            audit,
            last_logged_state: None,
            last_logged_error: None,
            last_heartbeat: None,
            last_report_epoch_seconds: None,
        })
    }

    pub fn tick(&mut self) -> MeterStatus {
        let now = epoch_seconds();
        let outcome = self.tick_inner();
        let mut status = match outcome {
            Ok((state, reported_events)) => MeterStatus {
                state: state.into(),
                last_scan_epoch_seconds: now,
                last_report_epoch_seconds: self.last_report_epoch_seconds,
                reported_events,
                error_code: None,
            },
            Err(code) => MeterStatus {
                state: "ERROR".into(),
                last_scan_epoch_seconds: now,
                last_report_epoch_seconds: self.last_report_epoch_seconds,
                reported_events: 0,
                error_code: Some(code),
            },
        };
        let changed = self.last_logged_state.as_deref() != Some(status.state.as_str())
            || self.last_logged_error.as_deref() != status.error_code.as_deref();
        let kind = if status.state == "ERROR" {
            changed.then_some(AuditKind::Error)
        } else if status.reported_events > 0 {
            // The accepted batch was logged before the heartbeat request.
            None
        } else if changed {
            Some(match status.state.as_str() {
                "BASELINE_READY" => AuditKind::BaselineReady,
                "UNPAIRED" => AuditKind::Unpaired,
                _ => AuditKind::StatusChange,
            })
        } else {
            None
        };
        if let Some(kind) = kind {
            if self.audit.append(kind, Some(&status), 0, false).is_err() {
                status.state = "ERROR".into();
                status.error_code = Some("METER_LOG_WRITE_FAILED".into());
            } else {
                self.last_logged_state = Some(status.state.clone());
                self.last_logged_error = status.error_code.clone();
            }
        }
        let _ = write_public_status(&self.config.status_file, &status);
        status
    }

    pub fn shutdown(&self) -> Result<(), String> {
        self.audit.append(AuditKind::ServiceStopped, None, 0, false)
    }

    fn tick_inner(&mut self) -> Result<(&'static str, usize), String> {
        let state_path = self.config.state_dir.join("scan-state.json");
        let first_pairing = load_credential(&self.config.state_dir)?.is_none()
            && self.config.pairing_inbox.exists();
        let fresh_baseline = if first_pairing {
            Some(baseline_codex_home(&self.config.codex_home)?)
        } else {
            None
        };
        let paired = consume_pairing(&self.config)?;
        if paired && first_pairing {
            if let Err(code) =
                save_state(&state_path, &fresh_baseline.ok_or("METER_STATE_INVALID")?)
            {
                let _ = fs::remove_file(credential_file(&self.config.state_dir));
                return Err(code);
            }
            return Ok(("BASELINE_READY", 0));
        }
        let previous = load_state(&state_path)?;
        if previous.is_none() {
            // Existing official activity has unknown ownership until this OS
            // profile is paired. Establish a byte cursor without charging it.
            let baseline = baseline_codex_home(&self.config.codex_home)?;
            save_state(&state_path, &baseline)?;
            return Ok(("BASELINE_READY", 0));
        }
        let Some(credential) = load_credential(&self.config.state_dir)? else {
            return Ok(("UNPAIRED", 0));
        };
        if credential.device_id != self.config.device_id {
            return Err("METER_CREDENTIAL_INVALID".into());
        }
        let reported = scan_report_and_commit(
            &self.config.codex_home,
            &state_path,
            &previous.unwrap(),
            |events| send_events(&self.client, &self.config, &credential, events),
        )?;
        if !reported.events.is_empty() {
            self.last_report_epoch_seconds = Some(epoch_seconds());
            let tokens = reported
                .events
                .iter()
                .fold(0u64, |sum, event| sum.saturating_add(event.total_tokens));
            let status = MeterStatus {
                state: "REPORTED".into(),
                last_scan_epoch_seconds: epoch_seconds(),
                last_report_epoch_seconds: self.last_report_epoch_seconds,
                reported_events: reported.events.len(),
                error_code: None,
            };
            self.audit
                .append(AuditKind::UsageReported, Some(&status), tokens, false)?;
        }
        if self
            .last_heartbeat
            .is_none_or(|last| last.elapsed() >= Duration::from_secs(30))
        {
            send_heartbeat(&self.client, &self.config, &credential)?;
            self.last_heartbeat = Some(Instant::now());
            let status = MeterStatus {
                state: if reported.events.is_empty() {
                    "UP_TO_DATE"
                } else {
                    "REPORTED"
                }
                .into(),
                last_scan_epoch_seconds: epoch_seconds(),
                last_report_epoch_seconds: self.last_report_epoch_seconds,
                reported_events: 0,
                error_code: None,
            };
            self.audit
                .append(AuditKind::Heartbeat, Some(&status), 0, true)?;
        }
        Ok((
            if reported.events.is_empty() {
                "UP_TO_DATE"
            } else {
                "REPORTED"
            },
            reported.events.len(),
        ))
    }
}

fn epoch_seconds() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_secs()
}

fn write_public_status(path: &Path, status: &MeterStatus) -> Result<(), String> {
    let parent = path.parent().ok_or("METER_STATUS_PATH_INVALID")?;
    fs::create_dir_all(parent).map_err(|_| "METER_STATUS_WRITE_FAILED")?;
    let bytes = serde_json::to_vec(status).map_err(|_| "METER_STATUS_WRITE_FAILED")?;
    let temporary = parent.join(format!(".meter-status-{}.tmp", uuid::Uuid::new_v4()));
    let result: Result<(), &str> = (|| {
        let mut output = File::create_new(&temporary).map_err(|_| "METER_STATUS_WRITE_FAILED")?;
        output
            .write_all(&bytes)
            .map_err(|_| "METER_STATUS_WRITE_FAILED")?;
        output.sync_all().map_err(|_| "METER_STATUS_WRITE_FAILED")?;
        fs::rename(&temporary, path).map_err(|_| "METER_STATUS_WRITE_FAILED")
    })();
    if result.is_err() {
        let _ = fs::remove_file(&temporary);
    }
    result.map_err(str::to_owned)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;
    use std::io::Read;
    use std::net::TcpListener;
    use std::path::{Path, PathBuf};

    struct TestDir(PathBuf);

    impl TestDir {
        fn new() -> Self {
            let directory = std::env::temp_dir()
                .join(format!("quota-guard-meter-test-{}", uuid::Uuid::new_v4()));
            fs::create_dir_all(&directory).unwrap();
            Self(directory)
        }

        fn path(&self) -> &Path {
            &self.0
        }
    }

    impl Drop for TestDir {
        fn drop(&mut self) {
            let _ = fs::remove_dir_all(&self.0);
        }
    }

    fn record(response_id: &str, tokens: u64) -> String {
        format!(
            "{{\"type\":\"token_usage_record\",\"timestamp\":\"2026-10-07T00:00:00Z\",\"payload\":{{\"response_id\":\"{response_id}\",\"usage\":{{\"total_tokens\":{tokens}}}}}}}\n"
        )
    }

    #[test]
    fn baselines_history_then_reports_only_new_official_records() {
        let root = TestDir::new();
        let sessions = root.path().join(".codex/sessions/2026");
        fs::create_dir_all(&sessions).unwrap();
        let log = sessions.join("run.jsonl");
        fs::write(&log, record("old", 10)).unwrap();

        let baseline = scan(root.path().join(".codex").as_path(), &ScanState::default()).unwrap();
        assert_eq!(baseline.events.len(), 1);
        let baseline_state = baseline.next_state;
        fs::write(&log, record("old", 10) + &record("new", 20)).unwrap();

        let fresh = scan(root.path().join(".codex").as_path(), &baseline_state).unwrap();
        assert_eq!(fresh.events.len(), 1);
        assert_eq!(fresh.events[0].total_tokens, 20);
        assert!(fresh.events[0]
            .event_id
            .starts_with("codex-local-usage:v1:"));
    }

    #[test]
    fn fast_baseline_skips_old_log_contents_and_only_reads_future_appends() {
        let root = TestDir::new();
        let home = root.path().join(".codex");
        let sessions = home.join("sessions/2026");
        fs::create_dir_all(&sessions).unwrap();
        let log = sessions.join("history.jsonl");
        fs::write(&log, b"old-not-json\n").unwrap();

        let baseline = baseline_codex_home(&home).unwrap();
        assert_eq!(baseline.files["2026/history.jsonl"].offset, 13);
        use std::io::Write;
        fs::OpenOptions::new()
            .append(true)
            .open(&log)
            .unwrap()
            .write_all(record("new-response", 25).as_bytes())
            .unwrap();

        let fresh = scan(&home, &baseline).unwrap();
        assert_eq!(fresh.events.len(), 1);
        assert_eq!(fresh.events[0].total_tokens, 25);
    }

    #[test]
    fn failed_upload_does_not_commit_cursor_and_retries_same_event() {
        let root = TestDir::new();
        let home = root.path().join(".codex");
        let sessions = home.join("sessions");
        fs::create_dir_all(&sessions).unwrap();
        let log = sessions.join("run.jsonl");
        fs::write(&log, record("new", 20)).unwrap();
        let state_file = root.path().join("meter-state.json");
        let initial = ScanState::default();

        assert!(
            scan_report_and_commit(&home, &state_file, &initial, |_| Err("offline".into()))
                .is_err()
        );
        assert!(!state_file.exists());

        let mut seen = Vec::new();
        scan_report_and_commit(&home, &state_file, &initial, |events| {
            seen.extend(events.iter().map(|event| event.event_id.clone()));
            Ok(())
        })
        .unwrap();
        assert_eq!(seen.len(), 1);
        assert!(state_file.exists());
    }

    #[test]
    fn configuration_rejects_public_plain_http_and_accepts_private_lan() {
        let root = TestDir::new();
        let mut config = MeterConfig {
            server_url: "http://149.88.90.196:8080".into(),
            codex_home: root.path().join(".codex"),
            pairing_inbox: root.path().join("pairing.json"),
            state_dir: root.path().join("state"),
            status_file: root.path().join("status.json"),
            device_id: uuid::Uuid::new_v4(),
        };
        assert!(config.validate().is_err());
        config.server_url = "http://192.168.0.102:8080".into();
        assert!(config.validate().is_ok());
    }

    #[test]
    fn pairing_moves_a_meter_only_token_into_service_state_and_removes_inbox() {
        let root = TestDir::new();
        let config = MeterConfig {
            server_url: "http://127.0.0.1:8080".into(),
            codex_home: root.path().join(".codex"),
            pairing_inbox: root.path().join("pairing.json"),
            state_dir: root.path().join("state"),
            status_file: root.path().join("status.json"),
            device_id: uuid::Uuid::new_v4(),
        };
        let token = "A".repeat(43);
        fs::write(
            &config.pairing_inbox,
            format!(
                "{{\"deviceId\":\"{}\",\"deviceToken\":\"{}\"}}",
                config.device_id, token,
            ),
        )
        .unwrap();

        assert!(consume_pairing(&config).unwrap());
        assert!(!config.pairing_inbox.exists());
        assert_eq!(
            load_credential(&config.state_dir)
                .unwrap()
                .unwrap()
                .device_token,
            token
        );
    }

    #[test]
    fn new_pairing_rebaselines_unattributed_usage_before_reporting() {
        let root = TestDir::new();
        let home = root.path().join(".codex");
        let sessions = home.join("sessions");
        fs::create_dir_all(&sessions).unwrap();
        let log = sessions.join("run.jsonl");
        fs::write(&log, record("old", 10)).unwrap();
        let state_dir = root.path().join("state");
        let state_path = state_dir.join("scan-state.json");
        save_state(&state_path, &baseline_codex_home(&home).unwrap()).unwrap();
        fs::write(&log, record("old", 10) + &record("before-pairing", 20)).unwrap();
        let config = MeterConfig {
            server_url: "http://127.0.0.1:1".into(),
            codex_home: home.clone(),
            pairing_inbox: root.path().join("pairing.json"),
            state_dir: state_dir.clone(),
            status_file: root.path().join("status.json"),
            device_id: uuid::Uuid::new_v4(),
        };
        fs::write(
            &config.pairing_inbox,
            format!(
                "{{\"deviceId\":\"{}\",\"deviceToken\":\"{}\"}}",
                config.device_id,
                "A".repeat(43)
            ),
        )
        .unwrap();

        let mut runtime = MeterRuntime::new(config).unwrap();
        let first = runtime.tick();
        assert_eq!(first.state, "BASELINE_READY");
        assert_eq!(first.reported_events, 0);

        fs::write(
            &log,
            record("old", 10) + &record("before-pairing", 20) + &record("after-pairing", 30),
        )
        .unwrap();
        let fresh = scan(&home, &load_state(&state_path).unwrap().unwrap()).unwrap();
        assert_eq!(fresh.events.len(), 1);
        assert_eq!(fresh.events[0].total_tokens, 30);
    }

    #[test]
    fn rotating_an_existing_pairing_never_discards_pending_usage() {
        let root = TestDir::new();
        let home = root.path().join(".codex");
        let sessions = home.join("sessions");
        fs::create_dir_all(&sessions).unwrap();
        let log = sessions.join("run.jsonl");
        fs::write(&log, record("old", 10)).unwrap();
        let state_dir = root.path().join("state");
        let state_path = state_dir.join("scan-state.json");
        save_state(&state_path, &baseline_codex_home(&home).unwrap()).unwrap();
        let device_id = uuid::Uuid::new_v4();
        save_credential(
            &state_dir,
            &MeterCredential {
                device_id,
                device_token: "A".repeat(43),
            },
        )
        .unwrap();
        fs::write(&log, record("old", 10) + &record("pending", 20)).unwrap();
        let config = MeterConfig {
            server_url: "http://127.0.0.1:1".into(),
            codex_home: home.clone(),
            pairing_inbox: root.path().join("pairing.json"),
            state_dir: state_dir.clone(),
            status_file: root.path().join("status.json"),
            device_id,
        };
        fs::write(
            &config.pairing_inbox,
            format!(
                "{{\"deviceId\":\"{}\",\"deviceToken\":\"{}\"}}",
                config.device_id,
                "B".repeat(43)
            ),
        )
        .unwrap();

        let mut runtime = MeterRuntime::new(config).unwrap();
        assert_eq!(runtime.tick().state, "ERROR");
        let pending = scan(&home, &load_state(&state_path).unwrap().unwrap()).unwrap();
        assert_eq!(pending.events.len(), 1);
        assert_eq!(pending.events[0].total_tokens, 20);
    }

    #[test]
    fn runtime_keeps_a_local_history_of_start_baseline_and_unpaired_state() {
        let root = TestDir::new();
        let config = MeterConfig {
            server_url: "http://127.0.0.1:1".into(),
            codex_home: root.path().join(".codex"),
            pairing_inbox: root.path().join("pairing.json"),
            state_dir: root.path().join("state"),
            status_file: root.path().join("status.json"),
            device_id: uuid::Uuid::new_v4(),
        };
        let mut runtime = MeterRuntime::new(config).unwrap();
        assert_eq!(runtime.tick().state, "BASELINE_READY");
        assert_eq!(runtime.tick().state, "UNPAIRED");

        let content = fs::read_to_string(root.path().join("state/logs/meter.jsonl")).unwrap();
        let kinds: Vec<String> = content
            .lines()
            .map(|line| {
                serde_json::from_str::<serde_json::Value>(line).unwrap()["kind"]
                    .as_str()
                    .unwrap()
                    .to_owned()
            })
            .collect();
        assert_eq!(kinds, ["SERVICE_STARTED", "BASELINE_READY", "UNPAIRED"]);
        assert!(!content.contains("deviceToken"));
        assert!(!content.contains("response_id"));
    }

    #[test]
    fn graceful_shutdown_is_written_to_the_activity_log() {
        let root = TestDir::new();
        let config = MeterConfig {
            server_url: "http://127.0.0.1:1".into(),
            codex_home: root.path().join(".codex"),
            pairing_inbox: root.path().join("pairing.json"),
            state_dir: root.path().join("state"),
            status_file: root.path().join("status.json"),
            device_id: uuid::Uuid::new_v4(),
        };
        let runtime = MeterRuntime::new(config).unwrap();
        runtime.shutdown().unwrap();
        let content = fs::read_to_string(root.path().join("state/logs/meter.jsonl")).unwrap();
        let last: serde_json::Value =
            serde_json::from_str(content.lines().last().unwrap()).unwrap();
        assert_eq!(last["kind"], "SERVICE_STOPPED");
    }

    #[test]
    fn fixed_error_codes_are_recorded_without_the_invalid_pairing_contents() {
        let root = TestDir::new();
        let config = MeterConfig {
            server_url: "http://127.0.0.1:1".into(),
            codex_home: root.path().join(".codex"),
            pairing_inbox: root.path().join("pairing.json"),
            state_dir: root.path().join("state"),
            status_file: root.path().join("status.json"),
            device_id: uuid::Uuid::new_v4(),
        };
        fs::write(&config.pairing_inbox, "private-canary-not-json").unwrap();
        let mut runtime = MeterRuntime::new(config).unwrap();
        assert_eq!(
            runtime.tick().error_code.as_deref(),
            Some("METER_PAIRING_INVALID")
        );

        let content = fs::read_to_string(root.path().join("state/logs/meter.jsonl")).unwrap();
        assert!(content.contains("METER_PAIRING_INVALID"));
        assert!(!content.contains("private-canary"));
    }

    #[test]
    fn audit_write_failure_is_visible_in_the_public_status() {
        let root = TestDir::new();
        let config = MeterConfig {
            server_url: "http://127.0.0.1:1".into(),
            codex_home: root.path().join(".codex"),
            pairing_inbox: root.path().join("pairing.json"),
            state_dir: root.path().join("state"),
            status_file: root.path().join("status.json"),
            device_id: uuid::Uuid::new_v4(),
        };
        let mut runtime = MeterRuntime::new(config).unwrap();
        fs::remove_dir_all(root.path().join("state/logs")).unwrap();
        fs::write(root.path().join("state/logs"), b"blocked").unwrap();

        let status = runtime.tick();
        assert_eq!(status.state, "ERROR");
        assert_eq!(status.error_code.as_deref(), Some("METER_LOG_WRITE_FAILED"));
        let public: serde_json::Value =
            serde_json::from_slice(&fs::read(root.path().join("status.json")).unwrap()).unwrap();
        assert_eq!(public["errorCode"], "METER_LOG_WRITE_FAILED");
    }

    #[test]
    fn runtime_logs_reported_token_total_and_successful_heartbeat_without_credentials() {
        let root = TestDir::new();
        let home = root.path().join(".codex");
        let sessions = home.join("sessions");
        fs::create_dir_all(&sessions).unwrap();
        let log = sessions.join("run.jsonl");
        fs::write(&log, record("old", 1)).unwrap();
        let state_dir = root.path().join("state");
        save_state(
            &state_dir.join("scan-state.json"),
            &baseline_codex_home(&home).unwrap(),
        )
        .unwrap();
        fs::write(
            &log,
            record("old", 1) + &record("fresh-private-response", 42),
        )
        .unwrap();
        let device_id = uuid::Uuid::new_v4();
        let private_token = "A".repeat(43);
        save_credential(
            &state_dir,
            &MeterCredential {
                device_id,
                device_token: private_token.clone(),
            },
        )
        .unwrap();

        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let port = listener.local_addr().unwrap().port();
        let receiver = std::thread::spawn(move || {
            for _ in 0..2 {
                let (mut stream, _) = listener.accept().unwrap();
                stream
                    .set_read_timeout(Some(Duration::from_secs(5)))
                    .unwrap();
                let mut bytes = Vec::new();
                let mut buffer = [0u8; 4096];
                loop {
                    let count = stream.read(&mut buffer).unwrap();
                    bytes.extend_from_slice(&buffer[..count]);
                    if bytes.windows(4).any(|part| part == b"\r\n\r\n") {
                        break;
                    }
                }
                use std::io::Write;
                stream
                    .write_all(
                        b"HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}",
                    )
                    .unwrap();
            }
        });
        let config = MeterConfig {
            server_url: format!("http://127.0.0.1:{port}"),
            codex_home: home,
            pairing_inbox: root.path().join("pairing.json"),
            state_dir: state_dir.clone(),
            status_file: root.path().join("status.json"),
            device_id,
        };
        let mut runtime = MeterRuntime::new(config).unwrap();
        let status = runtime.tick();
        receiver.join().unwrap();
        assert_eq!(status.state, "REPORTED");

        let content = fs::read_to_string(state_dir.join("logs/meter.jsonl")).unwrap();
        let rows: Vec<serde_json::Value> = content
            .lines()
            .map(|line| serde_json::from_str(line).unwrap())
            .collect();
        assert!(rows.iter().any(|row| row["kind"] == "USAGE_REPORTED"
            && row["reportedTokens"] == 42
            && row["reportedEvents"] == 1));
        assert!(rows
            .iter()
            .any(|row| row["kind"] == "HEARTBEAT" && row["heartbeatSent"] == true));
        assert!(!content.contains(&private_token));
        assert!(!content.contains("fresh-private-response"));
    }

    #[test]
    fn usage_transport_sends_only_meter_token_and_safe_event_fields() {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let port = listener.local_addr().unwrap().port();
        let receiver = std::thread::spawn(move || {
            let (mut stream, _) = listener.accept().unwrap();
            stream
                .set_read_timeout(Some(std::time::Duration::from_secs(5)))
                .unwrap();
            let mut bytes = Vec::new();
            let mut buffer = [0u8; 4096];
            loop {
                let count = stream.read(&mut buffer).unwrap();
                bytes.extend_from_slice(&buffer[..count]);
                if bytes.windows(4).any(|part| part == b"\r\n\r\n") {
                    break;
                }
            }
            let request = String::from_utf8_lossy(&bytes);
            assert!(request.starts_with("POST /api/v1/meter/usage/events HTTP/1.1"));
            assert!(request
                .to_ascii_lowercase()
                .contains("x-quota-guard-meter: aaaaaaaaaa"));
            assert!(!request
                .to_ascii_lowercase()
                .contains("authorization: bearer"));
            use std::io::Write;
            stream
                .write_all(b"HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}")
                .unwrap();
        });
        let root = TestDir::new();
        let config = MeterConfig {
            server_url: format!("http://127.0.0.1:{port}"),
            codex_home: root.path().join(".codex"),
            pairing_inbox: root.path().join("pairing.json"),
            state_dir: root.path().join("state"),
            status_file: root.path().join("status.json"),
            device_id: uuid::Uuid::new_v4(),
        };
        let credential = MeterCredential {
            device_id: config.device_id,
            device_token: "A".repeat(43),
        };
        let event = UsageEvent {
            event_id: format!("codex-local-usage:v1:{}", "a".repeat(64)),
            occurred_at: "2026-10-07T00:00:00Z".into(),
            total_tokens: 20,
            source: "TOKEN_USAGE_RECORD".into(),
        };
        let client = reqwest::blocking::Client::builder()
            .no_proxy()
            .build()
            .unwrap();

        send_events(&client, &config, &credential, &[event]).unwrap();
        receiver.join().unwrap();
    }
}
