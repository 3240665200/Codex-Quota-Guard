use chrono::{DateTime, TimeDelta, Utc};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::collections::{BTreeMap, VecDeque};
use std::fs::{self, File};
use std::io::{BufRead, BufReader, Read, Seek, SeekFrom, Write};
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Condvar, Mutex};
use std::thread::{self, JoinHandle};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

mod audit;
mod outbox;
mod review_upload;
mod collectors;
use collectors as speed_mode;
pub fn run_codex_cli(arguments: &[std::ffi::OsString]) -> Result<i32, String> {
    collectors::run_cli(arguments)
}
pub fn speed_detection_probe(home: &Path) -> BTreeMap<String, usize> {
    let mut modes = BTreeMap::new();
    for event in speed_mode::recent_corrections(home) { *modes.entry(event.service_tier).or_default() += 1; }
    modes
}
mod server_state;
use audit::{AuditKind, AuditLog};
pub use server_state::prepare_server_state;
#[cfg(windows)]
mod ccswitch_guard;
#[cfg(windows)]
#[path = "../../conflict_evidence.rs"]
mod conflict_evidence;

const MAX_RECORD_BYTES: usize = 4 * 1024 * 1024;
const MAX_GUARD_STATUS_BYTES: usize = 4096;
const PREFIX_BYTES: usize = 4096;
const MAX_CACHED_TURNS: usize = 256;
const SCAN_CURSOR_VERSION: u8 = 1;
const LEGACY_FILE_SETTLING_DELAY: Duration = Duration::from_secs(30);
const CAPTURE_INTERVAL: Duration = Duration::from_secs(3);
const GUARD_STATUS_FILE: &str = "ccswitch-guard-status.json";
const GUARD_POLL_INTERVAL: Duration = Duration::from_secs(1);
const GUARD_STATUS_FRESH: Duration = Duration::from_secs(30);

#[derive(Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct MeterConfig {
    pub server_url: String,
    pub codex_home: PathBuf,
    pub pairing_inbox: PathBuf,
    pub state_dir: PathBuf,
    #[serde(default)]
    pub log_dir: Option<PathBuf>,
    pub status_file: PathBuf,
    pub device_id: uuid::Uuid,
    #[serde(default)]
    pub owner_user_sid: Option<String>,
}

impl MeterConfig {
    pub fn validate(&self) -> Result<(), String> {
        normalized_server_origin(&self.server_url).ok_or("METER_SERVER_URL_INVALID")?;
        if !self.codex_home.is_absolute()
            || !self.pairing_inbox.is_absolute()
            || !self.state_dir.is_absolute()
            || !self.status_file.is_absolute()
            || self
                .log_dir
                .as_ref()
                .is_some_and(|path| !path.is_absolute())
        {
            return Err("METER_PATH_INVALID".into());
        }
        if self.owner_user_sid.as_deref().is_some_and(|sid| {
            sid.len() > 184
                || !sid.starts_with("S-1-")
                || sid
                    .split('-')
                    .skip(2)
                    .any(|part| part.is_empty() || !part.bytes().all(|c| c.is_ascii_digit()))
        }) {
            return Err("METER_OWNER_SID_INVALID".into());
        }
        Ok(())
    }
}

fn normalized_server_origin(value: &str) -> Option<String> {
    let url = reqwest::Url::parse(value).ok()?;
    if url.host_str().is_none()
        || !matches!(url.scheme(), "http" | "https")
        || !url.username().is_empty()
        || url.password().is_some()
        || url.query().is_some()
        || url.fragment().is_some()
        || url.path() != "/"
    {
        return None;
    }
    Some(url.origin().ascii_serialization())
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct PairingInbox {
    device_id: uuid::Uuid,
    device_token: String,
    #[serde(default)]
    server_origin: Option<String>,
    #[serde(default)]
    user_id: Option<uuid::Uuid>,
}

#[derive(Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct MeterCredential {
    pub device_id: uuid::Uuid,
    pub device_token: String,
    #[serde(default)]
    pub user_id: Option<uuid::Uuid>,
    #[serde(default)]
    owner_verified: bool,
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
    let pairing_origin = pairing
        .server_origin
        .as_deref()
        .and_then(normalized_server_origin);
    if pairing_origin.is_none() || pairing_origin != normalized_server_origin(&config.server_url) {
        return Err("METER_PAIRING_SERVER_MISMATCH".into());
    }
    let credential = MeterCredential {
        device_id: pairing.device_id,
        device_token: pairing.device_token,
        user_id: pairing.user_id,
        owner_verified: false,
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

#[derive(Clone, Debug, Default, Deserialize, PartialEq, Eq, Serialize)]
struct FileCursor {
    #[serde(default)]
    current_turn_hash: Option<String>,
    offset: u64,
    prefix_len: usize,
    prefix_hash: String,
    #[serde(default)]
    cursor_version: u8,
    #[serde(default)]
    legacy_usage: Option<CumulativeUsage>,
    #[serde(default)]
    direct_usage_record_observed: bool,
    #[serde(default)]
    legacy_fallback_released: bool,
    #[serde(default)]
    legacy_settled_cutoff: Option<String>,
    #[serde(default)]
    pending_fallback_events: Vec<UsageEvent>,
    #[serde(default)]
    current_metadata: TurnMetadata,
    #[serde(default)]
    turn_metadata: BTreeMap<String, TurnMetadata>,
    #[serde(default)]
    turn_metadata_order: VecDeque<String>,
}

#[derive(Clone, Debug, Default, Deserialize, PartialEq, Eq, Serialize)]
struct CumulativeUsage {
    input_tokens: Option<u64>,
    cached_input_tokens: Option<u64>,
    cache_write_input_tokens: Option<u64>,
    output_tokens: Option<u64>,
    reasoning_output_tokens: Option<u64>,
    total_tokens: u64,
}

#[derive(Clone, Debug, Default, Deserialize, PartialEq, Eq, Serialize)]
struct TurnMetadata {
    model: Option<String>,
    reasoning_effort: Option<String>,
    #[serde(default)]
    service_tier: Option<String>,
}

#[derive(Default)]
struct MetadataOverrides {
    model: Option<Option<String>>,
    reasoning_effort: Option<Option<String>>,
    service_tier: Option<Option<String>>,
}

impl FileCursor {
    fn fresh() -> Self {
        Self {
            cursor_version: SCAN_CURSOR_VERSION,
            ..Self::default()
        }
    }

    fn remember_turn_metadata(&mut self, turn_id: &str, metadata: TurnMetadata) {
        let turn_hash = hex_digest(turn_id.as_bytes());
        self.turn_metadata_order
            .retain(|cached_hash| cached_hash != &turn_hash);
        self.turn_metadata
            .insert(turn_hash.clone(), metadata.sanitized());
        self.turn_metadata_order.push_back(turn_hash);
        while self.turn_metadata_order.len() > MAX_CACHED_TURNS {
            if let Some(evicted_hash) = self.turn_metadata_order.pop_front() {
                self.turn_metadata.remove(&evicted_hash);
            }
        }
    }

    fn sanitize_metadata_cache(&mut self) {
        self.current_metadata = self.current_metadata.sanitized();
        let stored_metadata = std::mem::take(&mut self.turn_metadata);
        let stored_order = std::mem::take(&mut self.turn_metadata_order);
        let mut turn_metadata = BTreeMap::new();
        let mut turn_metadata_order = VecDeque::new();

        for turn_hash in stored_order {
            if is_turn_hash(&turn_hash) && !turn_metadata.contains_key(&turn_hash) {
                if let Some(metadata) = stored_metadata.get(&turn_hash) {
                    turn_metadata.insert(turn_hash.clone(), metadata.sanitized());
                    turn_metadata_order.push_back(turn_hash);
                }
            }
        }
        for (turn_hash, metadata) in stored_metadata {
            if is_turn_hash(&turn_hash) && !turn_metadata.contains_key(&turn_hash) {
                turn_metadata.insert(turn_hash.clone(), metadata.sanitized());
                turn_metadata_order.push_back(turn_hash);
            }
        }
        while turn_metadata_order.len() > MAX_CACHED_TURNS {
            if let Some(evicted_hash) = turn_metadata_order.pop_front() {
                turn_metadata.remove(&evicted_hash);
            }
        }

        self.turn_metadata = turn_metadata;
        self.turn_metadata_order = turn_metadata_order;
    }
}

impl ScanState {
    fn sanitize_metadata_caches(&mut self) {
        for cursor in self.files.values_mut() {
            cursor.sanitize_metadata_cache();
        }
    }
}

impl TurnMetadata {
    fn sanitized(&self) -> Self {
        Self {
            model: self
                .model
                .as_deref()
                .and_then(validate_model)
                .map(str::to_owned),
            reasoning_effort: self
                .reasoning_effort
                .as_deref()
                .and_then(validate_reasoning_effort)
                .map(str::to_owned),
            service_tier: self.service_tier.as_deref().and_then(validate_service_tier).map(str::to_owned),
        }
    }
}

#[derive(Clone, Debug, Deserialize, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct UsageEvent {
    pub event_id: String,
    pub occurred_at: String,
    pub total_tokens: u64,
    pub source: String,
    #[serde(default)]
    pub model: Option<String>,
    #[serde(default)]
    pub reasoning_effort: Option<String>,
    #[serde(default)]
    pub service_tier: Option<String>,
    #[serde(default)]
    pub input_tokens: Option<u64>,
    #[serde(default)]
    pub cached_input_tokens: Option<u64>,
    #[serde(default)]
    pub cache_write_input_tokens: Option<u64>,
    #[serde(default)]
    pub output_tokens: Option<u64>,
    #[serde(default)]
    pub reasoning_output_tokens: Option<u64>,
}

#[derive(Debug)]
pub struct ScanOutcome {
    pub events: Vec<UsageEvent>,
    pub next_state: ScanState,
}

pub use collectors::codex_rollout::{baseline_codex_home, scan};
use collectors::codex_rollout::{
    parse_token_usage_record, scan_with_boundary, session_files, validate_model,
    validate_reasoning_effort, validate_service_tier,
};
#[cfg(test)]
use collectors::codex_rollout::{parse_direct_record, process_session_record, rebuild_legacy_cursor};

fn is_turn_hash(value: &str) -> bool {
    value.len() == 64
        && value
            .bytes()
            .all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(&byte))
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
    let mut state: ScanState = serde_json::from_slice(&bytes).map_err(|_| "METER_STATE_INVALID")?;
    state.sanitize_metadata_caches();
    Ok(Some(state))
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
    report: F,
) -> Result<ScanOutcome, String>
where
    F: FnMut(&[UsageEvent]) -> Result<(), String>,
{
    scan_report_and_commit_guarded(
        codex_home,
        state_path,
        previous,
        || Ok(()),
        report,
        |state| save_state(state_path, state),
    )
}

fn scan_report_and_commit_guarded<F, G, C>(
    codex_home: &Path,
    state_path: &Path,
    previous: &ScanState,
    mut ensure_allowed: G,
    mut report: F,
    mut commit: C,
) -> Result<ScanOutcome, String>
where
    F: FnMut(&[UsageEvent]) -> Result<(), String>,
    G: FnMut() -> Result<(), String>,
    C: FnMut(&ScanState) -> Result<(), String>,
{
    ensure_allowed()?;
    let outcome = scan(codex_home, previous)?;
    ensure_allowed()?;
    let now = Utc::now();
    let events: Vec<_> = outcome
        .events
        .into_iter()
        .filter(|event| reportable_at(event, now))
        .collect();
    for batch in events.chunks(100) {
        ensure_allowed()?;
        report(batch)?;
    }
    // A completed HTTP request cannot be undone, but a failed guard must stop
    // later batches and prevent the accounting cursor from moving forward.
    ensure_allowed()?;
    if &outcome.next_state != previous || !state_path.exists() {
        commit(&outcome.next_state)?;
    }
    Ok(ScanOutcome {
        events,
        next_state: outcome.next_state,
    })
}

fn reportable_at(event: &UsageEvent, now: DateTime<Utc>) -> bool {
    DateTime::parse_from_rfc3339(&event.occurred_at)
        .ok()
        .is_some_and(|timestamp| {
            let timestamp = timestamp.with_timezone(&Utc);
            timestamp >= now - TimeDelta::days(30) && timestamp <= now + TimeDelta::minutes(4)
        })
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
    send_events_guarded(client, config, credential, events, || Ok(()))
}

fn send_events_guarded(
    client: &reqwest::blocking::Client,
    config: &MeterConfig,
    credential: &MeterCredential,
    events: &[UsageEvent],
    ensure_allowed: impl FnMut() -> Result<(), String>,
) -> Result<(), String> {
    with_device_recovery(client, config, credential, ensure_allowed, || {
        send_events_once(client, config, credential, events)
    })
}

fn send_events_once(
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
    send_heartbeat_guarded(client, config, credential, || Ok(()))
}

fn send_heartbeat_guarded(
    client: &reqwest::blocking::Client,
    config: &MeterConfig,
    credential: &MeterCredential,
    ensure_allowed: impl FnMut() -> Result<(), String>,
) -> Result<(), String> {
    with_device_recovery(client, config, credential, ensure_allowed, || {
        send_heartbeat_once(client, config, credential)
    })
}

fn send_heartbeat_once(
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

fn with_device_recovery(
    client: &reqwest::blocking::Client,
    config: &MeterConfig,
    credential: &MeterCredential,
    mut ensure_allowed: impl FnMut() -> Result<(), String>,
    mut operation: impl FnMut() -> Result<(), String>,
) -> Result<(), String> {
    ensure_allowed()?;
    match operation() {
        Err(code) if code == "METER_PAIRING_EXPIRED" => {
            ensure_allowed()?;
            let user_id = renew_device_credential(client, config, credential)?;
            if credential.user_id != Some(user_id) {
                return Err("METER_PAIRING_OWNER_MISMATCH".into());
            }
            ensure_allowed()?;
            operation()
        }
        result => result,
    }
}

fn renew_device_credential(
    client: &reqwest::blocking::Client,
    config: &MeterConfig,
    credential: &MeterCredential,
) -> Result<uuid::Uuid, String> {
    let response = client
        .post(meter_endpoint(config, "/api/v1/meter/credential/renew")?)
        .header("X-Quota-Guard-Meter", &credential.device_token)
        .send()
        .map_err(|_| "METER_BACKEND_UNAVAILABLE")?;
    match response.status().as_u16() {
        200..=299 => {}
        401 | 403 => return Err("METER_PAIRING_EXPIRED".into()),
        _ => return Err("METER_BACKEND_UNAVAILABLE".into()),
    }
    #[derive(Deserialize)]
    #[serde(rename_all = "camelCase")]
    struct Renewal {
        device_id: uuid::Uuid,
        user_id: uuid::Uuid,
        expires_at: String,
    }
    let mut bytes = Vec::new();
    response
        .take(4097)
        .read_to_end(&mut bytes)
        .map_err(|_| "METER_RENEWAL_INVALID")?;
    if bytes.len() > 4096 {
        return Err("METER_RENEWAL_INVALID".into());
    }
    let renewal: Renewal = serde_json::from_slice(&bytes).map_err(|_| "METER_RENEWAL_INVALID")?;
    if renewal.device_id != credential.device_id
        || !DateTime::parse_from_rfc3339(&renewal.expires_at)
            .is_ok_and(|expires| expires > Utc::now())
    {
        return Err("METER_RENEWAL_INVALID".into());
    }
    Ok(renewal.user_id)
}

#[derive(Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct MeterStatus {
    pub state: String,
    pub version: String,
    pub last_scan_epoch_seconds: u64,
    pub last_report_epoch_seconds: Option<u64>,
    pub reported_events: usize,
    #[serde(default)]
    pub pending_events: Option<usize>,
    #[serde(default)]
    pub deferred_events: usize,
    #[serde(default)]
    pub review_submitted_events: usize,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub review_error_code: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub warning_code: Option<String>,
    pub error_code: Option<String>,
    pub cc_switch_guard: String,
    pub cc_switch_guard_checked_at: u64,
    pub cc_switch_guard_lease_id: Option<uuid::Uuid>,
}

#[derive(Clone)]
struct GuardSnapshot {
    state: &'static str,
    checked_at: u64,
    lease_id: Option<uuid::Uuid>,
    error_code: Option<String>,
    detected: bool,
    confirmed_termination_count: u64,
    confirmed_last_terminated_at: Option<u64>,
    termination_epoch: uuid::Uuid,
    termination_count: u64,
    last_terminated_at: Option<u64>,
}

impl GuardSnapshot {
    fn new(
        state: &'static str,
        checked_at: u64,
        lease_id: Option<uuid::Uuid>,
        error_code: Option<String>,
        detected: bool,
    ) -> Self {
        Self {
            state,
            checked_at,
            lease_id,
            error_code,
            detected,
            confirmed_termination_count: 0,
            confirmed_last_terminated_at: None,
            termination_epoch: uuid::Uuid::new_v4(),
            termination_count: 0,
            last_terminated_at: None,
        }
    }

    #[cfg(test)]
    fn inactive() -> Self {
        Self::new("INACTIVE", epoch_seconds(), None, None, false)
    }

    #[cfg(test)]
    fn error(code: impl Into<String>) -> Self {
        Self::new("ERROR", epoch_seconds(), None, Some(code.into()), false)
    }
}

#[derive(Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct GuardStatus {
    cc_switch_guard: String,
    cc_switch_guard_checked_at: u64,
    cc_switch_guard_lease_id: Option<uuid::Uuid>,
    cc_switch_termination_epoch: uuid::Uuid,
    cc_switch_termination_count: u64,
    cc_switch_last_terminated_at: Option<u64>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct LegacyGuardStatus {
    cc_switch_guard: String,
    cc_switch_guard_checked_at: u64,
    cc_switch_guard_lease_id: Option<uuid::Uuid>,
}

#[derive(Clone)]
struct GuardTerminationLedger {
    lease_id: Option<uuid::Uuid>,
    epoch: uuid::Uuid,
    count: u64,
    last_terminated_at: Option<u64>,
}

impl GuardTerminationLedger {
    fn new(lease_id: Option<uuid::Uuid>) -> Self {
        Self {
            lease_id,
            epoch: uuid::Uuid::new_v4(),
            count: 0,
            last_terminated_at: None,
        }
    }

    fn recover(lease_id: Option<uuid::Uuid>, stored: Option<GuardStatus>) -> Self {
        if let (Some(lease_id), Some(status)) = (lease_id, stored) {
            if status.cc_switch_guard_lease_id == Some(lease_id) {
                return Self {
                    lease_id: Some(lease_id),
                    epoch: status.cc_switch_termination_epoch,
                    count: status.cc_switch_termination_count,
                    last_terminated_at: status.cc_switch_last_terminated_at,
                };
            }
        }
        Self::new(lease_id)
    }

    fn apply(&mut self, mut checked: GuardSnapshot) -> GuardSnapshot {
        if self.lease_id != checked.lease_id {
            *self = Self::new(checked.lease_id);
        }
        if checked.confirmed_termination_count > 0 {
            self.count = self
                .count
                .saturating_add(checked.confirmed_termination_count);
            if let Some(terminated_at) = checked.confirmed_last_terminated_at {
                self.last_terminated_at = Some(terminated_at);
            }
        }
        checked.confirmed_termination_count = 0;
        checked.confirmed_last_terminated_at = None;
        checked.termination_epoch = self.epoch;
        checked.termination_count = self.count;
        checked.last_terminated_at = self.last_terminated_at;
        checked
    }
}

fn guard_status_path(config: &MeterConfig) -> Result<PathBuf, String> {
    config
        .status_file
        .parent()
        .map(|parent| parent.join(GUARD_STATUS_FILE))
        .ok_or_else(|| "METER_STATUS_PATH_INVALID".into())
}

fn write_guard_status(path: &Path, guard: &GuardSnapshot) -> Result<(), String> {
    let parent = path.parent().ok_or("METER_STATUS_PATH_INVALID")?;
    fs::create_dir_all(parent).map_err(|_| "METER_STATUS_WRITE_FAILED")?;
    let status = GuardStatus {
        cc_switch_guard: guard.state.to_owned(),
        cc_switch_guard_checked_at: guard.checked_at,
        cc_switch_guard_lease_id: guard.lease_id,
        cc_switch_termination_epoch: guard.termination_epoch,
        cc_switch_termination_count: guard.termination_count,
        cc_switch_last_terminated_at: guard.last_terminated_at,
    };
    let bytes = serde_json::to_vec(&status).map_err(|_| "METER_STATUS_WRITE_FAILED")?;
    let temporary = parent.join(format!(
        ".ccswitch-guard-status-{}.tmp",
        uuid::Uuid::new_v4()
    ));
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

fn read_guard_status(path: &Path) -> Option<GuardStatus> {
    let metadata = fs::symlink_metadata(path).ok()?;
    if !metadata.file_type().is_file()
        || metadata.len() == 0
        || metadata.len() > MAX_GUARD_STATUS_BYTES as u64
    {
        return None;
    }
    let input = File::open(path).ok()?;
    let opened_metadata = input.metadata().ok()?;
    if !opened_metadata.is_file()
        || opened_metadata.len() == 0
        || opened_metadata.len() > MAX_GUARD_STATUS_BYTES as u64
    {
        return None;
    }
    let mut bytes = Vec::new();
    input
        .take((MAX_GUARD_STATUS_BYTES + 1) as u64)
        .read_to_end(&mut bytes)
        .ok()?;
    if bytes.is_empty() || bytes.len() > MAX_GUARD_STATUS_BYTES {
        return None;
    }
    if let Ok(status) = serde_json::from_slice::<GuardStatus>(&bytes) {
        if valid_guard_status(&status) {
            return Some(status);
        }
    }
    let legacy: LegacyGuardStatus = serde_json::from_slice(&bytes).ok()?;
    if !matches!(
        legacy.cc_switch_guard.as_str(),
        "INACTIVE" | "CLEAR" | "TERMINATED" | "ERROR"
    ) || (legacy.cc_switch_guard == "TERMINATED" && legacy.cc_switch_guard_checked_at == 0)
    {
        return None;
    }
    let legacy_termination = legacy.cc_switch_guard == "TERMINATED";
    Some(GuardStatus {
        cc_switch_guard: legacy.cc_switch_guard,
        cc_switch_guard_checked_at: legacy.cc_switch_guard_checked_at,
        cc_switch_guard_lease_id: legacy.cc_switch_guard_lease_id,
        cc_switch_termination_epoch: if legacy_termination {
            // Shared with the desktop's legacy-status fallback. Importing an
            // already observed termination must not create another notice.
            uuid::Uuid::from_u128(u128::from(legacy.cc_switch_guard_checked_at))
        } else {
            uuid::Uuid::new_v4()
        },
        cc_switch_termination_count: u64::from(legacy_termination),
        cc_switch_last_terminated_at: legacy_termination
            .then_some(legacy.cc_switch_guard_checked_at),
    })
}

fn valid_guard_status(status: &GuardStatus) -> bool {
    matches!(
        status.cc_switch_guard.as_str(),
        "INACTIVE" | "CLEAR" | "TERMINATED" | "ERROR"
    ) && !status.cc_switch_termination_epoch.is_nil()
        && !(status.cc_switch_termination_count == 0
            && status.cc_switch_last_terminated_at.is_some())
        && !(status.cc_switch_termination_count > 0
            && status
                .cc_switch_last_terminated_at
                .is_none_or(|at| at == 0 || at > status.cc_switch_guard_checked_at))
}

fn check_guard(config: &MeterConfig) -> GuardSnapshot {
    #[cfg(windows)]
    {
        let check = ccswitch_guard::check(config);
        let mut snapshot = GuardSnapshot::new(
            check.state.as_str(),
            check.checked_at,
            check.lease_id,
            check.error_code(),
            check.detected,
        );
        snapshot.confirmed_termination_count = check.confirmed_termination_count;
        snapshot.confirmed_last_terminated_at = check.last_terminated_at;
        snapshot
    }
    #[cfg(not(windows))]
    {
        let _ = config;
        GuardSnapshot::new("INACTIVE", epoch_seconds(), None, None, false)
    }
}

#[derive(Default)]
struct GuardAuditState {
    last_state: Option<String>,
    last_lease_id: Option<uuid::Uuid>,
}

impl GuardAuditState {
    fn record(&mut self, audit: &AuditLog, guard: &GuardSnapshot) -> Result<(), String> {
        let changed =
            self.last_state.as_deref() != Some(guard.state) || self.last_lease_id != guard.lease_id;
        if guard.detected {
            audit.append_guard(AuditKind::GuardDetected, guard.state)?;
        }
        if (changed && (guard.state != "INACTIVE" || self.last_state.is_some()))
            || guard.state == "TERMINATED"
        {
            let kind = match guard.state {
                "CLEAR" => AuditKind::GuardActive,
                "TERMINATED" => AuditKind::GuardTerminated,
                "ERROR" => AuditKind::GuardError,
                _ => AuditKind::GuardInactive,
            };
            audit.append_guard(kind, guard.state)?;
        }
        self.last_state = Some(guard.state.into());
        self.last_lease_id = guard.lease_id;
        Ok(())
    }
}

type GuardChecker = dyn Fn(&MeterConfig) -> GuardSnapshot + Send + Sync;

struct GuardSupervisor {
    snapshot: Arc<Mutex<GuardSnapshot>>,
    stop: Arc<(Mutex<bool>, Condvar)>,
    worker: Mutex<Option<JoinHandle<()>>>,
}

impl GuardSupervisor {
    fn start(
        config: &MeterConfig,
        audit: &AuditLog,
        checker: Arc<GuardChecker>,
        interval: Duration,
    ) -> Result<Self, String> {
        let status_path = guard_status_path(config)?;
        let mut audit_state = GuardAuditState::default();
        let first_check = checker(config);
        let mut termination_ledger =
            GuardTerminationLedger::recover(first_check.lease_id, read_guard_status(&status_path));
        let initial = publish_guard_snapshot(
            termination_ledger.apply(first_check),
            audit,
            &mut audit_state,
            &status_path,
        );
        let snapshot = Arc::new(Mutex::new(initial));
        let stop = Arc::new((Mutex::new(false), Condvar::new()));
        let thread_snapshot = Arc::clone(&snapshot);
        let thread_stop = Arc::clone(&stop);
        let thread_config = config.clone();
        let thread_audit = audit.clone();
        let thread_checker = checker;
        let thread_status_path = status_path;
        let worker = thread::Builder::new()
            .name("quota-guard-ccswitch-check".into())
            .spawn(move || loop {
                let (stop_lock, wake) = &*thread_stop;
                let stopped = stop_lock
                    .lock()
                    .unwrap_or_else(|poisoned| poisoned.into_inner());
                let (stopped, _) = wake
                    .wait_timeout_while(stopped, interval, |stopped| !*stopped)
                    .unwrap_or_else(|poisoned| poisoned.into_inner());
                if *stopped {
                    break;
                }
                drop(stopped);

                let checked = thread_checker(&thread_config);
                let Ok(mut current) = thread_snapshot.lock() else {
                    break;
                };
                let next = publish_guard_snapshot(
                    termination_ledger.apply(checked),
                    &thread_audit,
                    &mut audit_state,
                    &thread_status_path,
                );
                *current = next;
            });
        let worker = match worker {
            Ok(worker) => Some(worker),
            Err(_) => {
                let mut current = snapshot
                    .lock()
                    .unwrap_or_else(|poisoned| poisoned.into_inner());
                current.state = "ERROR";
                current.error_code = Some("METER_GUARD_THREAD_UNAVAILABLE".into());
                None
            }
        };
        Ok(Self {
            snapshot,
            stop,
            worker: Mutex::new(worker),
        })
    }

    fn snapshot(&self) -> GuardSnapshot {
        let mut snapshot = self
            .snapshot
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .clone();
        if let Some(code) = self.snapshot_error(&snapshot) {
            snapshot.state = "ERROR";
            snapshot.error_code = Some(code);
            snapshot.checked_at = epoch_seconds();
        }
        snapshot
    }

    fn snapshot_error(&self, snapshot: &GuardSnapshot) -> Option<String> {
        if let Some(code) = &snapshot.error_code {
            return Some(code.clone());
        }
        let worker_finished = self
            .worker
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .as_ref()
            .is_some_and(JoinHandle::is_finished);
        let stopping = *self
            .stop
            .0
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        if worker_finished && !stopping {
            return Some("METER_GUARD_CHECK_UNAVAILABLE".into());
        }
        let stale = snapshot.checked_at == 0
            || epoch_seconds().saturating_sub(snapshot.checked_at) > GUARD_STATUS_FRESH.as_secs()
            || snapshot.checked_at > epoch_seconds().saturating_add(2);
        if stale {
            return Some("METER_GUARD_CHECK_STALE".into());
        }
        None
    }

    fn ensure_allowed(&self) -> Result<(), String> {
        self.snapshot().error_code.map_or(Ok(()), Err)
    }

    fn with_allowed<R>(&self, action: impl FnOnce() -> Result<R, String>) -> Result<R, String> {
        let snapshot = self
            .snapshot
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        if let Some(code) = self.snapshot_error(&snapshot) {
            return Err(code);
        }
        action()
    }

    fn stop(&self) {
        let mut stopped = self
            .stop
            .0
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        *stopped = true;
        self.stop.1.notify_all();
        drop(stopped);
        if let Some(worker) = self
            .worker
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .take()
        {
            let _ = worker.join();
        }
    }
}

impl Drop for GuardSupervisor {
    fn drop(&mut self) {
        self.stop();
    }
}

fn publish_guard_snapshot(
    guard: GuardSnapshot,
    audit: &AuditLog,
    audit_state: &mut GuardAuditState,
    status_path: &Path,
) -> GuardSnapshot {
    let _ = audit_state.record(audit, &guard);
    if write_guard_status(status_path, &guard).is_err() {
        // An older CLEAR record must not remain readable after publication fails.
        let _ = fs::remove_file(status_path);
    }
    guard
}

struct CaptureSupervisor {
    stop: Arc<(Mutex<bool>, Condvar)>,
    failure: Arc<Mutex<Option<&'static str>>>,
    worker: Mutex<Option<JoinHandle<()>>>,
}

impl CaptureSupervisor {
    fn start(
        interval: Duration,
        cycle: impl Fn() -> Result<(), String> + Send + Sync + 'static,
    ) -> Result<Self, String> {
        let stop = Arc::new((Mutex::new(false), Condvar::new()));
        let failure = Arc::new(Mutex::new(None));
        let thread_stop = Arc::clone(&stop);
        let thread_failure = Arc::clone(&failure);
        let cycle = Arc::new(cycle);
        let worker = thread::Builder::new()
            .name("quota-guard-meter-capture".into())
            .spawn(move || {
                let outcome = catch_unwind(AssertUnwindSafe(|| loop {
                    if capture_is_stopping(&thread_stop) {
                        break;
                    }
                    let _ = cycle();
                    if wait_for_capture(&thread_stop, interval) {
                        break;
                    }
                }));
                if !capture_is_stopping(&thread_stop) {
                    let code = if outcome.is_err() {
                        "METER_CAPTURE_THREAD_PANICKED"
                    } else {
                        "METER_CAPTURE_THREAD_STOPPED"
                    };
                    *thread_failure
                        .lock()
                        .unwrap_or_else(|poisoned| poisoned.into_inner()) = Some(code);
                }
            })
            .map_err(|_| "METER_CAPTURE_THREAD_UNAVAILABLE")?;
        Ok(Self {
            stop,
            failure,
            worker: Mutex::new(Some(worker)),
        })
    }

    fn fatal_error(&self) -> Option<&'static str> {
        if let Some(code) = *self
            .failure
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
        {
            return Some(code);
        }
        let finished = self
            .worker
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .as_ref()
            .is_some_and(JoinHandle::is_finished);
        (finished && !capture_is_stopping(&self.stop)).then_some("METER_CAPTURE_THREAD_STOPPED")
    }

    fn stop(&self) {
        let mut stopped = self
            .stop
            .0
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        *stopped = true;
        self.stop.1.notify_all();
        drop(stopped);
        if let Some(worker) = self
            .worker
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .take()
        {
            let _ = worker.join();
        }
    }
}

impl Drop for CaptureSupervisor {
    fn drop(&mut self) {
        self.stop();
    }
}

fn capture_is_stopping(stop: &Arc<(Mutex<bool>, Condvar)>) -> bool {
    *stop
        .0
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner())
}

fn wait_for_capture(stop: &Arc<(Mutex<bool>, Condvar)>, interval: Duration) -> bool {
    let stopped = stop
        .0
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner());
    let (stopped, _) = stop
        .1
        .wait_timeout_while(stopped, interval, |stopped| !*stopped)
        .unwrap_or_else(|poisoned| poisoned.into_inner());
    *stopped
}

fn capture_local_events(
    config: &MeterConfig,
    journal_lock: &Mutex<()>,
    status_lock: &Mutex<()>,
    last_capture_epoch: &AtomicU64,
) -> Result<(), String> {
    let (previous, credential) = {
        let _journal_guard = journal_lock
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        (
            load_state(&config.state_dir.join("scan-state.json"))?,
            load_credential(&config.state_dir)?,
        )
    };
    let (Some(previous), Some(credential)) = (previous, credential) else {
        return Ok(());
    };
    if credential.device_id != config.device_id {
        return Err("METER_CREDENTIAL_INVALID".into());
    }
    if !credential.owner_verified || credential.user_id.is_none() {
        return Ok(());
    }
    let outbox = outbox::Outbox::capture_with_short_lock(
        config,
        &previous,
        credential.user_id,
        journal_lock,
    )?;
    let pending = outbox.pending_count();
    last_capture_epoch.store(epoch_seconds(), Ordering::Relaxed);
    publish_capture_progress(config, pending, status_lock);
    Ok(())
}

fn publish_capture_progress(config: &MeterConfig, pending_events: usize, status_lock: &Mutex<()>) {
    let _status_guard = status_lock
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner());
    let mut status = read_public_status(&config.status_file).unwrap_or_else(|| MeterStatus {
        state: "UP_TO_DATE".into(),
        version: env!("CARGO_PKG_VERSION").into(),
        last_scan_epoch_seconds: 0,
        last_report_epoch_seconds: None,
        reported_events: 0,
        pending_events: None,
        deferred_events: 0,
            review_submitted_events: 0,
            review_error_code: None,
        warning_code: None,
        error_code: None,
        cc_switch_guard: "INACTIVE".into(),
        cc_switch_guard_checked_at: 0,
        cc_switch_guard_lease_id: None,
    });
    status.last_scan_epoch_seconds = epoch_seconds();
    status.pending_events = Some(pending_events);
    let _ = write_public_status(&config.status_file, &status);
}

fn ensure_current_owner(config: &MeterConfig, expected_user_id: uuid::Uuid) -> Result<(), String> {
    let Some(current) = load_credential(&config.state_dir)? else {
        return Err("METER_OUTBOX_OWNER_CHANGED".into());
    };
    if current.device_id != config.device_id
        || !current.owner_verified
        || current.user_id != Some(expected_user_id)
    {
        return Err("METER_OUTBOX_OWNER_CHANGED".into());
    }
    Ok(())
}

pub struct MeterRuntime {
    last_speed_review: Option<Instant>,
    last_review_upload: Option<(Instant, bool)>,
    review_submitted_events: usize,
    review_error_code: Option<String>,
    config: MeterConfig,
    client: reqwest::blocking::Client,
    audit: AuditLog,
    last_logged_state: Option<String>,
    last_logged_error: Option<String>,
    last_logged_epoch_seconds: Option<u64>,
    last_heartbeat: Option<Instant>,
    last_report_epoch_seconds: Option<u64>,
    pending_events: Option<usize>,
    deferred_events: usize,
    warning_code: Option<String>,
    guard: GuardSupervisor,
    capture: CaptureSupervisor,
    journal_lock: Arc<Mutex<()>>,
    status_lock: Arc<Mutex<()>>,
    last_capture_epoch: Arc<AtomicU64>,
    last_public_status: Option<MeterStatus>,
}

impl MeterRuntime {
    pub fn new(config: MeterConfig) -> Result<Self, String> {
        Self::new_with_guard_checker(config, Arc::new(check_guard), GUARD_POLL_INTERVAL)
    }

    fn new_with_guard_checker(
        config: MeterConfig,
        checker: Arc<GuardChecker>,
        guard_interval: Duration,
    ) -> Result<Self, String> {
        Self::new_with_capture_interval(config, checker, guard_interval, CAPTURE_INTERVAL)
    }

    fn new_with_capture_interval(
        config: MeterConfig,
        checker: Arc<GuardChecker>,
        guard_interval: Duration,
        capture_interval: Duration,
    ) -> Result<Self, String> {
        config.validate()?;
        let client = reqwest::blocking::Client::builder()
            .no_proxy()
            .connect_timeout(Duration::from_secs(5))
            .timeout(Duration::from_secs(10))
            .build()
            .map_err(|_| "METER_HTTP_UNAVAILABLE")?;
        let audit = match config.log_dir.as_deref() {
            Some(directory) => AuditLog::in_directory(directory)?,
            None => AuditLog::new(&config.state_dir)?,
        };
        let public_log_path = config
            .status_file
            .parent()
            .ok_or("METER_STATUS_PATH_INVALID")?
            .join("meter-runtime-logs.jsonl");
        let audit = audit.with_public_summary_path(public_log_path);
        let _ = audit.append(AuditKind::ServiceStarted, None, 0, false);
        let guard = GuardSupervisor::start(&config, &audit, checker, guard_interval)?;
        let journal_lock = Arc::new(Mutex::new(()));
        let status_lock = Arc::new(Mutex::new(()));
        let last_capture_epoch = Arc::new(AtomicU64::new(0));
        let capture_config = config.clone();
        let capture_lock = Arc::clone(&journal_lock);
        let capture_status_lock = Arc::clone(&status_lock);
        let capture_epoch = Arc::clone(&last_capture_epoch);
        let capture = CaptureSupervisor::start(capture_interval, move || {
            capture_local_events(
                &capture_config,
                &capture_lock,
                &capture_status_lock,
                &capture_epoch,
            )
        })?;
        Ok(Self {
            last_speed_review: None,
            last_review_upload: None,
            config,
            client,
            audit,
            last_logged_state: None,
            last_logged_error: None,
            last_logged_epoch_seconds: None,
            last_heartbeat: None,
            last_report_epoch_seconds: None,
            pending_events: None,
            deferred_events: 0,
            review_submitted_events: 0,
            review_error_code: None,
            warning_code: None,
            guard,
            capture,
            journal_lock,
            status_lock,
            last_capture_epoch,
            last_public_status: None,
        })
    }

    #[cfg(test)]
    fn new_for_test(config: MeterConfig) -> Result<Self, String> {
        Self::new_with_guard_checker(
            config,
            Arc::new(|_: &MeterConfig| GuardSnapshot::inactive()),
            GUARD_POLL_INTERVAL,
        )
    }

    pub fn tick(&mut self) -> MeterStatus {
        let now = epoch_seconds();
        // The independent supervisor publishes every guard check. Keep this
        // tick's public status compatible while never delaying checks for IO.
        let guard = self.guard.snapshot();
        let _ = self.publish_guard_status(&guard);
        let outcome = if let Some(code) = self.capture.fatal_error() {
            Err(code.into())
        } else {
            self.tick_inner()
        };
        let mut status = match outcome {
            Ok((state, reported_events)) => MeterStatus {
                state: state.into(),
                version: env!("CARGO_PKG_VERSION").into(),
                last_scan_epoch_seconds: now,
                last_report_epoch_seconds: self.last_report_epoch_seconds,
                reported_events,
                pending_events: self.pending_events,
                deferred_events: self.deferred_events,
            review_submitted_events: self.review_submitted_events,
            review_error_code: self.review_error_code.clone(),
                warning_code: self.warning_code.clone(),
                error_code: None,
                cc_switch_guard: guard.state.into(),
                cc_switch_guard_checked_at: guard.checked_at,
                cc_switch_guard_lease_id: guard.lease_id,
            },
            Err(code) => MeterStatus {
                state: "ERROR".into(),
                version: env!("CARGO_PKG_VERSION").into(),
                last_scan_epoch_seconds: now,
                last_report_epoch_seconds: self.last_report_epoch_seconds,
                reported_events: 0,
                pending_events: self.pending_events,
                deferred_events: self.deferred_events,
            review_submitted_events: self.review_submitted_events,
            review_error_code: self.review_error_code.clone(),
                warning_code: self.warning_code.clone(),
                error_code: Some(code),
                cc_switch_guard: guard.state.into(),
                cc_switch_guard_checked_at: guard.checked_at,
                cc_switch_guard_lease_id: guard.lease_id,
            },
        };
        status.last_scan_epoch_seconds = status
            .last_scan_epoch_seconds
            .max(self.last_capture_epoch.load(Ordering::Relaxed));
        let latest_guard = self.guard.snapshot();
        status.cc_switch_guard = latest_guard.state.into();
        status.cc_switch_guard_checked_at = latest_guard.checked_at;
        status.cc_switch_guard_lease_id = latest_guard.lease_id;
        if status.state != "ERROR" {
            if let Some(code) = latest_guard.error_code {
                status.state = "ERROR".into();
                status.error_code = Some(code);
                status.reported_events = 0;
            }
        }
        if write_public_status_monotonic(&self.config.status_file, &status, &self.status_lock)
            .is_ok()
        {
            self.last_public_status = Some(status.clone());
        }
        let kind = audit_kind_for_status(
            &status,
            self.last_logged_state.as_deref(),
            self.last_logged_error.as_deref(),
            self.last_logged_epoch_seconds,
            now,
        );
        if let Some(kind) = kind {
            let _ = self.audit.append(kind, Some(&status), 0, false);
            self.last_logged_state = Some(status.state.clone());
            self.last_logged_error = status.error_code.clone().or(status.warning_code.clone());
            self.last_logged_epoch_seconds = Some(now);
        }
        status
    }

    pub fn shutdown(&self) -> Result<(), String> {
        self.capture.stop();
        self.guard.stop();
        let _ = self.audit.append(AuditKind::ServiceStopped, None, 0, false);
        Ok(())
    }

    pub fn fatal_error(&self) -> Option<&'static str> {
        self.capture.fatal_error()
    }

    // Recheck between bounded HTTP operations so a failed collector cannot
    // remain hidden behind a large upload backlog before SCM recovers it.
    fn ensure_operational(&self) -> Result<(), String> {
        if let Some(code) = self.capture.fatal_error() {
            return Err(code.into());
        }
        self.guard.ensure_allowed()
    }

    fn record_capture_progress(&self, outbox: &outbox::Outbox) {
        self.last_capture_epoch
            .store(epoch_seconds(), Ordering::Relaxed);
        publish_capture_progress(&self.config, outbox.pending_count(), &self.status_lock);
    }

    fn publish_guard_status(&mut self, guard: &GuardSnapshot) -> Result<(), String> {
        let mut status = self.last_public_status.clone().unwrap_or(MeterStatus {
            state: "ERROR".into(),
            version: env!("CARGO_PKG_VERSION").into(),
            last_scan_epoch_seconds: 0,
            last_report_epoch_seconds: None,
            reported_events: 0,
            pending_events: self.pending_events,
            deferred_events: self.deferred_events,
            review_submitted_events: self.review_submitted_events,
            review_error_code: self.review_error_code.clone(),
            warning_code: self.warning_code.clone(),
            error_code: None,
            cc_switch_guard: "INACTIVE".into(),
            cc_switch_guard_checked_at: 0,
            cc_switch_guard_lease_id: None,
        });
        status.cc_switch_guard = guard.state.into();
        status.cc_switch_guard_checked_at = guard.checked_at;
        status.cc_switch_guard_lease_id = guard.lease_id;
        if let Some(code) = &guard.error_code {
            status.state = "ERROR".into();
            status.error_code = Some(code.clone());
        }
        write_public_status_monotonic(&self.config.status_file, &status, &self.status_lock)
    }

    fn tick_inner(&mut self) -> Result<(&'static str, usize), String> {
        self.deferred_events = 0;
        self.warning_code = None;
        let state_path = self.config.state_dir.join("scan-state.json");
        let (previous, credential) = {
            let _journal_guard = self
                .journal_lock
                .lock()
                .unwrap_or_else(|poisoned| poisoned.into_inner());
            let first_pairing = load_credential(&self.config.state_dir)?.is_none()
                && self.config.pairing_inbox.exists();
            let fresh_baseline = first_pairing
                .then(|| baseline_codex_home(&self.config.codex_home))
                .transpose()?;
            let paired = consume_pairing(&self.config)?;
            if paired && first_pairing {
                let baseline = fresh_baseline.ok_or("METER_STATE_INVALID")?;
                save_state(&state_path, &baseline)?;
                return Ok(("BASELINE_READY", 0));
            }
            let previous = load_state(&state_path)?;
            if previous.is_none() {
                // Existing official activity has unknown ownership until this OS
                // profile is paired. Establish a byte cursor without charging it.
                let baseline = match fresh_baseline {
                    Some(baseline) => baseline,
                    None => baseline_codex_home(&self.config.codex_home)?,
                };
                save_state(&state_path, &baseline)?;
                return Ok(("BASELINE_READY", 0));
            }
            let credential = load_credential(&self.config.state_dir)?;
            (previous.ok_or("METER_STATE_INVALID")?, credential)
        };
        let Some(mut credential) = credential else {
            return Ok(("UNPAIRED", 0));
        };
        if credential.device_id != self.config.device_id {
            return Err("METER_CREDENTIAL_INVALID".into());
        }
        // Capture before any network IO so source log rotation and service
        // restarts cannot erase unacknowledged usage. The journal is bound to
        // the paired device, origin and Windows profile's Codex directory.
        let mut outbox = outbox::Outbox::capture_with_short_lock(
            &self.config,
            &previous,
            credential.user_id,
            &self.journal_lock,
        )?;
        self.pending_events = Some(outbox.pending_count());
        self.record_capture_progress(&outbox);
        self.review_submitted_events = outbox.review_submitted_events();
        if !credential.owner_verified || credential.user_id.is_none() {
            self.ensure_operational()?;
            let user_id = renew_device_credential(&self.client, &self.config, &credential)?;
            if credential.user_id.is_some_and(|claimed| claimed != user_id) {
                return Err("METER_PAIRING_OWNER_MISMATCH".into());
            }
            credential.user_id = Some(user_id);
            credential.owner_verified = true;
            save_credential(&self.config.state_dir, &credential)?;
            outbox = outbox::Outbox::capture_with_short_lock(
                &self.config,
                &previous,
                credential.user_id,
                &self.journal_lock,
            )?;
            self.pending_events = Some(outbox.pending_count());
            self.record_capture_progress(&outbox);
        }
        // The guard is fail-closed for actions and network requests. A stale,
        // unknown or failed desktop lease must not stop local collection.
        self.ensure_operational()?;
        let now = Utc::now();
        let candidates: Vec<_> = outbox
            .pending
            .iter()
            .filter(|event| reportable_at(event, now))
            .cloned()
            .collect();
        let mut reported_events = Vec::new();
        for batch in candidates.chunks(100) {
            self.ensure_operational()?;
            match send_events_guarded(&self.client, &self.config, &credential, batch, || {
                self.ensure_operational()
            }) {
                Ok(()) => {
                    reported_events.extend_from_slice(batch);
                    let owner_id = credential.user_id.ok_or("METER_CREDENTIAL_INVALID")?;
                    let latest = {
                        let _journal_guard = self
                            .journal_lock
                            .lock()
                            .unwrap_or_else(|poisoned| poisoned.into_inner());
                        ensure_current_owner(&self.config, owner_id)?;
                        self.guard.with_allowed(|| {
                            outbox::Outbox::acknowledge_latest(&self.config, owner_id, batch)
                        })?
                    };
                    outbox = latest;
                }
                Err(code) if code == "METER_REPORT_REJECTED" => {
                    // A malformed event must not hold later valid events
                    // hostage. Isolate rejected records with their owner.
                    for event in batch {
                        let one = std::slice::from_ref(event);
                        let result = if batch.len() == 1 {
                            Err(code.clone())
                        } else {
                            send_events_guarded(
                                &self.client,
                                &self.config,
                                &credential,
                                one,
                                || self.ensure_operational(),
                            )
                        };
                        match result {
                            Ok(()) => {
                                let owner_id =
                                    credential.user_id.ok_or("METER_CREDENTIAL_INVALID")?;
                                let latest = {
                                    let _journal_guard = self
                                        .journal_lock
                                        .lock()
                                        .unwrap_or_else(|poisoned| poisoned.into_inner());
                                    ensure_current_owner(&self.config, owner_id)?;
                                    self.guard.with_allowed(|| {
                                        outbox::Outbox::acknowledge_latest(
                                            &self.config,
                                            owner_id,
                                            one,
                                        )
                                    })?
                                };
                                outbox = latest;
                                reported_events.push(event.clone());
                            }
                            Err(code) if code == "METER_REPORT_REJECTED" => {
                                let owner_id =
                                    credential.user_id.ok_or("METER_CREDENTIAL_INVALID")?;
                                let latest = {
                                    let _journal_guard = self
                                        .journal_lock
                                        .lock()
                                        .unwrap_or_else(|poisoned| poisoned.into_inner());
                                    ensure_current_owner(&self.config, owner_id)?;
                                    self.guard.with_allowed(|| {
                                        outbox::Outbox::quarantine_rejected_latest(
                                            &self.config,
                                            owner_id,
                                            one,
                                        )
                                    })?
                                };
                                outbox = latest;
                            }
                            Err(code) => return Err(code),
                        }
                    }
                }
                Err(code) => return Err(code),
            }
            self.pending_events = Some(outbox.pending_count());
            if !reported_events.is_empty() {
                self.last_report_epoch_seconds = Some(epoch_seconds());
            }
        }
        outbox = outbox::Outbox::capture_with_short_lock(
            &self.config,
            &previous,
            credential.user_id,
            &self.journal_lock,
        )?;
        {
            let _journal_guard = self
                .journal_lock
                .lock()
                .unwrap_or_else(|poisoned| poisoned.into_inner());
            ensure_current_owner(
                &self.config,
                credential.user_id.ok_or("METER_CREDENTIAL_INVALID")?,
            )?;
            save_state(&state_path, outbox.scan_state())?;
        }
        self.pending_events = Some(outbox.pending_count());
        self.record_capture_progress(&outbox);
        let now = Utc::now();
        self.deferred_events = outbox.pending.iter().filter(|event| !reportable_at(event, now)).count();
        self.warning_code = (self.deferred_events > 0)
            .then(|| "METER_EVENT_OUTSIDE_REPORT_WINDOW".to_owned());
        if self.last_speed_review.is_none_or(|last| last.elapsed() >= Duration::from_secs(60)) {
            self.last_speed_review = Some(Instant::now());
            let corrections = speed_mode::recent_corrections(&self.config.codex_home);
            for batch in corrections.chunks(100) {
                if self.ensure_operational().is_err() { break; }
                let owner = credential.user_id.ok_or("METER_CREDENTIAL_INVALID")?;
                if ensure_current_owner(&self.config, owner).is_err() { break; }
                // Optional on older servers; failures never block usage capture/upload.
                let _ = self.client.post(meter_endpoint(&self.config, "/api/v1/meter/usage/modes")?)
                    .header("X-Quota-Guard-Meter", &credential.device_token)
                    .timeout(Duration::from_secs(3))
                    .json(&serde_json::json!({"events":batch})).send();
            }
        }
        // Keep historical/future events with their original IDs, timestamps and
        // owner. Their review warning is separate from collector/network errors;
        // valid usage and device-only heartbeats continue without the desktop.
        let warning = outbox.owner_warning();
        let reported = ScanOutcome {
            events: reported_events,
            next_state: outbox.scan_state().clone(),
        };
        if !reported.events.is_empty() {
            self.last_report_epoch_seconds = Some(epoch_seconds());
            let guard = self.guard.snapshot();
            let tokens = reported
                .events
                .iter()
                .fold(0u64, |sum, event| sum.saturating_add(event.total_tokens));
            let status = MeterStatus {
                state: "REPORTED".into(),
                version: env!("CARGO_PKG_VERSION").into(),
                last_scan_epoch_seconds: epoch_seconds(),
                last_report_epoch_seconds: self.last_report_epoch_seconds,
                reported_events: reported.events.len(),
                pending_events: self.pending_events,
                deferred_events: self.deferred_events,
            review_submitted_events: self.review_submitted_events,
            review_error_code: self.review_error_code.clone(),
                warning_code: self.warning_code.clone(),
                error_code: None,
                cc_switch_guard: guard.state.into(),
                cc_switch_guard_checked_at: guard.checked_at,
                cc_switch_guard_lease_id: guard.lease_id,
            };
            let _ = self
                .audit
                .append(AuditKind::UsageReported, Some(&status), tokens, false);
        }
        if self
            .last_heartbeat
            .is_none_or(|last| last.elapsed() >= Duration::from_secs(30))
        {
            self.ensure_operational()?;
            send_heartbeat_guarded(&self.client, &self.config, &credential, || {
                self.ensure_operational()
            })?;
            self.last_heartbeat = Some(Instant::now());
            let guard = self.guard.snapshot();
            let status = MeterStatus {
                state: if reported.events.is_empty() {
                    "UP_TO_DATE"
                } else {
                    "REPORTED"
                }
                .into(),
                version: env!("CARGO_PKG_VERSION").into(),
                last_scan_epoch_seconds: epoch_seconds(),
                last_report_epoch_seconds: self.last_report_epoch_seconds,
                reported_events: 0,
                pending_events: self.pending_events,
                deferred_events: self.deferred_events,
            review_submitted_events: self.review_submitted_events,
            review_error_code: self.review_error_code.clone(),
                warning_code: self.warning_code.clone(),
                error_code: None,
                cc_switch_guard: guard.state.into(),
                cc_switch_guard_checked_at: guard.checked_at,
                cc_switch_guard_lease_id: guard.lease_id,
            };
            let _ = self
                .audit
                .append(AuditKind::Heartbeat, Some(&status), 0, true);
        }
        if let Some(code) = warning {
            return Err(code.into());
        }
        // Valid real-time use and heartbeats always run first. Review uploads
        // are bounded, optional on older servers, and cannot suppress billing.
        self.upload_deferred_review(&credential, &mut outbox)?;
        Ok((
            if reported.events.is_empty() {
                "UP_TO_DATE"
            } else {
                "REPORTED"
            },
            reported.events.len(),
        ))
    }

    fn upload_deferred_review(&mut self, credential: &MeterCredential, outbox: &mut outbox::Outbox) -> Result<(), String> {
        if self.deferred_events == 0 { return Ok(()); }
        if self.last_review_upload.is_some_and(|(last, success)| last.elapsed() < Duration::from_secs(if success { 10 } else { 60 })) {
            return Ok(());
        }
        let now = Utc::now();
        let candidates: Vec<_> = outbox.pending.iter().filter(|event| !reportable_at(event, now)).take(500).cloned().collect();
        self.last_review_upload = Some((Instant::now(), false));
        let owner = credential.user_id.ok_or("METER_CREDENTIAL_INVALID")?;
        for batch in candidates.chunks(100) {
            self.ensure_operational()?;
            ensure_current_owner(&self.config, owner)?;
            match review_upload::send(&self.client, &self.config, credential, batch, || self.ensure_operational()) {
                Ok(()) => {
                    let current = {
                        let _guard = self.journal_lock.lock().unwrap_or_else(|p| p.into_inner());
                        ensure_current_owner(&self.config, owner)?;
                        self.guard.with_allowed(|| outbox::Outbox::acknowledge_review_latest(&self.config, owner, batch))?
                    };
                    *outbox = current;
                    self.review_submitted_events = outbox.review_submitted_events();
                    self.review_error_code = None;
                    self.pending_events = Some(outbox.pending_count());
                    self.deferred_events = outbox.pending.iter().filter(|event| !reportable_at(event, now)).count();
                    self.warning_code = (self.deferred_events > 0).then(|| "METER_EVENT_OUTSIDE_REPORT_WINDOW".into());
                }
                Err(code) => {
                    self.review_error_code = Some(code);
                    return Ok(());
                }
            }
        }
        self.last_review_upload = Some((Instant::now(), true));
        Ok(())
    }
}

fn audit_kind_for_status(
    status: &MeterStatus,
    last_state: Option<&str>,
    last_error: Option<&str>,
    last_logged_at: Option<u64>,
    now: u64,
) -> Option<AuditKind> {
    let changed =
        last_state != Some(status.state.as_str())
            || last_error != status.error_code.as_deref().or(status.warning_code.as_deref());
    if status.state == "ERROR" {
        return (changed
            || last_logged_at.is_none_or(|previous| now.saturating_sub(previous) >= 300))
        .then_some(AuditKind::Error);
    }
    if status.reported_events > 0 {
        // The accepted batch was logged before the heartbeat request.
        return None;
    }
    if changed {
        return Some(match status.state.as_str() {
            "BASELINE_READY" => AuditKind::BaselineReady,
            "UNPAIRED" => AuditKind::Unpaired,
            _ => AuditKind::StatusChange,
        });
    }
    None
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

fn read_public_status(path: &Path) -> Option<MeterStatus> {
    let metadata = fs::symlink_metadata(path).ok()?;
    if !metadata.file_type().is_file() || metadata.len() > 16 * 1024 {
        return None;
    }
    let bytes = fs::read(path).ok()?;
    (bytes.len() <= 16 * 1024)
        .then(|| serde_json::from_slice(&bytes).ok())
        .flatten()
}

fn write_public_status_monotonic(
    path: &Path,
    status: &MeterStatus,
    status_lock: &Mutex<()>,
) -> Result<(), String> {
    let _status_guard = status_lock
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner());
    let mut merged = status.clone();
    if let Some(existing) = read_public_status(path) {
        if existing.last_scan_epoch_seconds > merged.last_scan_epoch_seconds {
            merged.last_scan_epoch_seconds = existing.last_scan_epoch_seconds;
            merged.pending_events = existing.pending_events;
        }
        if existing.last_report_epoch_seconds > merged.last_report_epoch_seconds {
            merged.last_report_epoch_seconds = existing.last_report_epoch_seconds;
        }
    }
    write_public_status(path, &merged)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;
    use std::io::Read;
    use std::net::TcpListener;
    use std::path::{Path, PathBuf};
    use std::sync::LazyLock;

    static TEST_TIMESTAMP: LazyLock<String> = LazyLock::new(|| Utc::now().to_rfc3339());

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
        record_at(response_id, tokens, &TEST_TIMESTAMP)
    }

    fn guard_check(
        state: &'static str,
        lease_id: Option<uuid::Uuid>,
        confirmed_count: u64,
        last_terminated_at: Option<u64>,
    ) -> GuardSnapshot {
        let mut checked = GuardSnapshot::new(
            state,
            last_terminated_at.unwrap_or(200),
            lease_id,
            None,
            false,
        );
        checked.confirmed_termination_count = confirmed_count;
        checked.confirmed_last_terminated_at = last_terminated_at;
        checked
    }

    #[test]
    fn termination_ledger_preserves_event_when_guard_clears() {
        let lease_id = uuid::Uuid::new_v4();
        let mut ledger = GuardTerminationLedger::new(Some(lease_id));
        let terminated = ledger.apply(guard_check("TERMINATED", Some(lease_id), 1, Some(101)));
        let clear = ledger.apply(guard_check("CLEAR", Some(lease_id), 0, None));

        assert_eq!(terminated.termination_count, 1);
        assert_eq!(terminated.last_terminated_at, Some(101));
        assert_eq!(clear.termination_epoch, terminated.termination_epoch);
        assert_eq!(clear.termination_count, 1);
        assert_eq!(clear.last_terminated_at, Some(101));
    }

    #[test]
    fn termination_ledger_accumulates_each_confirmed_check() {
        let lease_id = uuid::Uuid::new_v4();
        let mut ledger = GuardTerminationLedger::new(Some(lease_id));
        let first = ledger.apply(guard_check("TERMINATED", Some(lease_id), 1, Some(101)));
        let second = ledger.apply(guard_check("TERMINATED", Some(lease_id), 1, Some(103)));

        assert_eq!(first.termination_count, 1);
        assert_eq!(second.termination_epoch, first.termination_epoch);
        assert_eq!(second.termination_count, 2);
        assert_eq!(second.last_terminated_at, Some(103));
    }

    #[test]
    fn same_lease_restart_recovers_epoch_and_count_from_status_file() {
        let root = TestDir::new();
        let path = root.path().join(GUARD_STATUS_FILE);
        let lease_id = uuid::Uuid::new_v4();
        let mut ledger = GuardTerminationLedger::new(Some(lease_id));
        let saved = ledger.apply(guard_check("TERMINATED", Some(lease_id), 2, Some(150)));
        write_guard_status(&path, &saved).unwrap();
        let persisted = read_guard_status(&path).expect("valid guard status should load");

        let mut restarted = GuardTerminationLedger::recover(Some(lease_id), Some(persisted));
        let resumed = restarted.apply(guard_check("CLEAR", Some(lease_id), 0, None));

        assert_eq!(resumed.termination_epoch, saved.termination_epoch);
        assert_eq!(resumed.termination_count, 2);
        assert_eq!(resumed.last_terminated_at, Some(150));

        write_guard_status(&path, &resumed).unwrap();
        let mut restarted_again =
            GuardTerminationLedger::recover(Some(lease_id), read_guard_status(&path));
        let after_clear_restart =
            restarted_again.apply(guard_check("CLEAR", Some(lease_id), 0, None));
        assert_eq!(
            after_clear_restart.termination_epoch,
            saved.termination_epoch
        );
        assert_eq!(after_clear_restart.termination_count, 2);
        assert_eq!(after_clear_restart.last_terminated_at, Some(150));
    }

    #[test]
    fn legacy_terminated_status_recovers_at_least_one_event_for_same_lease() {
        let root = TestDir::new();
        let path = root.path().join(GUARD_STATUS_FILE);
        let lease_id = uuid::Uuid::new_v4();
        let legacy = serde_json::json!({
            "ccSwitchGuard": "TERMINATED",
            "ccSwitchGuardCheckedAt": 123,
            "ccSwitchGuardLeaseId": lease_id,
        });
        fs::write(&path, serde_json::to_vec(&legacy).unwrap()).unwrap();

        let persisted = read_guard_status(&path).expect("legacy terminated status should load");
        assert_eq!(persisted.cc_switch_guard, "TERMINATED");
        assert_eq!(persisted.cc_switch_termination_count, 1);
        assert_eq!(
            persisted.cc_switch_termination_epoch,
            uuid::Uuid::from_u128(123)
        );
        let mut ledger = GuardTerminationLedger::recover(Some(lease_id), Some(persisted));
        let cleared = ledger.apply(guard_check("CLEAR", Some(lease_id), 0, None));

        assert_eq!(cleared.termination_count, 1);
        assert_eq!(cleared.last_terminated_at, Some(123));
    }

    #[test]
    fn changed_or_missing_lease_starts_an_empty_termination_epoch() {
        let root = TestDir::new();
        let path = root.path().join(GUARD_STATUS_FILE);
        let old_lease = uuid::Uuid::new_v4();
        let mut old_ledger = GuardTerminationLedger::new(Some(old_lease));
        let saved = old_ledger.apply(guard_check("TERMINATED", Some(old_lease), 1, Some(150)));
        write_guard_status(&path, &saved).unwrap();
        let persisted = read_guard_status(&path).unwrap();

        let new_lease = uuid::Uuid::new_v4();
        let mut new_ledger =
            GuardTerminationLedger::recover(Some(new_lease), Some(persisted.clone()));
        let new_status = new_ledger.apply(guard_check("CLEAR", Some(new_lease), 0, None));
        assert_ne!(new_status.termination_epoch, saved.termination_epoch);
        assert_eq!(new_status.termination_count, 0);
        assert_eq!(new_status.last_terminated_at, None);

        let mut no_lease_ledger = GuardTerminationLedger::recover(None, Some(persisted));
        let no_lease = no_lease_ledger.apply(guard_check("INACTIVE", None, 0, None));
        assert_ne!(no_lease.termination_epoch, saved.termination_epoch);
        assert_eq!(no_lease.termination_count, 0);
        let next_lease = uuid::Uuid::new_v4();
        let next = no_lease_ledger.apply(guard_check("CLEAR", Some(next_lease), 0, None));
        assert_eq!(next.termination_count, 0);
        assert_eq!(next.last_terminated_at, None);
    }

    #[test]
    fn guard_status_reader_rejects_corrupt_and_oversized_files() {
        let root = TestDir::new();
        let path = root.path().join(GUARD_STATUS_FILE);
        fs::write(&path, b"not json").unwrap();
        assert!(read_guard_status(&path).is_none());
        fs::write(&path, br#"{"unknownStatusField":true}"#).unwrap();
        assert!(read_guard_status(&path).is_none());
        fs::write(&path, vec![b'x'; MAX_GUARD_STATUS_BYTES + 1]).unwrap();
        assert!(read_guard_status(&path).is_none());
    }

    #[test]
    fn failed_status_write_retains_count_for_retry_without_double_counting() {
        let root = TestDir::new();
        let audit = AuditLog::new(&root.path().join("state")).unwrap();
        let lease_id = uuid::Uuid::new_v4();
        let mut ledger = GuardTerminationLedger::new(Some(lease_id));
        let terminated = ledger.apply(guard_check("TERMINATED", Some(lease_id), 1, Some(150)));
        let blocked_path = root.path().join(GUARD_STATUS_FILE);
        fs::create_dir(&blocked_path).unwrap();
        let mut audit_state = GuardAuditState::default();

        let failed =
            publish_guard_snapshot(terminated.clone(), &audit, &mut audit_state, &blocked_path);
        assert_eq!(failed.state, "TERMINATED");
        assert_eq!(failed.termination_count, 1);

        fs::remove_dir(&blocked_path).unwrap();
        let clear = ledger.apply(guard_check("CLEAR", Some(lease_id), 0, None));
        let written = publish_guard_snapshot(clear, &audit, &mut audit_state, &blocked_path);
        let persisted = read_guard_status(&blocked_path).expect("retry should write status");
        assert_eq!(written.termination_epoch, terminated.termination_epoch);
        assert_eq!(
            persisted.cc_switch_termination_epoch,
            terminated.termination_epoch
        );
        assert_eq!(persisted.cc_switch_termination_count, 1);
        assert_eq!(persisted.cc_switch_last_terminated_at, Some(150));
    }

    #[test]
    fn metadata_is_turn_scoped_persistent_optional_and_does_not_change_event_identity() {
        let root = TestDir::new();
        let home = root.path().join(".codex");
        fs::create_dir_all(home.join("sessions")).unwrap();
        let file = home.join("sessions/run.jsonl");
        fs::write(&file, concat!(
            "{\"type\":\"turn_context\",\"payload\":{\"turn_id\":\"a\",\"model\":\"gpt-6.1-sol\",\"effort\":\"max\"}}\n",
            "{\"type\":\"turn_context\",\"payload\":{\"turn_id\":\"b\",\"model\":\"gpt-6-sol\",\"effort\":\"high\"}}\n")).unwrap();
        let first = scan(&home, &ScanState::default()).unwrap();
        let restored: ScanState =
            serde_json::from_slice(&serde_json::to_vec(&first.next_state).unwrap()).unwrap();
        let usage = record("metadata-response", 15)
            .replace("\"response_id\":", "\"turn_id\":\"a\",\"response_id\":");
        fs::OpenOptions::new()
            .append(true)
            .open(&file)
            .unwrap()
            .write_all(usage.as_bytes())
            .unwrap();
        let event = &scan(&home, &restored).unwrap().events[0];
        assert_eq!(event.model.as_deref(), Some("gpt-6.1-sol"));
        assert_eq!(event.reasoning_effort.as_deref(), Some("max"));
        assert_eq!(event.total_tokens, 15);
        let plain = parse_direct_record(usage.as_bytes()).unwrap();
        assert_eq!(plain.event_id, event.event_id);
        assert_eq!(plain.model, None);
    }

    #[test]
    fn service_tier_is_scoped_to_the_record_or_matching_turn_and_survives_restart() {
        let root = TestDir::new();
        let home = root.path().join(".codex");
        fs::create_dir_all(home.join("sessions")).unwrap();
        let path = home.join("sessions/speed.jsonl");
        fs::write(&path, concat!(
            "{\"type\":\"turn_context\",\"payload\":{\"turn_id\":\"fast-turn\",\"service_tier\":\"fast\"}}\n",
            "{\"type\":\"turn_context\",\"payload\":{\"turn_id\":\"normal-turn\",\"service_tier\":\"default\"}}\n"
        )).unwrap();
        let initial = scan(&home, &ScanState::default()).unwrap();
        let restored: ScanState = serde_json::from_slice(&serde_json::to_vec(&initial.next_state).unwrap()).unwrap();
        let fast = record("fast-speed-response",15).replace("\"response_id\":","\"turn_id\":\"fast-turn\",\"response_id\":");
        let direct = record("direct-speed-response",16)
            .replace("\"response_id\":","\"turn_id\":\"normal-turn\",\"service_tier\":\"priority\",\"response_id\":");
        fs::OpenOptions::new().append(true).open(&path).unwrap().write_all((fast.clone()+&direct).as_bytes()).unwrap();
        let outcome=scan(&home,&restored).unwrap();
        assert_eq!(outcome.events[0].service_tier.as_deref(),Some("fast"));
        assert_eq!(outcome.events[1].service_tier.as_deref(),Some("priority"));
        assert_eq!(outcome.events[0].event_id,parse_direct_record(fast.as_bytes()).unwrap().event_id);
        assert_eq!(serde_json::to_value(&outcome.events[0]).unwrap()["serviceTier"],"fast");
        let unknown=record("unrecorded-speed",17);
        assert_eq!(parse_direct_record(unknown.as_bytes()).unwrap().service_tier,None);
    }

    #[test]
    fn direct_metadata_overrides_turn_metadata_and_reasoning_effort_wins_over_effort() {
        let root = TestDir::new();
        let home = root.path().join(".codex");
        fs::create_dir_all(home.join("sessions")).unwrap();
        let log = home.join("sessions/run.jsonl");
        let usage = record("direct-response", 21)
            .replace("\"response_id\":", "\"turn_id\":\"turn-1\",\"response_id\":")
            .replace(
                "\"usage\":",
                "\"model\":\"vendor/model:custom\",\"reasoning_effort\":\"xhigh\",\"effort\":\"max\",\"usage\":",
            );
        let effort_alias = record("effort-alias-response", 22)
            .replace(
                "\"response_id\":",
                "\"turn_id\":\"turn-1\",\"response_id\":",
            )
            .replace("\"usage\":", "\"effort\":\"ultra\",\"usage\":");
        fs::write(
            &log,
            concat!("{\"type\":\"turn_context\",\"payload\":{\"turn_id\":\"turn-1\",\"model\":\"gpt-6.1-sol\",\"effort\":\"low\"}}\n")
                .to_owned()
                + &usage
                + &effort_alias,
        )
        .unwrap();

        let outcome = scan(&home, &ScanState::default()).unwrap();
        assert_eq!(outcome.events.len(), 2);
        assert_eq!(
            outcome.events[0].model.as_deref(),
            Some("vendor/model:custom")
        );
        assert_eq!(outcome.events[0].reasoning_effort.as_deref(), Some("xhigh"));
        assert_eq!(outcome.events[0].total_tokens, 21);
        assert_eq!(outcome.events[1].model.as_deref(), Some("gpt-6.1-sol"));
        assert_eq!(outcome.events[1].reasoning_effort.as_deref(), Some("ultra"));
        assert_eq!(outcome.events[1].total_tokens, 22);
    }

    #[test]
    fn missing_or_unsupported_metadata_stays_null_without_dropping_token_events() {
        let root = TestDir::new();
        let home = root.path().join(".codex");
        fs::create_dir_all(home.join("sessions")).unwrap();
        let log = home.join("sessions/run.jsonl");
        let invalid = record("invalid-direct", 11)
            .replace("\"response_id\":", "\"turn_id\":\"known\",\"response_id\":")
            .replace(
                "\"usage\":",
                "\"model\":\"gpt 6.1\",\"effort\":\"thinking\",\"usage\":",
            );
        let unknown = record("unknown-turn", 12).replace(
            "\"response_id\":",
            "\"turn_id\":\"not-cached\",\"response_id\":",
        );
        let without_turn_id = record("current-turn", 13);
        fs::write(
            &log,
            concat!("{\"type\":\"turn_context\",\"payload\":{\"turn_id\":\"known\",\"model\":\"gpt-6.1-sol\",\"effort\":\"max\"}}\n")
                .to_owned()
                + &invalid
                + &unknown
                + &without_turn_id,
        )
        .unwrap();

        let events = scan(&home, &ScanState::default()).unwrap().events;
        assert_eq!(events.len(), 3);
        assert_eq!(events[0].total_tokens, 11);
        assert_eq!(events[0].model, None);
        assert_eq!(events[0].reasoning_effort, None);
        assert_eq!(events[1].total_tokens, 12);
        assert_eq!(events[1].model, None);
        assert_eq!(events[1].reasoning_effort, None);
        assert_eq!(events[2].total_tokens, 13);
        assert_eq!(events[2].model.as_deref(), Some("gpt-6.1-sol"));
        assert_eq!(events[2].reasoning_effort.as_deref(), Some("max"));

        let json = serde_json::to_value(&events[1]).unwrap();
        assert!(json["model"].is_null());
        assert!(json["reasoningEffort"].is_null());
        assert!(json.get("reasoning_effort").is_none());
    }

    #[test]
    fn old_file_cursors_deserialize_without_metadata() {
        let state: ScanState = serde_json::from_str(
            r#"{"files":{"run.jsonl":{"offset":12,"prefix_len":8,"prefix_hash":"old-hash"}}}"#,
        )
        .unwrap();
        let cursor = &state.files["run.jsonl"];
        assert_eq!(cursor.offset, 12);
        assert_eq!(cursor.current_metadata, TurnMetadata::default());
        assert!(cursor.turn_metadata.is_empty());
        assert!(cursor.turn_metadata_order.is_empty());
    }

    #[test]
    fn turn_metadata_cache_is_bounded_and_keeps_only_hashed_turn_ids() {
        let root = TestDir::new();
        let home = root.path().join(".codex");
        fs::create_dir_all(home.join("sessions")).unwrap();
        let log = home.join("sessions/run.jsonl");
        let contexts = (0..MAX_CACHED_TURNS + 1)
            .map(|index| {
                format!(
                    "{{\"type\":\"turn_context\",\"payload\":{{\"turn_id\":\"turn-{index}\",\"model\":\"gpt-6.1-sol\",\"effort\":\"max\"}}}}\n"
                )
            })
            .collect::<String>();
        fs::write(&log, contexts).unwrap();

        let state = scan(&home, &ScanState::default()).unwrap().next_state;
        let cursor = &state.files["run.jsonl"];
        assert_eq!(cursor.turn_metadata.len(), MAX_CACHED_TURNS);
        assert_eq!(cursor.turn_metadata_order.len(), MAX_CACHED_TURNS);
        assert!(cursor
            .turn_metadata
            .keys()
            .all(|turn_hash| is_turn_hash(turn_hash)));
        assert!(!cursor.turn_metadata.contains_key(&hex_digest(b"turn-0")));
        assert!(cursor.turn_metadata.contains_key(&hex_digest(b"turn-256")));
        assert_eq!(
            cursor.current_metadata.model.as_deref(),
            Some("gpt-6.1-sol")
        );
    }

    fn record_at(response_id: &str, tokens: u64, timestamp: &str) -> String {
        format!(
            "{{\"type\":\"token_usage_record\",\"timestamp\":\"{timestamp}\",\"payload\":{{\"response_id\":\"{response_id}\",\"usage\":{{\"total_tokens\":{tokens}}}}}}}\n"
        )
    }

    fn cumulative_at(
        timestamp: &str,
        total: u64,
        input: Option<u64>,
        output: Option<u64>,
    ) -> String {
        let mut usage = serde_json::Map::new();
        usage.insert("total_tokens".into(), serde_json::json!(total));
        if let Some(input) = input {
            usage.insert("input_tokens".into(), serde_json::json!(input));
        }
        if let Some(output) = output {
            usage.insert("output_tokens".into(), serde_json::json!(output));
        }
        let row = serde_json::json!({
            "type": "event_msg",
            "timestamp": timestamp,
            "payload": {
                "type": "token_count",
                "info": {
                    "total_token_usage": usage,
                    "last_token_usage": { "total_tokens": 1 }
                }
            }
        });
        format!("{}\n", serde_json::to_string(&row).unwrap())
    }

    fn set_file_mtime_older_than(path: &Path, age: Duration) {
        let modified = SystemTime::now() - age;
        fs::OpenOptions::new()
            .read(true)
            .write(true)
            .open(path)
            .unwrap()
            .set_times(fs::FileTimes::new().set_modified(modified))
            .unwrap();
    }

    fn read_http_request(stream: &mut std::net::TcpStream) -> Vec<u8> {
        stream.set_nonblocking(false).unwrap();
        let mut bytes = Vec::new();
        let mut buffer = [0u8; 4096];
        loop {
            let header_end = bytes
                .windows(4)
                .position(|part| part == b"\r\n\r\n")
                .map(|position| position + 4);
            if let Some(header_end) = header_end {
                let header = String::from_utf8_lossy(&bytes[..header_end]);
                let content_length = header
                    .lines()
                    .find_map(|line| {
                        line.to_ascii_lowercase()
                            .starts_with("content-length:")
                            .then(|| {
                                line.split_once(':')
                                    .and_then(|(_, value)| value.trim().parse::<usize>().ok())
                            })
                            .flatten()
                    })
                    .unwrap_or(0);
                if bytes.len() >= header_end + content_length {
                    return bytes;
                }
            }
            let count = stream.read(&mut buffer).unwrap();
            assert!(count > 0, "HTTP request ended before its declared body");
            bytes.extend_from_slice(&buffer[..count]);
        }
    }

    #[test]
    fn reports_token_components_without_recounting_cache_or_reasoning() {
        let event = parse_direct_record(
            br#"{"type":"token_usage_record","timestamp":"2026-10-10T00:00:00Z",
            "payload":{"response_id":"breakdown","usage":{"total_tokens":1200,"input_tokens":1000,
            "cached_input_tokens":300,"cache_write_input_tokens":100,"output_tokens":200,
            "reasoning_output_tokens":150}}}"#,
        )
        .unwrap();
        assert_eq!(event.total_tokens, 1200);
        assert_eq!(event.input_tokens, Some(1000));
        assert_eq!(event.cached_input_tokens, Some(300));
        assert_eq!(event.cache_write_input_tokens, Some(100));
        assert_eq!(event.output_tokens, Some(200));
        assert_eq!(event.reasoning_output_tokens, Some(150));
        let json = serde_json::to_value(&event).unwrap();
        assert_eq!(json["inputTokens"], 1000);
        assert_eq!(json["reasoningOutputTokens"], 150);
        let legacy =
            parse_direct_record(record_at("legacy", 42, "2026-10-10T00:00:00Z").as_bytes())
                .unwrap();
        assert_eq!(legacy.input_tokens, None);
        assert_eq!(legacy.output_tokens, None);
        let restored: UsageEvent = serde_json::from_str(
            r#"{"eventId":"legacy","occurredAt":"2026-10-10T00:00:00Z",
            "totalTokens":42,"source":"TOKEN_USAGE_RECORD"}"#,
        )
        .unwrap();
        assert_eq!(restored.input_tokens, None);
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
    fn legacy_cumulative_snapshots_emit_only_positive_deltas_with_java_stable_ids() {
        let root = TestDir::new();
        let home = root.path().join(".codex");
        let sessions = home.join("sessions");
        fs::create_dir_all(&sessions).unwrap();
        let log = sessions.join("legacy.jsonl");
        let timestamp = Utc::now().to_rfc3339();
        let first = cumulative_at(&timestamp, 120, None, None);
        let second = cumulative_at(&timestamp, 180, Some(150), Some(30));
        let third = cumulative_at(&timestamp, 240, Some(200), Some(40));
        let reset = cumulative_at(&timestamp, 10, Some(5), Some(5));
        let after_reset = cumulative_at(&timestamp, 20, Some(15), Some(5));
        fs::write(&log, format!("{first}{second}{third}{reset}{after_reset}")).unwrap();
        set_file_mtime_older_than(&log, Duration::from_secs(60));

        let outcome = scan(&home, &ScanState::default()).unwrap();
        assert_eq!(outcome.events.len(), 3);
        assert_eq!(outcome.events[0].total_tokens, 60);
        assert_eq!(outcome.events[0].input_tokens, None);
        assert_eq!(outcome.events[1].input_tokens, Some(50));
        assert_eq!(outcome.events[1].output_tokens, Some(10));
        assert_eq!(outcome.events[2].total_tokens, 10);
        assert_eq!(outcome.events[2].input_tokens, Some(10));
        assert!(outcome
            .events
            .iter()
            .all(|event| event.source == "CUMULATIVE_TOKEN_COUNT_FALLBACK"));
        assert_eq!(
            outcome.events[0].event_id,
            format!(
                "codex-local-cumulative:v1:{}",
                hex_digest(second.trim_end().as_bytes())
            )
        );

        let restored: ScanState =
            serde_json::from_slice(&serde_json::to_vec(&outcome.next_state).unwrap()).unwrap();
        assert!(scan(&home, &restored).unwrap().events.is_empty());
    }

    #[test]
    fn direct_records_clear_unsettled_legacy_pending_and_permanently_disable_fallback() {
        let root = TestDir::new();
        let home = root.path().join(".codex");
        let sessions = home.join("sessions");
        fs::create_dir_all(&sessions).unwrap();
        let log = sessions.join("mixed.jsonl");
        let timestamp = Utc::now().to_rfc3339();
        fs::write(
            &log,
            cumulative_at(&timestamp, 100, Some(80), Some(20))
                + &cumulative_at(&timestamp, 130, Some(100), Some(30)),
        )
        .unwrap();

        let first = scan(&home, &ScanState::default()).unwrap();
        assert!(first.events.is_empty());
        assert_eq!(
            first.next_state.files["mixed.jsonl"]
                .pending_fallback_events
                .len(),
            1
        );
        let restored: ScanState =
            serde_json::from_slice(&serde_json::to_vec(&first.next_state).unwrap()).unwrap();
        let direct = record_at("mixed-direct", 31, &timestamp);
        fs::OpenOptions::new()
            .append(true)
            .open(&log)
            .unwrap()
            .write_all(direct.as_bytes())
            .unwrap();
        set_file_mtime_older_than(&log, Duration::from_secs(60));

        let after_direct = scan(&home, &restored).unwrap();
        assert_eq!(after_direct.events.len(), 1);
        assert_eq!(after_direct.events[0].source, "TOKEN_USAGE_RECORD");
        let cursor = &after_direct.next_state.files["mixed.jsonl"];
        assert!(cursor.direct_usage_record_observed);
        assert!(cursor.pending_fallback_events.is_empty());

        let new_direct = record_at("mixed-direct-new", 42, &timestamp);
        let later_legacy = cumulative_at(&timestamp, 500, Some(400), Some(100));
        fs::OpenOptions::new()
            .append(true)
            .open(&log)
            .unwrap()
            .write_all((later_legacy + &new_direct).as_bytes())
            .unwrap();
        let future = scan(&home, &after_direct.next_state).unwrap();
        assert_eq!(future.events.len(), 1);
        assert_eq!(future.events[0].source, "TOKEN_USAGE_RECORD");
        assert!(future.next_state.files["mixed.jsonl"]
            .pending_fallback_events
            .is_empty());
    }

    #[test]
    fn old_rust_cursor_rebuilds_history_context_without_rebilling_it() {
        let root = TestDir::new();
        let home = root.path().join(".codex");
        let sessions = home.join("sessions");
        fs::create_dir_all(&sessions).unwrap();
        let log = sessions.join("old-cursor.jsonl");
        let timestamp = Utc::now().to_rfc3339();
        let history = format!(
            "{{\"type\":\"turn_context\",\"payload\":{{\"model\":\"gpt-6.1-sol\",\"effort\":\"max\"}}}}\n{}{}",
            cumulative_at(&timestamp, 100, Some(80), Some(20)),
            cumulative_at(&timestamp, 120, Some(100), Some(20)),
        );
        fs::write(&log, &history).unwrap();
        let prefix_len = history.len().min(PREFIX_BYTES);
        let mut old_state = ScanState::default();
        old_state.files.insert(
            "old-cursor.jsonl".into(),
            FileCursor {
                offset: history.len() as u64,
                prefix_len,
                prefix_hash: hex_digest(&history.as_bytes()[..prefix_len]),
                ..FileCursor::default()
            },
        );
        let appended = cumulative_at(&timestamp, 150, Some(125), Some(25));
        fs::OpenOptions::new()
            .append(true)
            .open(&log)
            .unwrap()
            .write_all(appended.as_bytes())
            .unwrap();
        set_file_mtime_older_than(&log, Duration::from_secs(60));

        let upgraded = scan(&home, &old_state).unwrap();
        assert_eq!(upgraded.events.len(), 1);
        assert_eq!(upgraded.events[0].total_tokens, 30);
        assert_eq!(upgraded.events[0].input_tokens, Some(25));
        assert_eq!(upgraded.events[0].model.as_deref(), Some("gpt-6.1-sol"));
        assert_eq!(upgraded.events[0].reasoning_effort.as_deref(), Some("max"));
        assert_eq!(
            upgraded.next_state.files["old-cursor.jsonl"].cursor_version,
            SCAN_CURSOR_VERSION
        );
    }

    #[test]
    fn migrated_legacy_history_keeps_an_overlap_cutoff_without_rebilling() {
        let root = TestDir::new();
        let log = root.path().join("historical.jsonl");
        let timestamp = Utc::now().to_rfc3339();
        let history = cumulative_at(&timestamp, 100, Some(80), Some(20))
            + &cumulative_at(&timestamp, 120, Some(100), Some(20));
        fs::write(&log, &history).unwrap();
        let mut cursor = rebuild_legacy_cursor(&log, history.len() as u64).unwrap();
        assert!(cursor.pending_fallback_events.is_empty());
        let direct = record_at("late-after-upgrade", 20, &timestamp);
        let reviewed = process_session_record(direct.trim_end().as_bytes(), &mut cursor, true)
            .expect("late record must remain available for review");
        assert_eq!(reviewed.source, "QUARANTINED_LEGACY_DIRECT_LATE");
        assert_eq!(reviewed.total_tokens, 20);
    }

    #[test]
    fn late_direct_records_at_or_before_cutoff_or_without_time_are_quarantined() {
        let root = TestDir::new();
        let owner = uuid::Uuid::from_u128(17);
        let config = MeterConfig {
            server_url: "http://127.0.0.1:9".into(),
            codex_home: root.path().join(".codex"),
            pairing_inbox: root.path().join("pairing.json"),
            state_dir: root.path().join("state"),
            status_file: root.path().join("status.json"),
            device_id: uuid::Uuid::new_v4(),
            owner_user_sid: None,
            log_dir: None,
        };
        let sessions = config.codex_home.join("sessions");
        fs::create_dir_all(&sessions).unwrap();
        let log = sessions.join("legacy-then-direct.jsonl");
        let cutoff = (Utc::now() - TimeDelta::seconds(2)).to_rfc3339();
        let before_cutoff =
            (DateTime::parse_from_rfc3339(&cutoff).unwrap() - TimeDelta::seconds(1)).to_rfc3339();
        let after_cutoff =
            (DateTime::parse_from_rfc3339(&cutoff).unwrap() + TimeDelta::seconds(1)).to_rfc3339();
        fs::write(
            &log,
            cumulative_at(&before_cutoff, 100, Some(80), Some(20))
                + &cumulative_at(&cutoff, 150, Some(120), Some(30)),
        )
        .unwrap();
        save_state(
            &config.state_dir.join("scan-state.json"),
            &ScanState::default(),
        )
        .unwrap();
        set_file_mtime_older_than(&log, Duration::from_secs(60));

        let first = outbox::Outbox::capture(&config, &ScanState::default(), Some(owner), || Ok(()))
            .unwrap();
        assert_eq!(first.pending.len(), 1);
        assert_eq!(first.pending[0].source, "CUMULATIVE_TOKEN_COUNT_FALLBACK");

        let late_one = record_at("late-one", 25, &cutoff);
        let late_two = record_at("late-two", 26, &before_cutoff);
        let no_timestamp = record_at("late-no-time", 28, &cutoff)
            .replace(&format!("\"timestamp\":\"{cutoff}\","), "");
        let new_direct = record_at("new-direct", 27, &after_cutoff);
        fs::OpenOptions::new()
            .append(true)
            .open(&log)
            .unwrap()
            .write_all((late_one + &late_two + &no_timestamp + &new_direct).as_bytes())
            .unwrap();
        set_file_mtime_older_than(&log, Duration::from_secs(60));

        let second =
            outbox::Outbox::capture(&config, &ScanState::default(), Some(owner), || Ok(()))
                .unwrap();
        assert_eq!(second.owner_warning(), Some("METER_LEGACY_DIRECT_LATE"));
        assert_eq!(second.pending.len(), 2);
        assert!(second
            .pending
            .iter()
            .any(|event| event.source == "CUMULATIVE_TOKEN_COUNT_FALLBACK"));
        assert!(second.pending.iter().any(|event| {
            event.source == "TOKEN_USAGE_RECORD"
                && event.event_id
                    == parse_direct_record(
                        record_at("new-direct", 27, &after_cutoff).trim().as_bytes(),
                    )
                    .unwrap()
                    .event_id
        }));
        assert!(second.pending.iter().all(|event| {
            !["late-one", "late-two"].iter().any(|id| {
                event.event_id
                    == parse_direct_record(record_at(id, 25, &cutoff).trim().as_bytes())
                        .unwrap()
                        .event_id
            })
        }));
        let saved: serde_json::Value =
            serde_json::from_slice(&fs::read(config.state_dir.join("usage-outbox.json")).unwrap())
                .unwrap();
        assert_eq!(
            saved["quarantined"][0]["errorCode"],
            "METER_LEGACY_DIRECT_LATE"
        );
        assert_eq!(
            saved["quarantined"][0]["events"].as_array().unwrap().len(),
            3
        );
        assert_eq!(saved["quarantined"][0]["events"][2]["occurredAt"], "");
        let reloaded =
            outbox::Outbox::capture(&config, &ScanState::default(), Some(owner), || Ok(()))
                .unwrap();
        assert_eq!(reloaded.owner_warning(), Some("METER_LEGACY_DIRECT_LATE"));
        assert_eq!(reloaded.pending.len(), 2);
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
    fn stale_or_future_records_cannot_block_new_usage_or_the_scan_cursor() {
        let root = TestDir::new();
        let home = root.path().join(".codex");
        let sessions = home.join("sessions");
        fs::create_dir_all(&sessions).unwrap();
        let old = (Utc::now() - TimeDelta::days(31)).to_rfc3339();
        let future = (Utc::now() + TimeDelta::minutes(6)).to_rfc3339();
        fs::write(
            sessions.join("run.jsonl"),
            record_at("old", 10, &old) + &record_at("future", 20, &future) + &record("current", 30),
        )
        .unwrap();
        let state_file = root.path().join("meter-state.json");
        let mut accepted = Vec::new();
        let outcome = scan_report_and_commit(&home, &state_file, &ScanState::default(), |events| {
            accepted.extend(events.iter().map(|event| event.total_tokens));
            Ok(())
        })
        .unwrap();
        assert_eq!(accepted, vec![30]);
        assert_eq!(outcome.events.len(), 1);
        assert!(state_file.exists());

        let committed = load_state(&state_file).unwrap().unwrap();
        let next = scan_report_and_commit(&home, &state_file, &committed, |_| {
            panic!("already scanned events must not be resent")
        })
        .unwrap();
        assert!(next.events.is_empty());
    }

    #[test]
    fn configuration_accepts_http_origins_and_rejects_invalid_urls() {
        let root = TestDir::new();
        let mut config = MeterConfig {
            server_url: "http://149.88.90.196:8080".into(),
            codex_home: root.path().join(".codex"),
            pairing_inbox: root.path().join("pairing.json"),
            state_dir: root.path().join("state"),
            status_file: root.path().join("status.json"),
            device_id: uuid::Uuid::new_v4(),
            owner_user_sid: None,
            log_dir: None,
        };
        for url in [
            "http://149.88.90.196:8080",
            "http://192.168.0.102:8080",
            "http://control.example.com:8080",
            "https://control.example.com",
        ] {
            config.server_url = url.into();
            assert!(config.validate().is_ok(), "{url}");
        }
        for url in [
            "file:///C:/secret",
            "ftp://control.example.com",
            "http://user:secret@control.example.com",
            "http://control.example.com/api",
            "http://control.example.com/?token=secret",
        ] {
            config.server_url = url.into();
            assert!(config.validate().is_err(), "{url}");
        }
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
            owner_user_sid: None,
            log_dir: None,
        };
        let token = "A".repeat(43);
        fs::write(
            &config.pairing_inbox,
            format!(
                "{{\"deviceId\":\"{}\",\"deviceToken\":\"{}\",\"serverOrigin\":\"{}\"}}",
                config.device_id, token, config.server_url,
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
    fn pairing_from_another_or_missing_server_preserves_inbox_and_existing_credential() {
        let root = TestDir::new();
        let config = MeterConfig {
            server_url: "https://meter.example.com".into(),
            codex_home: root.path().join(".codex"),
            pairing_inbox: root.path().join("pairing.json"),
            state_dir: root.path().join("state"),
            status_file: root.path().join("status.json"),
            device_id: uuid::Uuid::new_v4(),
            owner_user_sid: None,
            log_dir: None,
        };
        save_credential(
            &config.state_dir,
            &MeterCredential {
                device_id: config.device_id,
                device_token: "A".repeat(43),
                user_id: Some(uuid::Uuid::from_u128(1)),
                owner_verified: true,
            },
        )
        .unwrap();
        let credential_before = fs::read(credential_file(&config.state_dir)).unwrap();
        for origin in [
            None,
            Some("https://other.example.com"),
            Some("https://meter.example.com/api"),
            Some("https://secret@meter.example.com"),
            Some("invalid"),
        ] {
            let mut inbox = serde_json::json!({
                "deviceId": config.device_id, "deviceToken": "B".repeat(43),
            });
            if let Some(origin) = origin {
                inbox["serverOrigin"] = origin.into();
            }
            let bytes = serde_json::to_vec(&inbox).unwrap();
            fs::write(&config.pairing_inbox, &bytes).unwrap();
            assert_eq!(
                consume_pairing(&config).unwrap_err(),
                "METER_PAIRING_SERVER_MISMATCH"
            );
            assert_eq!(fs::read(&config.pairing_inbox).unwrap(), bytes);
            assert_eq!(
                fs::read(credential_file(&config.state_dir)).unwrap(),
                credential_before
            );
        }
        fs::write(
            &config.pairing_inbox,
            serde_json::json!({
                "deviceId": config.device_id, "deviceToken": "B".repeat(43),
                "serverOrigin": "https://METER.example.com:443/",
            })
            .to_string(),
        )
        .unwrap();
        assert!(consume_pairing(&config).unwrap());
        assert!(!config.pairing_inbox.exists());
        assert_eq!(
            load_credential(&config.state_dir)
                .unwrap()
                .unwrap()
                .device_token,
            "B".repeat(43)
        );
    }

    fn server_state_fixture() -> (TestDir, MeterConfig, PathBuf, PathBuf) {
        let root = TestDir::new();
        let config = MeterConfig {
            server_url: "http://127.0.0.1:18080".into(),
            codex_home: root.path().join(".codex"),
            pairing_inbox: root.path().join("pairing.json"),
            state_dir: root.path().join("state"),
            status_file: root.path().join("status.json"),
            device_id: uuid::Uuid::new_v4(),
            owner_user_sid: None,
            log_dir: None,
        };
        fs::create_dir_all(config.codex_home.join("sessions")).unwrap();
        fs::create_dir_all(&config.state_dir).unwrap();
        let config_path = root.path().join("meter-config.json");
        fs::write(&config_path, serde_json::to_vec(&config).unwrap()).unwrap();
        let target = root.path().join("new-context");
        (root, config, config_path, target)
    }

    #[test]
    fn server_state_preparation_preserves_empty_old_journal_and_unknown_owner_gap() {
        let (_root, mut config, config_path, target) = server_state_fixture();
        let log = config.codex_home.join("sessions/run.jsonl");
        let history = record("old", 10);
        fs::write(&log, &history).unwrap();
        let mut journal = outbox::Outbox::capture(
            &config,
            &ScanState::default(),
            Some(uuid::Uuid::from_u128(1)),
            || Ok(()),
        )
        .unwrap();
        journal
            .acknowledge(&config, &journal.pending.clone())
            .unwrap();
        save_state(
            &config.state_dir.join("scan-state.json"),
            journal.scan_state(),
        )
        .unwrap();
        fs::write(
            credential_file(&config.state_dir),
            b"private-old-credential-do-not-copy",
        )
        .unwrap();
        let outbox_before = fs::read(config.state_dir.join("usage-outbox.json")).unwrap();
        let state_before = fs::read(config.state_dir.join("scan-state.json")).unwrap();
        let gap = record("unknown-gap", 42);
        let partial = record("cross-boundary", 17);
        let split = partial.len() / 2;
        let source_bytes = history + &gap + &partial[..split];
        fs::write(&log, &source_bytes).unwrap();
        // Repair the legacy state in which an installer already changed origin.
        config.server_url = "http://127.0.0.1:19090".into();
        let config_before = serde_json::to_vec(&config).unwrap();
        fs::write(&config_path, &config_before).unwrap();

        prepare_server_state(&config_path, &target).unwrap();
        assert_eq!(fs::read(&config_path).unwrap(), config_before);
        assert_eq!(
            fs::read(config.state_dir.join("usage-outbox.json")).unwrap(),
            outbox_before
        );
        assert_eq!(
            fs::read(target.join("archived-outbox.json")).unwrap(),
            outbox_before
        );
        assert_eq!(
            fs::read(config.state_dir.join("scan-state.json")).unwrap(),
            state_before
        );
        assert_eq!(fs::read(&log).unwrap(), source_bytes.as_bytes());
        assert_eq!(
            fs::read(credential_file(&config.state_dir)).unwrap(),
            b"private-old-credential-do-not-copy"
        );
        assert!(!credential_file(&target).exists());
        let unknown: serde_json::Value =
            serde_json::from_slice(&fs::read(target.join("unknown-usage.json")).unwrap()).unwrap();
        assert!(unknown["userId"].is_null());
        assert_eq!(unknown["serverOrigin"], "http://127.0.0.1:18080");
        assert_eq!(unknown["events"].as_array().unwrap().len(), 1);
        assert_eq!(unknown["events"][0]["totalTokens"], 42);
        assert_eq!(
            unknown["incompleteRecords"][0]["bytes"],
            serde_json::json!(partial[..split].as_bytes())
        );
        let baseline = load_state(&target.join("scan-state.json"))
            .unwrap()
            .unwrap();
        assert_eq!(
            baseline.files["run.jsonl"].offset,
            source_bytes.len() as u64
        );
        fs::write(
            &log,
            source_bytes + &partial[split..] + &record("after-boundary", 9),
        )
        .unwrap();
        let after = scan(&config.codex_home, &baseline).unwrap();
        assert_eq!(after.events.len(), 1);
        assert_eq!(after.events[0].total_tokens, 9);
        let metadata: serde_json::Value =
            serde_json::from_slice(&fs::read(target.join("server-state-prepared.json")).unwrap())
                .unwrap();
        assert_eq!(metadata["sourceServerOrigin"], "http://127.0.0.1:18080");
        assert_eq!(metadata["sourceConfigServerOrigin"], config.server_url);
        assert_eq!(metadata["credentialCopied"], false);
    }

    #[test]
    fn server_state_preparation_archives_pending_and_quarantine_bytes_without_reassignment() {
        let (_root, config, config_path, target) = server_state_fixture();
        fs::write(
            config.codex_home.join("sessions/run.jsonl"),
            record("old-pending", 20),
        )
        .unwrap();
        outbox::Outbox::capture(
            &config,
            &ScanState::default(),
            Some(uuid::Uuid::from_u128(1)),
            || Ok(()),
        )
        .unwrap();
        let path = config.state_dir.join("usage-outbox.json");
        let mut journal: serde_json::Value =
            serde_json::from_slice(&fs::read(&path).unwrap()).unwrap();
        journal["quarantined"] = serde_json::json!([{
            "userId": null, "events": journal["pending"].clone(), "errorCode": null,
        }]);
        let bytes = serde_json::to_vec(&journal).unwrap();
        fs::write(&path, &bytes).unwrap();
        prepare_server_state(&config_path, &target).unwrap();
        assert_eq!(fs::read(&path).unwrap(), bytes);
        assert_eq!(
            fs::read(target.join("archived-outbox.json")).unwrap(),
            bytes
        );
        assert!(!target.join("usage-outbox.json").exists());
    }

    #[test]
    fn server_state_preparation_without_journal_does_not_invent_an_origin() {
        let (_root, config, config_path, target) = server_state_fixture();
        let log = config.codex_home.join("sessions/run.jsonl");
        let old = record("old", 10);
        fs::write(&log, &old).unwrap();
        save_state(
            &config.state_dir.join("scan-state.json"),
            &baseline_codex_home(&config.codex_home).unwrap(),
        )
        .unwrap();
        let original = fs::read(config.state_dir.join("scan-state.json")).unwrap();
        fs::write(&log, old + &record("unknown", 8)).unwrap();
        prepare_server_state(&config_path, &target).unwrap();
        let unknown: serde_json::Value =
            serde_json::from_slice(&fs::read(target.join("unknown-usage.json")).unwrap()).unwrap();
        assert!(unknown["serverOrigin"].is_null());
        assert!(unknown["userId"].is_null());
        assert_eq!(unknown["events"][0]["totalTokens"], 8);
        assert!(!target.join("archived-outbox.json").exists());
        assert_eq!(
            fs::read(config.state_dir.join("scan-state.json")).unwrap(),
            original
        );
        assert_eq!(
            fs::read(target.join("archived-scan-state.json")).unwrap(),
            original
        );
    }

    #[test]
    fn server_state_preparation_without_any_cursor_establishes_a_nonbilling_baseline() {
        let (_root, config, config_path, target) = server_state_fixture();
        fs::write(
            config.codex_home.join("sessions/run.jsonl"),
            record("old", 10),
        )
        .unwrap();
        prepare_server_state(&config_path, &target).unwrap();
        let baseline = load_state(&target.join("scan-state.json"))
            .unwrap()
            .unwrap();
        assert!(scan(&config.codex_home, &baseline)
            .unwrap()
            .events
            .is_empty());
        assert!(fs::read_dir(&config.state_dir).unwrap().next().is_none());
    }

    #[test]
    fn server_state_preparation_rejects_other_device_profile_and_damaged_journal() {
        let (_root, config, config_path, target) = server_state_fixture();
        outbox::Outbox::capture(&config, &ScanState::default(), None, || Ok(())).unwrap();
        let outbox_path = config.state_dir.join("usage-outbox.json");
        let before = fs::read(&outbox_path).unwrap();
        let mut device = config.clone();
        device.device_id = uuid::Uuid::new_v4();
        let mut profile = config.clone();
        profile.codex_home = config.codex_home.join("other");
        for changed in [device, profile] {
            fs::write(&config_path, serde_json::to_vec(&changed).unwrap()).unwrap();
            assert_eq!(
                prepare_server_state(&config_path, &target).unwrap_err(),
                "METER_OUTBOX_IDENTITY_MISMATCH"
            );
            assert!(!target.exists());
            assert_eq!(fs::read(&outbox_path).unwrap(), before);
        }
        fs::write(&config_path, serde_json::to_vec(&config).unwrap()).unwrap();
        fs::write(&outbox_path, b"damaged-private-journal").unwrap();
        assert_eq!(
            prepare_server_state(&config_path, &target).unwrap_err(),
            "METER_OUTBOX_INVALID"
        );
        assert!(!target.exists());
        assert_eq!(fs::read(&outbox_path).unwrap(), b"damaged-private-journal");
    }

    #[test]
    fn server_state_preparation_never_reuses_an_existing_directory() {
        let (_root, _config, config_path, target) = server_state_fixture();
        fs::create_dir(&target).unwrap();
        assert_eq!(
            prepare_server_state(&config_path, &target).unwrap_err(),
            "METER_SERVER_STATE_TARGET_EXISTS"
        );
        fs::write(target.join("must-survive.txt"), b"not-owned-by-preparation").unwrap();
        assert_eq!(
            prepare_server_state(&config_path, &target).unwrap_err(),
            "METER_SERVER_STATE_TARGET_EXISTS"
        );
        assert_eq!(
            fs::read(target.join("must-survive.txt")).unwrap(),
            b"not-owned-by-preparation"
        );
    }

    #[test]
    fn server_state_preparation_rejects_a_reparse_destination_parent() {
        let (root, mut config, config_path, target) = server_state_fixture();
        let actual = root.path().join("actual");
        let link = root.path().join("linked");
        fs::create_dir(&actual).unwrap();
        #[cfg(windows)]
        {
            use std::os::windows::process::CommandExt;
            let result = std::process::Command::new("cmd.exe")
                .args(["/D", "/C", "mklink", "/J"])
                .arg(&link)
                .arg(&actual)
                .creation_flags(0x08000000)
                .output()
                .unwrap();
            assert!(
                result.status.success(),
                "could not create the synthetic junction"
            );
        }
        #[cfg(unix)]
        std::os::unix::fs::symlink(&actual, &link).unwrap();
        let result = prepare_server_state(&config_path, &link.join("new-context"));
        config.state_dir = link.clone();
        fs::write(&config_path, serde_json::to_vec(&config).unwrap()).unwrap();
        let source_result = prepare_server_state(&config_path, &target);
        #[cfg(windows)]
        fs::remove_dir(&link).unwrap();
        #[cfg(unix)]
        fs::remove_file(&link).unwrap();
        assert_eq!(result.unwrap_err(), "METER_SERVER_STATE_PATH_UNSAFE");
        assert_eq!(source_result.unwrap_err(), "METER_SERVER_STATE_PATH_UNSAFE");
        assert!(!target.exists());
        assert!(fs::read_dir(&actual).unwrap().next().is_none());
    }

    #[test]
    fn configured_log_directory_stays_stable_when_the_server_context_changes() {
        let (root, mut config, _config_path, _target) = server_state_fixture();
        let old_state = config.state_dir.clone();
        config.log_dir = Some(old_state.join("logs"));
        config.state_dir = root.path().join("new-context");
        let mut runtime = MeterRuntime::new_for_test(config.clone()).unwrap();
        assert_eq!(runtime.tick().state, "BASELINE_READY");
        let content = fs::read_to_string(old_state.join("logs/meter.jsonl")).unwrap();
        assert!(content.contains("SERVICE_STARTED"));
        assert!(content.contains("BASELINE_READY"));
        assert!(!config.state_dir.join("logs").exists());
        config.log_dir = Some(PathBuf::from("relative-logs"));
        assert_eq!(config.validate().unwrap_err(), "METER_PATH_INVALID");
        let mut legacy = serde_json::to_value(&config).unwrap();
        legacy.as_object_mut().unwrap().remove("logDir");
        let legacy: MeterConfig = serde_json::from_value(legacy).unwrap();
        assert!(legacy.log_dir.is_none());
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
            owner_user_sid: None,
            log_dir: None,
        };
        fs::write(
            &config.pairing_inbox,
            format!(
                "{{\"deviceId\":\"{}\",\"deviceToken\":\"{}\",\"serverOrigin\":\"{}\"}}",
                config.device_id,
                "A".repeat(43),
                config.server_url,
            ),
        )
        .unwrap();

        let mut runtime = MeterRuntime::new_for_test(config).unwrap();
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
                user_id: Some(uuid::Uuid::from_u128(1)),
                owner_verified: true,
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
            owner_user_sid: None,
            log_dir: None,
        };
        fs::write(
            &config.pairing_inbox,
            format!(
                "{{\"deviceId\":\"{}\",\"deviceToken\":\"{}\",\"serverOrigin\":\"{}\"}}",
                config.device_id,
                "B".repeat(43),
                config.server_url,
            ),
        )
        .unwrap();

        let mut runtime = MeterRuntime::new_for_test(config).unwrap();
        assert_eq!(runtime.tick().state, "ERROR");
        let pending = scan(&home, &load_state(&state_path).unwrap().unwrap()).unwrap();
        assert_eq!(pending.events.len(), 1);
        assert_eq!(pending.events[0].total_tokens, 20);
    }

    #[test]
    fn synthetic_meter_worker() {
        let Some(path) = std::env::var_os("QUOTA_GUARD_SYNTHETIC_METER_CONFIG") else {
            return;
        };
        let path = PathBuf::from(path);
        assert!(path.starts_with(std::env::temp_dir()));
        let config: MeterConfig = serde_json::from_slice(&fs::read(path).unwrap()).unwrap();
        assert!(config.server_url.starts_with("http://127.0.0.1:"));
        assert!(config.state_dir.starts_with(std::env::temp_dir()));
        // The child runs only the real accounting loop, with a harmless guard.
        let mut runtime = MeterRuntime::new_for_test(config).unwrap();
        runtime.tick();
        runtime.shutdown().unwrap();
    }

    fn run_synthetic_worker(config_path: &Path) {
        let output = std::process::Command::new(std::env::current_exe().unwrap())
            .args(["--exact", "tests::synthetic_meter_worker", "--nocapture"])
            .env("QUOTA_GUARD_SYNTHETIC_METER_CONFIG", config_path)
            .output()
            .unwrap();
        assert!(output.status.success(), "synthetic meter child failed");
    }

    #[test]
    fn independent_process_preserves_offline_usage_after_log_deletion_and_restart() {
        let root = TestDir::new();
        let sessions = root.path().join(".codex/sessions");
        fs::create_dir_all(&sessions).unwrap();
        let log = sessions.join("synthetic.jsonl");
        fs::write(&log, record("baseline", 1)).unwrap();
        let reserved = TcpListener::bind("127.0.0.1:0").unwrap();
        let address = reserved.local_addr().unwrap();
        let config = MeterConfig {
            server_url: format!("http://{address}"),
            codex_home: root.path().join(".codex"),
            pairing_inbox: root.path().join("pairing.json"),
            state_dir: root.path().join("state"),
            status_file: root.path().join("status.json"),
            device_id: uuid::Uuid::new_v4(),
            owner_user_sid: None,
            log_dir: None,
        };
        save_state(
            &config.state_dir.join("scan-state.json"),
            &baseline_codex_home(&config.codex_home).unwrap(),
        )
        .unwrap();
        save_credential(
            &config.state_dir,
            &MeterCredential {
                device_id: config.device_id,
                device_token: "A".repeat(43),
                user_id: Some(uuid::Uuid::from_u128(1)),
                owner_verified: true,
            },
        )
        .unwrap();
        let config_path = root.path().join("synthetic-config.json");
        fs::write(&config_path, serde_json::to_vec(&config).unwrap()).unwrap();
        fs::write(&log, record("baseline", 1) + &record("offline", 42)).unwrap();
        drop(reserved);
        run_synthetic_worker(&config_path);
        let outbox = config.state_dir.join("usage-outbox.json");
        assert!(
            outbox.is_file(),
            "offline usage must be durably copied before upload"
        );
        let offline_status: serde_json::Value =
            serde_json::from_slice(&fs::read(&config.status_file).unwrap()).unwrap();
        assert_eq!(
            offline_status["pendingEvents"], 1,
            "pending offline usage must be visible without exposing contents"
        );
        fs::remove_file(&log).unwrap();

        let listener = TcpListener::bind(address).unwrap();
        let receiver = thread::spawn(move || {
            let mut uploads = Vec::new();
            for _ in 0..2 {
                let (mut stream, _) = listener.accept().unwrap();
                stream
                    .set_read_timeout(Some(Duration::from_secs(5)))
                    .unwrap();
                let request = read_http_request(&mut stream);
                assert!(!String::from_utf8_lossy(&request)
                    .to_ascii_lowercase()
                    .contains("authorization:"));
                if request.starts_with(b"POST /api/v1/meter/usage/events ") {
                    let end = request
                        .windows(4)
                        .position(|value| value == b"\r\n\r\n")
                        .unwrap()
                        + 4;
                    uploads.push(
                        serde_json::from_slice::<serde_json::Value>(&request[end..]).unwrap(),
                    );
                }
                stream
                    .write_all(
                        b"HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}",
                    )
                    .unwrap();
            }
            uploads
        });
        run_synthetic_worker(&config_path);
        let uploads = receiver.join().unwrap();
        assert_eq!(uploads.len(), 1);
        assert_eq!(uploads[0]["events"].as_array().unwrap().len(), 1);
        assert_eq!(uploads[0]["events"][0]["totalTokens"], 42);
        let saved: serde_json::Value = serde_json::from_slice(&fs::read(outbox).unwrap()).unwrap();
        assert_eq!(saved["pending"].as_array().unwrap().len(), 0);
    }

    fn mock_meter_http(
        responses: Vec<(u16, String)>,
    ) -> (String, thread::JoinHandle<Vec<Vec<u8>>>) {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let url = format!("http://{}", listener.local_addr().unwrap());
        listener.set_nonblocking(true).unwrap();
        let handle = thread::spawn(move || {
            let mut requests = Vec::new();
            for (status, body) in responses {
                let deadline = Instant::now() + Duration::from_secs(3);
                let mut stream = loop {
                    match listener.accept() {
                        Ok((stream, _)) => break stream,
                        Err(error) if error.kind() == std::io::ErrorKind::WouldBlock => {
                            if Instant::now() >= deadline {
                                return requests;
                            }
                            thread::sleep(Duration::from_millis(10));
                        }
                        Err(error) => panic!("mock listener failed: {error}"),
                    }
                };
                stream.set_nonblocking(false).unwrap();
                stream
                    .set_read_timeout(Some(Duration::from_secs(5)))
                    .unwrap();
                requests.push(read_http_request(&mut stream));
                write!(stream, "HTTP/1.1 {status} Mock\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}", body.len()).unwrap();
            }
            requests
        });
        (url, handle)
    }

    #[test]
    fn capture_thread_persists_new_usage_and_public_progress_while_upload_is_blocked() {
        let root = TestDir::new();
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let address = listener.local_addr().unwrap();
        listener.set_nonblocking(true).unwrap();
        let (first_request_tx, first_request_rx) = std::sync::mpsc::channel();
        let (release_tx, release_rx) = std::sync::mpsc::channel();
        let server = thread::spawn(move || {
            let mut requests = Vec::new();
            let accept = || {
                let deadline = Instant::now() + Duration::from_secs(5);
                loop {
                    match listener.accept() {
                        Ok((stream, _)) => return stream,
                        Err(error) if error.kind() == std::io::ErrorKind::WouldBlock => {
                            assert!(Instant::now() < deadline, "meter request timed out");
                            thread::yield_now();
                        }
                        Err(error) => panic!("mock listener failed: {error}"),
                    }
                }
            };
            let mut first = accept();
            first
                .set_read_timeout(Some(Duration::from_secs(5)))
                .unwrap();
            let request = read_http_request(&mut first);
            first_request_tx.send(request.clone()).unwrap();
            requests.push(request);
            release_rx
                .recv_timeout(Duration::from_secs(5))
                .expect("test should release the slow upload");
            write!(
                first,
                "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{{}}"
            )
            .unwrap();
            let mut second = accept();
            second
                .set_read_timeout(Some(Duration::from_secs(5)))
                .unwrap();
            requests.push(read_http_request(&mut second));
            write!(
                second,
                "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{{}}"
            )
            .unwrap();
            requests
        });

        let config = MeterConfig {
            server_url: format!("http://{address}"),
            codex_home: root.path().join(".codex"),
            pairing_inbox: root.path().join("pairing.json"),
            state_dir: root.path().join("state"),
            status_file: root.path().join("status.json"),
            device_id: uuid::Uuid::new_v4(),
            owner_user_sid: None,
            log_dir: None,
        };
        let log = config.codex_home.join("sessions/run.jsonl");
        fs::create_dir_all(log.parent().unwrap()).unwrap();
        fs::write(&log, record("slow-first", 10)).unwrap();
        save_state(
            &config.state_dir.join("scan-state.json"),
            &ScanState::default(),
        )
        .unwrap();
        save_credential(
            &config.state_dir,
            &MeterCredential {
                device_id: config.device_id,
                device_token: "A".repeat(43),
                user_id: Some(uuid::Uuid::from_u128(1)),
                owner_verified: true,
            },
        )
        .unwrap();
        let runtime = MeterRuntime::new_with_capture_interval(
            config.clone(),
            Arc::new(|_: &MeterConfig| GuardSnapshot::inactive()),
            GUARD_POLL_INTERVAL,
            Duration::from_millis(20),
        )
        .unwrap();
        let ticker = thread::spawn(move || {
            let mut runtime = runtime;
            let status = runtime.tick();
            (runtime, status)
        });

        let first_request = first_request_rx
            .recv_timeout(Duration::from_secs(3))
            .expect("usage upload should enter the mock server");
        assert!(first_request.starts_with(b"POST /api/v1/meter/usage/events "));
        let initial_status = read_public_status(&config.status_file).unwrap();
        fs::OpenOptions::new()
            .append(true)
            .open(&log)
            .unwrap()
            .write_all(record("captured-during-upload", 20).as_bytes())
            .unwrap();

        let deadline = Instant::now() + Duration::from_secs(4);
        let mut captured_while_blocked = false;
        while Instant::now() < deadline {
            let journal: Option<serde_json::Value> =
                fs::read(config.state_dir.join("usage-outbox.json"))
                    .ok()
                    .and_then(|bytes| serde_json::from_slice(&bytes).ok());
            let status = read_public_status(&config.status_file);
            if journal
                .as_ref()
                .and_then(|journal| journal["pending"].as_array())
                .is_some_and(|pending| pending.len() == 2)
                && status.as_ref().is_some_and(|status| {
                    status.pending_events == Some(2)
                        && status.last_scan_epoch_seconds > initial_status.last_scan_epoch_seconds
                })
            {
                captured_while_blocked = true;
                break;
            }
            thread::yield_now();
        }
        assert!(
            captured_while_blocked,
            "background capture did not persist and publish while HTTP was blocked"
        );

        release_tx.send(()).unwrap();
        let (runtime, status) = ticker.join().unwrap();
        let requests = server.join().unwrap();
        runtime.shutdown().unwrap();
        assert_eq!(requests.len(), 2);
        assert_eq!(status.state, "REPORTED");
        let journal: serde_json::Value =
            serde_json::from_slice(&fs::read(config.state_dir.join("usage-outbox.json")).unwrap())
                .unwrap();
        assert_eq!(journal["pending"].as_array().unwrap().len(), 1);
    }

    #[test]
    fn fatal_capture_signal_interrupts_a_multi_batch_upload() {
        let root = TestDir::new();
        let home = root.path().join(".codex");
        fs::create_dir_all(home.join("sessions")).unwrap();
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        listener.set_nonblocking(true).unwrap();
        let config = MeterConfig {
            server_url: format!("http://{}", listener.local_addr().unwrap()),
            codex_home: home.clone(),
            pairing_inbox: root.path().join("pairing.json"),
            state_dir: root.path().join("state"),
            status_file: root.path().join("status.json"),
            device_id: uuid::Uuid::new_v4(),
            owner_user_sid: None,
            log_dir: None,
        };
        save_state(
            &config.state_dir.join("scan-state.json"),
            &ScanState::default(),
        )
        .unwrap();
        save_credential(
            &config.state_dir,
            &MeterCredential {
                device_id: config.device_id,
                device_token: "A".repeat(43),
                user_id: Some(uuid::Uuid::from_u128(1)),
                owner_verified: true,
            },
        )
        .unwrap();
        let log: String = (0..101)
            .map(|index| record(&format!("fatal-backlog-{index}"), 10))
            .collect();
        fs::write(home.join("sessions/run.jsonl"), log).unwrap();
        let mut runtime = MeterRuntime::new_for_test(config.clone()).unwrap();
        let fatal_signal = Arc::clone(&runtime.capture.failure);
        let server = thread::spawn(move || {
            let deadline = Instant::now() + Duration::from_secs(5);
            let mut stream = loop {
                match listener.accept() {
                    Ok((stream, _)) => break stream,
                    Err(error) if error.kind() == std::io::ErrorKind::WouldBlock => {
                        assert!(Instant::now() < deadline, "first upload did not arrive");
                        thread::sleep(Duration::from_millis(5));
                    }
                    Err(error) => panic!("listener failed: {error}"),
                }
            };
            stream
                .set_read_timeout(Some(Duration::from_secs(3)))
                .unwrap();
            let request = read_http_request(&mut stream);
            assert!(request.starts_with(b"POST /api/v1/meter/usage/events "));
            // Simulate the supervisor's fatal signal while the first HTTP
            // request is in flight. Real panic detection has its own test.
            *fatal_signal.lock().unwrap() = Some("METER_CAPTURE_THREAD_PANICKED");
            stream
                .write_all(b"HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}")
                .unwrap();
            let deadline = Instant::now() + Duration::from_millis(250);
            while Instant::now() < deadline {
                match listener.accept() {
                    Ok(_) => panic!("another batch was sent after capture failed"),
                    Err(error) if error.kind() == std::io::ErrorKind::WouldBlock => {
                        thread::sleep(Duration::from_millis(5))
                    }
                    Err(error) => panic!("listener failed: {error}"),
                }
            }
        });
        let status = runtime.tick();
        assert_eq!(
            status.error_code.as_deref(),
            Some("METER_CAPTURE_THREAD_PANICKED")
        );
        assert_eq!(runtime.fatal_error(), Some("METER_CAPTURE_THREAD_PANICKED"));
        runtime.shutdown().unwrap();
        server.join().unwrap();
        let journal: serde_json::Value =
            serde_json::from_slice(&fs::read(config.state_dir.join("usage-outbox.json")).unwrap())
                .unwrap();
        assert_eq!(journal["pending"].as_array().unwrap().len(), 1);
    }

    #[test]
    fn capture_thread_panic_is_a_fatal_supervisor_failure() {
        let capture = CaptureSupervisor::start(Duration::from_millis(1), || {
            panic!("synthetic capture thread panic")
        })
        .unwrap();
        let deadline = Instant::now() + Duration::from_secs(2);
        while capture.fatal_error().is_none() && Instant::now() < deadline {
            thread::yield_now();
        }
        assert_eq!(capture.fatal_error(), Some("METER_CAPTURE_THREAD_PANICKED"));
        capture.stop();
    }

    #[test]
    fn expired_meter_renews_with_only_device_key_then_retries_the_same_usage() {
        let root = TestDir::new();
        let device_id = uuid::Uuid::new_v4();
        let (url, receiver) = mock_meter_http(vec![
            (401, "{}".into()),
            (200, serde_json::json!({ "deviceId": device_id, "userId": uuid::Uuid::from_u128(1), "expiresAt": (Utc::now() + TimeDelta::days(30)).to_rfc3339() }).to_string()),
            (200, "{\"acceptedEvents\":1}".into()),
        ]);
        let config = MeterConfig {
            server_url: url,
            codex_home: root.path().join(".codex"),
            pairing_inbox: root.path().join("pairing.json"),
            state_dir: root.path().join("state"),
            status_file: root.path().join("status.json"),
            device_id,
            owner_user_sid: None,
            log_dir: None,
        };
        let credential = MeterCredential {
            device_id,
            device_token: "A".repeat(43),
            user_id: Some(uuid::Uuid::from_u128(1)),
            owner_verified: true,
        };
        let event = parse_direct_record(record("retry-stable", 42).trim().as_bytes()).unwrap();
        let result = send_events(
            &reqwest::blocking::Client::new(),
            &config,
            &credential,
            &[event],
        );
        let requests = receiver.join().unwrap();
        assert!(
            result.is_ok(),
            "expired device grant must recover without a desktop session"
        );
        assert_eq!(requests.len(), 3);
        assert!(requests[1].starts_with(b"POST /api/v1/meter/credential/renew HTTP/1.1"));
        assert_eq!(requests[0], requests[2]);
        assert!(requests
            .iter()
            .all(|request| !String::from_utf8_lossy(request)
                .to_ascii_lowercase()
                .contains("authorization:")));
    }

    #[test]
    fn stale_usage_is_retained_while_new_usage_continues_to_upload() {
        let root = TestDir::new();
        let (url, receiver) = mock_meter_http(vec![(200, "{}".into()); 6]);
        let config = MeterConfig {
            server_url: url,
            codex_home: root.path().join(".codex"),
            pairing_inbox: root.path().join("pairing.json"),
            state_dir: root.path().join("state"),
            status_file: root.path().join("status.json"),
            device_id: uuid::Uuid::new_v4(),
            owner_user_sid: None,
            log_dir: None,
        };
        fs::create_dir_all(config.codex_home.join("sessions")).unwrap();
        save_state(
            &config.state_dir.join("scan-state.json"),
            &ScanState::default(),
        )
        .unwrap();
        save_credential(
            &config.state_dir,
            &MeterCredential {
                device_id: config.device_id,
                device_token: "A".repeat(43),
                user_id: Some(uuid::Uuid::from_u128(1)),
                owner_verified: true,
            },
        )
        .unwrap();
        let stale = (Utc::now() - TimeDelta::days(31)).to_rfc3339();
        let future = (Utc::now() + TimeDelta::days(1)).to_rfc3339();
        fs::write(
            config.codex_home.join("sessions/run.jsonl"),
            record_at("stale", 10, &stale)
                + &record_at("future", 20, &future)
                + &record("fresh", 99),
        )
        .unwrap();
        let mut runtime = MeterRuntime::new_for_test(config.clone()).unwrap();
        let status = runtime.tick();
        runtime.shutdown().unwrap();
        // Restart only the meter, then capture new usage with the existing
        // device grant and durable cursor. No desktop/login process is created.
        let log = config.codex_home.join("sessions/run.jsonl");
        let existing = fs::read_to_string(&log).unwrap();
        fs::write(&log, existing + &record("after-meter-restart", 15)).unwrap();
        let mut restarted = MeterRuntime::new_for_test(config.clone()).unwrap();
        let after_restart = restarted.tick();
        restarted.shutdown().unwrap();
        assert_eq!(after_restart.state, "REPORTED");
        assert_eq!(after_restart.reported_events, 1);
        assert_eq!(after_restart.error_code, None);
        assert_eq!(after_restart.deferred_events, 2);
        let requests = receiver.join().unwrap();
        assert_eq!(
            requests.len(),
            6,
            "invalid old records must not prevent valid uploads or heartbeat"
        );
        assert!(String::from_utf8_lossy(&requests[0]).contains("\"totalTokens\":99"));
        assert!(String::from_utf8_lossy(&requests[3]).contains("\"totalTokens\":15"));
        assert!(requests[2].starts_with(b"POST /api/v1/meter/usage/reviews "));
        assert!(requests[5].starts_with(b"POST /api/v1/meter/usage/reviews "));
        assert!(requests.iter().all(|request| !String::from_utf8_lossy(request)
            .to_ascii_lowercase().contains("authorization:")));
        assert_eq!(status.state, "REPORTED");
        assert_eq!(status.error_code, None);
        assert_eq!(status.warning_code.as_deref(), Some("METER_EVENT_OUTSIDE_REPORT_WINDOW"));
        assert_eq!(status.deferred_events, 2);
        let outbox: serde_json::Value =
            serde_json::from_slice(&fs::read(config.state_dir.join("usage-outbox.json")).unwrap())
                .unwrap();
        assert_eq!(outbox["pending"].as_array().unwrap().len(), 2);
        let public: serde_json::Value =
            serde_json::from_slice(&fs::read(&config.status_file).unwrap()).unwrap();
        assert_eq!(public["pendingEvents"], 2);
        assert_eq!(public["deferredEvents"], 2);
        assert_eq!(public["state"], "REPORTED");
        assert!(public["errorCode"].is_null());
        assert_eq!(public["reviewErrorCode"], "METER_REVIEW_RECEIPT_INVALID");
        assert_eq!(outbox["pending"][0]["occurredAt"], stale);
        assert_eq!(outbox["pending"][1]["occurredAt"], future);
    }

    #[test]
    fn deferred_usage_moves_only_after_durable_review_receipt_and_survives_restart() {
        let root = TestDir::new();
        let stale = (Utc::now() - TimeDelta::days(31)).to_rfc3339();
        let line = record_at("review-history", 10, &stale);
        let event = parse_direct_record(line.trim().as_bytes()).unwrap();
        let receipt = serde_json::json!({"quarantinedEventIds":[event.event_id]}).to_string();
        let (url, receiver) = mock_meter_http(vec![(200, "{}".into()), (200, receipt), (200, "{}".into())]);
        let config = MeterConfig {
            server_url: url, codex_home: root.path().join(".codex"), pairing_inbox: root.path().join("pairing.json"),
            state_dir: root.path().join("state"), status_file: root.path().join("status.json"), device_id: uuid::Uuid::new_v4(),
            owner_user_sid: None, log_dir: None,
        };
        fs::create_dir_all(config.codex_home.join("sessions")).unwrap();
        save_state(&config.state_dir.join("scan-state.json"), &ScanState::default()).unwrap();
        save_credential(&config.state_dir, &MeterCredential {
            device_id: config.device_id, device_token: "A".repeat(43), user_id: Some(uuid::Uuid::from_u128(1)), owner_verified: true,
        }).unwrap();
        fs::write(config.codex_home.join("sessions/run.jsonl"), line).unwrap();
        let mut runtime = MeterRuntime::new_for_test(config.clone()).unwrap();
        let status = runtime.tick();
        runtime.shutdown().unwrap();
        assert_eq!(status.state, "UP_TO_DATE");
        assert_eq!(status.reported_events, 0, "quarantine receipt must not be shown as billed usage");
        assert_eq!(status.deferred_events, 0);
        assert_eq!(status.review_submitted_events, 1);
        assert_eq!(status.review_error_code, None);
        let mut restarted = MeterRuntime::new_for_test(config.clone()).unwrap();
        let status = restarted.tick();
        restarted.shutdown().unwrap();
        assert_eq!(status.review_submitted_events, 1);
        let requests = receiver.join().unwrap();
        assert_eq!(requests.len(), 3);
        assert!(requests[1].starts_with(b"POST /api/v1/meter/usage/reviews "));
        assert!(String::from_utf8_lossy(&requests[1]).contains(&stale));
        assert!(requests.iter().all(|r| !r.starts_with(b"POST /api/v1/meter/usage/events ")));
        let journal: serde_json::Value = serde_json::from_slice(&fs::read(config.state_dir.join("usage-outbox.json")).unwrap()).unwrap();
        assert!(journal["pending"].as_array().unwrap().is_empty());
        assert_eq!(journal["reviewSubmittedEvents"], 1);
    }

    #[test]
    fn future_usage_becomes_eligible_without_rewriting_its_time() {
        let now = DateTime::parse_from_rfc3339("2026-10-25T06:00:00Z").unwrap().with_timezone(&Utc);
        let timestamp = (now + TimeDelta::minutes(10)).to_rfc3339();
        let event = parse_direct_record(record_at("future-catch-up", 7, &timestamp).as_bytes()).unwrap();
        assert!(!reportable_at(&event, now));
        assert!(reportable_at(&event, now + TimeDelta::minutes(7)));
        assert_eq!(event.occurred_at, timestamp);
    }

    #[test]
    fn changed_authenticated_owner_quarantines_old_pending_and_reports_only_new_activity() {
        let root = TestDir::new();
        let (url, receiver) = mock_meter_http(vec![(200, "{}".into()), (200, "{}".into())]);
        let config = MeterConfig {
            server_url: url,
            codex_home: root.path().join(".codex"),
            pairing_inbox: root.path().join("pairing.json"),
            state_dir: root.path().join("state"),
            status_file: root.path().join("status.json"),
            device_id: uuid::Uuid::new_v4(),
            owner_user_sid: None,
            log_dir: None,
        };
        fs::create_dir_all(config.codex_home.join("sessions")).unwrap();
        let log = config.codex_home.join("sessions/run.jsonl");
        fs::write(&log, record("previous-owner", 41)).unwrap();
        save_state(
            &config.state_dir.join("scan-state.json"),
            &ScanState::default(),
        )
        .unwrap();
        let old = outbox::Outbox::capture(
            &config,
            &ScanState::default(),
            Some(uuid::Uuid::from_u128(1)),
            || Ok(()),
        )
        .unwrap();
        assert_eq!(old.pending.len(), 1);
        let outbox_path = config.state_dir.join("usage-outbox.json");
        let mut journal: serde_json::Value =
            serde_json::from_slice(&fs::read(&outbox_path).unwrap()).unwrap();
        journal["userId"] = uuid::Uuid::from_u128(1).to_string().into();
        fs::write(&outbox_path, serde_json::to_vec(&journal).unwrap()).unwrap();
        fs::write(
            &log,
            record("previous-owner", 41) + &record("ownership-transition", 17),
        )
        .unwrap();
        // Simulate deletion of the original system user and a later enrollment
        // with the same installation UUID but another authenticated owner.
        fs::write(
            credential_file(&config.state_dir),
            serde_json::json!({
                "deviceId": config.device_id, "deviceToken": "B".repeat(43),
                "userId": uuid::Uuid::from_u128(2), "ownerVerified": true,
            })
            .to_string(),
        )
        .unwrap();
        let mut runtime = MeterRuntime::new_for_test(config.clone()).unwrap();
        let first = runtime.tick();
        assert_eq!(
            first.error_code.as_deref(),
            Some("METER_OUTBOX_OWNER_UNKNOWN")
        );
        fs::write(
            &log,
            record("previous-owner", 41)
                + &record("ownership-transition", 17)
                + &record("new-owner", 99),
        )
        .unwrap();
        runtime.tick();
        let requests = receiver.join().unwrap();
        assert_eq!(requests.len(), 2);
        assert!(requests[0].starts_with(b"POST /api/v1/meter/heartbeat "));
        assert!(requests[1].starts_with(b"POST /api/v1/meter/usage/events "));
        assert!(String::from_utf8_lossy(&requests[1]).contains("\"totalTokens\":99"));
        assert!(!String::from_utf8_lossy(&requests[1]).contains("\"totalTokens\":41"));
        let saved: serde_json::Value =
            serde_json::from_slice(&fs::read(outbox_path).unwrap()).unwrap();
        assert_eq!(saved["quarantined"][0]["events"][0]["totalTokens"], 41);
        assert_eq!(saved["quarantined"][1]["events"][0]["totalTokens"], 17);
        assert!(saved["quarantined"][1]["userId"].is_null());
        assert_eq!(saved["pending"].as_array().unwrap().len(), 0);
    }

    #[test]
    fn rejected_record_is_retained_without_blocking_valid_records_in_the_same_batch() {
        let root = TestDir::new();
        let (url, receiver) = mock_meter_http(vec![
            (400, "{}".into()),
            (400, "{}".into()),
            (200, "{}".into()),
            (200, "{}".into()),
        ]);
        let config = MeterConfig {
            server_url: url,
            codex_home: root.path().join(".codex"),
            pairing_inbox: root.path().join("pairing.json"),
            state_dir: root.path().join("state"),
            status_file: root.path().join("status.json"),
            device_id: uuid::Uuid::new_v4(),
            owner_user_sid: None,
            log_dir: None,
        };
        fs::create_dir_all(config.codex_home.join("sessions")).unwrap();
        fs::write(
            config.codex_home.join("sessions/run.jsonl"),
            record("reject", 41) + &record("accept", 99),
        )
        .unwrap();
        save_state(
            &config.state_dir.join("scan-state.json"),
            &ScanState::default(),
        )
        .unwrap();
        save_credential(
            &config.state_dir,
            &MeterCredential {
                device_id: config.device_id,
                device_token: "A".repeat(43),
                user_id: Some(uuid::Uuid::from_u128(1)),
                owner_verified: true,
            },
        )
        .unwrap();
        let mut runtime = MeterRuntime::new_for_test(config.clone()).unwrap();
        let status = runtime.tick();
        let requests = receiver.join().unwrap();
        assert_eq!(
            requests.len(),
            4,
            "a rejected bulk request must isolate invalid records and continue"
        );
        assert_eq!(status.error_code.as_deref(), Some("METER_REPORT_REJECTED"));
        let saved: serde_json::Value =
            serde_json::from_slice(&fs::read(config.state_dir.join("usage-outbox.json")).unwrap())
                .unwrap();
        assert_eq!(saved["quarantined"][0]["events"][0]["totalTokens"], 41);
        assert_eq!(saved["pending"].as_array().unwrap().len(), 0);
    }

    #[test]
    fn full_server_history_window_is_reportable() {
        let now = Utc::now();
        let event = parse_direct_record(
            record_at(
                "near-expiry",
                42,
                &(now - TimeDelta::hours(29 * 24 + 12)).to_rfc3339(),
            )
            .trim()
            .as_bytes(),
        )
        .unwrap();
        assert!(reportable_at(&event, now));
    }

    #[test]
    fn outbox_reloads_deduplicates_and_rejects_other_devices_origins_and_profiles() {
        let root = TestDir::new();
        let config = MeterConfig {
            server_url: "http://127.0.0.1:1".into(),
            codex_home: root.path().join(".codex"),
            pairing_inbox: root.path().join("pairing.json"),
            state_dir: root.path().join("state"),
            status_file: root.path().join("status.json"),
            device_id: uuid::Uuid::new_v4(),
            owner_user_sid: None,
            log_dir: None,
        };
        fs::create_dir_all(config.codex_home.join("sessions")).unwrap();
        fs::write(
            config.codex_home.join("sessions/run.jsonl"),
            record("duplicate", 42) + &record("duplicate", 42),
        )
        .unwrap();
        let owner = Some(uuid::Uuid::from_u128(1));
        let captured =
            outbox::Outbox::capture(&config, &ScanState::default(), owner, || Ok(())).unwrap();
        assert_eq!(captured.pending.len(), 1);
        let replay =
            outbox::Outbox::capture(&config, &ScanState::default(), owner, || Ok(())).unwrap();
        assert_eq!(replay.pending[0].event_id, captured.pending[0].event_id);
        let stored = fs::read(config.state_dir.join("usage-outbox.json")).unwrap();
        let mut device = config.clone();
        device.device_id = uuid::Uuid::new_v4();
        let mut origin = config.clone();
        origin.server_url = "http://127.0.0.1:2".into();
        let mut profile = config.clone();
        profile.codex_home = root.path().join("other-profile");
        for changed in [device, origin, profile] {
            assert!(
                matches!(outbox::Outbox::capture(&changed, &ScanState::default(), owner, || Ok(())), Err(code) if code == "METER_OUTBOX_IDENTITY_MISMATCH")
            );
            assert_eq!(
                fs::read(config.state_dir.join("usage-outbox.json")).unwrap(),
                stored
            );
        }
    }

    #[test]
    fn invalid_or_full_outbox_fails_without_replacing_the_original_journal() {
        let root = TestDir::new();
        let config = MeterConfig {
            server_url: "http://127.0.0.1:1".into(),
            codex_home: root.path().join(".codex"),
            pairing_inbox: root.path().join("pairing.json"),
            state_dir: root.path().join("state"),
            status_file: root.path().join("status.json"),
            device_id: uuid::Uuid::new_v4(),
            owner_user_sid: None,
            log_dir: None,
        };
        fs::create_dir_all(&config.state_dir).unwrap();
        let path = config.state_dir.join("usage-outbox.json");
        fs::write(&path, b"invalid-private-canary").unwrap();
        assert!(
            matches!(outbox::Outbox::capture(&config, &ScanState::default(), None, || Ok(())), Err(code) if code == "METER_OUTBOX_INVALID")
        );
        assert_eq!(fs::read(&path).unwrap(), b"invalid-private-canary");
        File::create(&path)
            .unwrap()
            .set_len(64 * 1024 * 1024 + 1)
            .unwrap();
        assert!(
            matches!(outbox::Outbox::capture(&config, &ScanState::default(), None, || Ok(())), Err(code) if code == "METER_OUTBOX_TOO_LARGE")
        );
        assert_eq!(fs::metadata(&path).unwrap().len(), 64 * 1024 * 1024 + 1);
    }

    #[test]
    fn a_new_device_key_cannot_claim_another_owners_pending_usage() {
        let root = TestDir::new();
        let device_id = uuid::Uuid::new_v4();
        let (url, receiver) = mock_meter_http(vec![(
            200,
            serde_json::json!({
                "deviceId": device_id, "userId": uuid::Uuid::from_u128(2),
                "expiresAt": (Utc::now() + TimeDelta::days(30)).to_rfc3339(),
            })
            .to_string(),
        )]);
        let config = MeterConfig {
            server_url: url,
            codex_home: root.path().join(".codex"),
            pairing_inbox: root.path().join("pairing.json"),
            state_dir: root.path().join("state"),
            status_file: root.path().join("status.json"),
            device_id,
            owner_user_sid: None,
            log_dir: None,
        };
        fs::create_dir_all(config.codex_home.join("sessions")).unwrap();
        fs::write(
            config.codex_home.join("sessions/run.jsonl"),
            record("bound-to-owner-one", 42),
        )
        .unwrap();
        save_state(
            &config.state_dir.join("scan-state.json"),
            &ScanState::default(),
        )
        .unwrap();
        save_credential(
            &config.state_dir,
            &MeterCredential {
                device_id,
                device_token: "B".repeat(43),
                user_id: Some(uuid::Uuid::from_u128(1)),
                owner_verified: false,
            },
        )
        .unwrap();
        let mut runtime = MeterRuntime::new_for_test(config.clone()).unwrap();
        let status = runtime.tick();
        assert_eq!(
            status.error_code.as_deref(),
            Some("METER_PAIRING_OWNER_MISMATCH")
        );
        assert_eq!(status.pending_events, Some(1));
        let requests = receiver.join().unwrap();
        assert_eq!(requests.len(), 1);
        assert!(requests[0].starts_with(b"POST /api/v1/meter/credential/renew "));
        assert!(
            !load_credential(&config.state_dir)
                .unwrap()
                .unwrap()
                .owner_verified
        );
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
            owner_user_sid: None,
            log_dir: None,
        };
        let mut runtime = MeterRuntime::new_for_test(config).unwrap();
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
            owner_user_sid: None,
            log_dir: None,
        };
        let runtime = MeterRuntime::new_for_test(config).unwrap();
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
            owner_user_sid: None,
            log_dir: None,
        };
        fs::write(&config.pairing_inbox, "private-canary-not-json").unwrap();
        let mut runtime = MeterRuntime::new_for_test(config).unwrap();
        assert_eq!(
            runtime.tick().error_code.as_deref(),
            Some("METER_PAIRING_INVALID")
        );

        let content = fs::read_to_string(root.path().join("state/logs/meter.jsonl")).unwrap();
        assert!(content.contains("METER_PAIRING_INVALID"));
        assert!(!content.contains("private-canary"));
    }

    #[test]
    fn audit_write_failure_does_not_block_local_baseline_or_change_meter_status() {
        let root = TestDir::new();
        let config = MeterConfig {
            server_url: "http://127.0.0.1:1".into(),
            codex_home: root.path().join(".codex"),
            pairing_inbox: root.path().join("pairing.json"),
            state_dir: root.path().join("state"),
            status_file: root.path().join("status.json"),
            device_id: uuid::Uuid::new_v4(),
            owner_user_sid: None,
            log_dir: None,
        };
        let mut runtime = MeterRuntime::new_for_test(config).unwrap();
        fs::remove_dir_all(root.path().join("state/logs")).unwrap();
        fs::write(root.path().join("state/logs"), b"blocked").unwrap();

        let status = runtime.tick();
        assert_eq!(status.state, "BASELINE_READY");
        assert_eq!(status.error_code, None);
        let public: serde_json::Value =
            serde_json::from_slice(&fs::read(root.path().join("status.json")).unwrap()).unwrap();
        assert_eq!(public["state"], "BASELINE_READY");
        assert_eq!(public["version"], env!("CARGO_PKG_VERSION"));
    }

    #[test]
    fn repeated_service_errors_are_logged_periodically_without_flooding() {
        let status = MeterStatus {
            state: "ERROR".into(),
            version: env!("CARGO_PKG_VERSION").into(),
            last_scan_epoch_seconds: 1_000,
            last_report_epoch_seconds: None,
            reported_events: 0,
            error_code: Some("METER_BACKEND_UNAVAILABLE".into()),
            pending_events: None,
            deferred_events: 0,
            review_submitted_events: 0,
            review_error_code: None,
            warning_code: None,
            cc_switch_guard: "INACTIVE".into(),
            cc_switch_guard_checked_at: 1_000,
            cc_switch_guard_lease_id: None,
        };
        assert!(audit_kind_for_status(
            &status,
            Some("ERROR"),
            Some("METER_BACKEND_UNAVAILABLE"),
            Some(1_000),
            1_301
        )
        .is_some());
        assert!(audit_kind_for_status(
            &status,
            Some("ERROR"),
            Some("METER_BACKEND_UNAVAILABLE"),
            Some(1_000),
            1_299
        )
        .is_none());
    }

    #[test]
    fn public_status_and_summary_write_failures_do_not_block_local_baseline() {
        let root = TestDir::new();
        fs::write(root.path().join("blocked"), b"not-a-directory").unwrap();
        let config = MeterConfig {
            server_url: "http://127.0.0.1:1".into(),
            codex_home: root.path().join(".codex"),
            pairing_inbox: root.path().join("pairing.json"),
            state_dir: root.path().join("state"),
            status_file: root.path().join("blocked/status.json"),
            device_id: uuid::Uuid::new_v4(),
            owner_user_sid: None,
            log_dir: None,
        };
        let mut runtime = MeterRuntime::new_for_test(config).unwrap();

        let status = runtime.tick();
        assert_eq!(status.state, "BASELINE_READY");
        assert_eq!(status.error_code, None);
        let content = fs::read_to_string(root.path().join("state/logs/meter.jsonl")).unwrap();
        assert!(content.contains("BASELINE_READY"));
    }

    #[test]
    fn status_and_log_failures_do_not_block_durable_local_usage_capture() {
        let root = TestDir::new();
        fs::write(root.path().join("blocked-status"), b"not-a-directory").unwrap();
        fs::write(root.path().join("blocked-logs"), b"not-a-directory").unwrap();
        let config = MeterConfig {
            server_url: "http://127.0.0.1:1".into(),
            codex_home: root.path().join(".codex"),
            pairing_inbox: root.path().join("pairing.json"),
            state_dir: root.path().join("state"),
            status_file: root.path().join("blocked-status/status.json"),
            device_id: uuid::Uuid::new_v4(),
            owner_user_sid: None,
            log_dir: Some(root.path().join("blocked-logs")),
        };
        fs::create_dir_all(config.codex_home.join("sessions")).unwrap();
        fs::write(
            config.codex_home.join("sessions/run.jsonl"),
            record("durable-despite-display-errors", 37),
        )
        .unwrap();
        save_state(
            &config.state_dir.join("scan-state.json"),
            &ScanState::default(),
        )
        .unwrap();
        save_credential(
            &config.state_dir,
            &MeterCredential {
                device_id: config.device_id,
                device_token: "A".repeat(43),
                user_id: Some(uuid::Uuid::from_u128(1)),
                owner_verified: true,
            },
        )
        .unwrap();

        let mut runtime = MeterRuntime::new_for_test(config.clone()).unwrap();
        let status = runtime.tick();
        assert_eq!(
            status.error_code.as_deref(),
            Some("METER_BACKEND_UNAVAILABLE")
        );
        assert_eq!(runtime.fatal_error(), None);
        let journal: serde_json::Value =
            serde_json::from_slice(&fs::read(config.state_dir.join("usage-outbox.json")).unwrap())
                .unwrap();
        assert_eq!(journal["pending"].as_array().unwrap().len(), 1);
        assert_eq!(journal["pending"][0]["totalTokens"], 37);
    }

    #[test]
    fn guard_status_is_published_before_reporting_without_advancing_scan_state() {
        let root = TestDir::new();
        let config = MeterConfig {
            server_url: "http://127.0.0.1:1".into(),
            codex_home: root.path().join(".codex"),
            pairing_inbox: root.path().join("pairing.json"),
            state_dir: root.path().join("state"),
            status_file: root.path().join("status.json"),
            device_id: uuid::Uuid::new_v4(),
            owner_user_sid: None,
            log_dir: None,
        };
        let guard = GuardSnapshot::new(
            "CLEAR",
            epoch_seconds(),
            Some(uuid::Uuid::new_v4()),
            None,
            false,
        );
        let published_guard = guard.clone();
        let mut runtime = MeterRuntime::new_with_guard_checker(
            config,
            Arc::new(move |_| published_guard.clone()),
            GUARD_POLL_INTERVAL,
        )
        .unwrap();
        let published_epoch = runtime.guard.snapshot().termination_epoch;
        runtime.publish_guard_status(&guard).unwrap();
        let interim: MeterStatus =
            serde_json::from_slice(&fs::read(root.path().join("status.json")).unwrap()).unwrap();
        assert_eq!(interim.cc_switch_guard, "CLEAR");
        assert_eq!(interim.cc_switch_guard_lease_id, guard.lease_id);
        assert_eq!(interim.last_scan_epoch_seconds, 0);
        let independent: serde_json::Value =
            serde_json::from_slice(&fs::read(root.path().join(GUARD_STATUS_FILE)).unwrap())
                .unwrap();
        assert_eq!(independent["ccSwitchGuard"], "CLEAR");
        assert_eq!(
            independent["ccSwitchTerminationEpoch"],
            published_epoch.to_string()
        );
        assert_eq!(independent["ccSwitchTerminationCount"], 0);
        assert_eq!(
            independent["ccSwitchLastTerminatedAt"],
            serde_json::Value::Null
        );
        assert_eq!(
            independent["ccSwitchGuardLeaseId"],
            guard.lease_id.unwrap().to_string()
        );
        let public: serde_json::Value =
            serde_json::from_slice(&fs::read(root.path().join("status.json")).unwrap()).unwrap();
        assert!(public.get("ccSwitchTerminationEpoch").is_none());
        assert!(public.get("ccSwitchTerminationCount").is_none());
        assert!(public.get("ccSwitchLastTerminatedAt").is_none());
        assert!(!root.path().join("state/scan-state.json").exists());
        assert_eq!(runtime.tick().state, "BASELINE_READY");
        let final_status: MeterStatus =
            serde_json::from_slice(&fs::read(root.path().join("status.json")).unwrap()).unwrap();
        assert_eq!(final_status.state, "BASELINE_READY");
        assert!(final_status.last_scan_epoch_seconds > 0);
    }

    #[test]
    fn guard_audit_failure_does_not_stop_local_capture() {
        let root = TestDir::new();
        let (url, receiver) = mock_meter_http(vec![(200, "{}".into()), (200, "{}".into())]);
        let config = MeterConfig {
            server_url: url,
            codex_home: root.path().join(".codex"),
            pairing_inbox: root.path().join("pairing.json"),
            state_dir: root.path().join("state"),
            status_file: root.path().join("status.json"),
            device_id: uuid::Uuid::new_v4(),
            owner_user_sid: None,
            log_dir: None,
        };
        let mut runtime = MeterRuntime::new_for_test(config.clone()).unwrap();
        fs::remove_dir_all(root.path().join("state/logs")).unwrap();
        fs::write(root.path().join("state/logs"), b"blocked").unwrap();
        fs::create_dir_all(config.codex_home.join("sessions")).unwrap();
        fs::write(
            config.codex_home.join("sessions/run.jsonl"),
            record("capture-despite-log-error", 13),
        )
        .unwrap();
        save_state(
            &config.state_dir.join("scan-state.json"),
            &ScanState::default(),
        )
        .unwrap();
        save_credential(
            &config.state_dir,
            &MeterCredential {
                device_id: config.device_id,
                device_token: "A".repeat(43),
                user_id: Some(uuid::Uuid::from_u128(1)),
                owner_verified: true,
            },
        )
        .unwrap();
        let guard = GuardSnapshot::new("CLEAR", 123, Some(uuid::Uuid::new_v4()), None, false);
        let mut audit_state = GuardAuditState::default();
        assert_eq!(
            audit_state.record(&runtime.audit, &guard),
            Err("METER_LOG_UNAVAILABLE".into())
        );
        let status = runtime.tick();
        assert_eq!(status.state, "REPORTED");
        assert_eq!(receiver.join().unwrap().len(), 2);
        let journal: serde_json::Value =
            serde_json::from_slice(&fs::read(root.path().join("state/usage-outbox.json")).unwrap())
                .unwrap();
        assert_eq!(journal["pending"].as_array().unwrap().len(), 0);
    }

    #[test]
    fn guard_refreshes_during_slow_upload_and_blocks_next_batch_and_cursor_commit() {
        use std::sync::atomic::{AtomicBool, Ordering};

        let root = TestDir::new();
        let home = root.path().join(".codex");
        let sessions = home.join("sessions");
        fs::create_dir_all(&sessions).unwrap();
        let log = sessions.join("run.jsonl");
        let initial_log = record("baseline", 1);
        fs::write(&log, &initial_log).unwrap();
        let state_dir = root.path().join("state");
        let state_path = state_dir.join("scan-state.json");
        save_state(&state_path, &baseline_codex_home(&home).unwrap()).unwrap();
        let mut with_pending = initial_log;
        for index in 0..101 {
            with_pending.push_str(&record(&format!("slow-{index}"), 10));
        }
        fs::write(&log, with_pending).unwrap();
        let previous_bytes = fs::read(&state_path).unwrap();
        let device_id = uuid::Uuid::new_v4();
        save_credential(
            &state_dir,
            &MeterCredential {
                device_id,
                device_token: "A".repeat(43),
                user_id: Some(uuid::Uuid::from_u128(1)),
                owner_verified: true,
            },
        )
        .unwrap();

        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let port = listener.local_addr().unwrap().port();
        listener.set_nonblocking(true).unwrap();
        let in_flight = Arc::new(AtomicBool::new(false));
        let checker_in_flight = Arc::clone(&in_flight);
        let lease_id = uuid::Uuid::new_v4();
        let checker = Arc::new(move |_: &MeterConfig| {
            if checker_in_flight.load(Ordering::Acquire) {
                GuardSnapshot::error("METER_CC_SWITCH_GUARD_FAILED")
            } else {
                GuardSnapshot::new("CLEAR", epoch_seconds(), Some(lease_id), None, false)
            }
        });
        let guard_status_path = root.path().join(GUARD_STATUS_FILE);
        let receiver = std::thread::spawn(move || {
            let accept_deadline = Instant::now() + Duration::from_secs(5);
            let (mut stream, _) = loop {
                match listener.accept() {
                    Ok(accepted) => break accepted,
                    Err(error) if error.kind() == std::io::ErrorKind::WouldBlock => {
                        assert!(
                            Instant::now() < accept_deadline,
                            "HTTP request was not sent"
                        );
                        thread::sleep(Duration::from_millis(10));
                    }
                    Err(error) => panic!("listener failed: {error}"),
                }
            };
            stream
                .set_read_timeout(Some(Duration::from_secs(5)))
                .unwrap();
            let request = read_http_request(&mut stream);
            assert!(request.starts_with(b"POST /api/v1/meter/usage/events HTTP/1.1"));
            in_flight.store(true, Ordering::Release);

            let deadline = Instant::now() + Duration::from_secs(3);
            let mut observed_guard_error = false;
            while Instant::now() < deadline {
                if fs::read(&guard_status_path)
                    .ok()
                    .and_then(|bytes| serde_json::from_slice::<serde_json::Value>(&bytes).ok())
                    .is_some_and(|status| status["ccSwitchGuard"] == "ERROR")
                {
                    observed_guard_error = true;
                    break;
                }
                thread::sleep(Duration::from_millis(10));
            }
            use std::io::Write;
            stream
                .write_all(b"HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}")
                .unwrap();

            let deadline = Instant::now() + Duration::from_millis(250);
            let mut second_request_seen = false;
            while Instant::now() < deadline {
                match listener.accept() {
                    Ok((_, _)) => {
                        second_request_seen = true;
                        break;
                    }
                    Err(error) if error.kind() == std::io::ErrorKind::WouldBlock => {
                        thread::sleep(Duration::from_millis(10));
                    }
                    Err(error) => panic!("listener failed: {error}"),
                }
            }
            (observed_guard_error, second_request_seen)
        });

        let config = MeterConfig {
            server_url: format!("http://127.0.0.1:{port}"),
            codex_home: home,
            pairing_inbox: root.path().join("pairing.json"),
            state_dir: state_dir.clone(),
            status_file: root.path().join("status.json"),
            device_id,
            owner_user_sid: None,
            log_dir: None,
        };
        let mut runtime =
            MeterRuntime::new_with_guard_checker(config, checker, Duration::from_millis(20))
                .unwrap();
        let status = runtime.tick();
        let (observed_guard_error, second_request_seen) = receiver.join().unwrap();
        runtime.shutdown().unwrap();

        assert!(
            observed_guard_error,
            "guard status did not refresh during HTTP"
        );
        assert!(
            !second_request_seen,
            "a second batch was sent after guard failure"
        );
        assert_eq!(status.state, "ERROR");
        assert_eq!(
            status.error_code.as_deref(),
            Some("METER_CC_SWITCH_GUARD_FAILED")
        );
        assert_eq!(fs::read(&state_path).unwrap(), previous_bytes);

        let log_contents = fs::read_to_string(state_dir.join("logs/meter.jsonl")).unwrap();
        for line in log_contents.lines() {
            serde_json::from_str::<serde_json::Value>(line).unwrap();
        }
        assert!(log_contents.contains("GUARD_ERROR"));
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
                user_id: Some(uuid::Uuid::from_u128(1)),
                owner_verified: true,
            },
        )
        .unwrap();

        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let port = listener.local_addr().unwrap().port();
        let public_status_path = root.path().join("status.json");
        let status_during_requests = public_status_path.clone();
        let receiver = std::thread::spawn(move || {
            let mut observed_prepublish = false;
            for _ in 0..2 {
                let (mut stream, _) = listener.accept().unwrap();
                let public: serde_json::Value =
                    serde_json::from_slice(&fs::read(&status_during_requests).unwrap()).unwrap();
                observed_prepublish |= public["ccSwitchGuard"] == "INACTIVE"
                    && public["ccSwitchGuardCheckedAt"]
                        .as_u64()
                        .is_some_and(|at| at > 0)
                    && public["lastScanEpochSeconds"]
                        .as_u64()
                        .is_some_and(|at| at > 0);
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
            observed_prepublish
        });
        let config = MeterConfig {
            server_url: format!("http://127.0.0.1:{port}"),
            codex_home: home,
            pairing_inbox: root.path().join("pairing.json"),
            state_dir: state_dir.clone(),
            status_file: root.path().join("status.json"),
            device_id,
            owner_user_sid: None,
            log_dir: None,
        };
        let mut runtime = MeterRuntime::new_for_test(config).unwrap();
        let status = runtime.tick();
        assert!(
            receiver.join().unwrap(),
            "guard status and current capture progress were not visible before HTTP reporting"
        );
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
            owner_user_sid: None,
            log_dir: None,
        };
        let credential = MeterCredential {
            device_id: config.device_id,
            device_token: "A".repeat(43),
            user_id: Some(uuid::Uuid::from_u128(1)),
            owner_verified: true,
        };
        let event = UsageEvent {
            event_id: format!("codex-local-usage:v1:{}", "a".repeat(64)),
            occurred_at: "2026-10-07T00:00:00Z".into(),
            total_tokens: 20,
            source: "TOKEN_USAGE_RECORD".into(),
            model: None,
            reasoning_effort: None,
            service_tier: None,
            input_tokens: Some(15),
            cached_input_tokens: Some(5),
            cache_write_input_tokens: Some(0),
            output_tokens: Some(5),
            reasoning_output_tokens: Some(2),
        };
        let client = reqwest::blocking::Client::builder()
            .no_proxy()
            .build()
            .unwrap();

        send_events(&client, &config, &credential, &[event]).unwrap();
        receiver.join().unwrap();
    }
}
