use chrono::{DateTime, TimeDelta, Utc};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::collections::{BTreeMap, VecDeque};
use std::fs::{self, File};
use std::io::{BufRead, BufReader, Read, Seek, SeekFrom, Write};
use std::path::{Path, PathBuf};
use std::sync::{Arc, Condvar, Mutex};
use std::thread::{self, JoinHandle};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

mod audit;
mod outbox;
use audit::{AuditKind, AuditLog};
#[cfg(windows)]
mod ccswitch_guard;

const MAX_RECORD_BYTES: usize = 4 * 1024 * 1024;
const MAX_GUARD_STATUS_BYTES: usize = 4096;
const PREFIX_BYTES: usize = 4096;
const MAX_CACHED_TURNS: usize = 256;
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
    pub status_file: PathBuf,
    pub device_id: uuid::Uuid,
    #[serde(default)]
    pub owner_user_sid: Option<String>,
}

impl MeterConfig {
    pub fn validate(&self) -> Result<(), String> {
        let url = reqwest::Url::parse(&self.server_url).map_err(|_| "METER_SERVER_URL_INVALID")?;
        if url.host_str().is_none() || !matches!(url.scheme(), "http" | "https") {
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

#[derive(Clone, Debug, Default, Deserialize, PartialEq, Eq, Serialize)]
struct FileCursor {
    offset: u64,
    prefix_len: usize,
    prefix_hash: String,
    #[serde(default)]
    current_metadata: TurnMetadata,
    #[serde(default)]
    turn_metadata: BTreeMap<String, TurnMetadata>,
    #[serde(default)]
    turn_metadata_order: VecDeque<String>,
}

#[derive(Clone, Debug, Default, Deserialize, PartialEq, Eq, Serialize)]
struct TurnMetadata {
    model: Option<String>,
    reasoning_effort: Option<String>,
}

#[derive(Default)]
struct MetadataOverrides {
    model: Option<Option<String>>,
    reasoning_effort: Option<Option<String>>,
}

impl FileCursor {
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
        }
    }
}

#[derive(Clone, Debug, Deserialize, Serialize)]
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

pub fn scan(codex_home: &Path, previous: &ScanState) -> Result<ScanOutcome, String> {
    let sessions = codex_home.join("sessions");
    let mut next_state = previous.clone();
    next_state.sanitize_metadata_caches();
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
        let prior = next_state.files.get(&key);
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
        let mut cursor = if start == 0 {
            FileCursor::default()
        } else {
            prior.cloned().unwrap_or_default()
        };
        cursor.sanitize_metadata_cache();
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
            if let Some(event) = process_session_record(&line, &mut cursor) {
                events.push(event);
            }
        }

        cursor.offset = complete_offset;
        cursor.prefix_len = retained_prefix_len;
        cursor.prefix_hash = retained_prefix_hash;
        next_state.files.insert(key, cursor);
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
                ..FileCursor::default()
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
    parse_token_usage_record(&row, line, None)
}

fn process_session_record(line: &[u8], cursor: &mut FileCursor) -> Option<UsageEvent> {
    let row: serde_json::Value = serde_json::from_slice(line).ok()?;
    match row.get("type")?.as_str()? {
        "turn_context" => {
            update_turn_context(&row, cursor);
            None
        }
        "token_usage_record" => {
            let mut event = parse_direct_record(line)?;
            let metadata = metadata_for_usage(row.get("payload")?, Some(cursor));
            event.model = metadata.model;
            event.reasoning_effort = metadata.reasoning_effort;
            Some(event)
        }
        _ => None,
    }
}

fn update_turn_context(row: &serde_json::Value, cursor: &mut FileCursor) {
    let Some(payload) = row.get("payload") else {
        cursor.current_metadata = TurnMetadata::default();
        return;
    };
    let metadata = metadata_from_payload(payload);
    cursor.current_metadata = metadata.clone();
    if let Some(turn_id) = payload
        .get("turn_id")
        .and_then(serde_json::Value::as_str)
        .filter(|turn_id| !turn_id.is_empty())
    {
        cursor.remember_turn_metadata(turn_id, metadata);
    }
}

fn parse_token_usage_record(
    row: &serde_json::Value,
    line: &[u8],
    cursor: Option<&FileCursor>,
) -> Option<UsageEvent> {
    if row.get("type")?.as_str()? != "token_usage_record" {
        return None;
    }
    let payload = row.get("payload")?;
    let usage = payload.get("usage")?;
    let tokens = usage.get("total_tokens")?.as_u64()?;
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
    let metadata = metadata_for_usage(payload, cursor);
    Some(UsageEvent {
        event_id: format!("codex-local-usage:v1:{}", hex_digest(identity.as_bytes())),
        occurred_at: timestamp.to_owned(),
        total_tokens: tokens,
        source: "TOKEN_USAGE_RECORD".to_owned(),
        model: metadata.model,
        reasoning_effort: metadata.reasoning_effort,
        input_tokens: token_component(usage, "input_tokens", false),
        cached_input_tokens: token_component(usage, "cached_input_tokens", true),
        cache_write_input_tokens: token_component(usage, "cache_write_input_tokens", true),
        output_tokens: token_component(usage, "output_tokens", false),
        reasoning_output_tokens: token_component(usage, "reasoning_output_tokens", true),
    })
}

fn token_component(usage: &serde_json::Value, field: &str, default_zero: bool) -> Option<u64> {
    match usage.get(field) {
        Some(value) => value.as_u64().filter(|tokens| *tokens <= 100_000_000),
        None if default_zero => Some(0),
        None => None,
    }
}

fn metadata_for_usage(payload: &serde_json::Value, cursor: Option<&FileCursor>) -> TurnMetadata {
    let mut metadata = match payload.get("turn_id") {
        Some(turn_id) => turn_id
            .as_str()
            .filter(|turn_id| !turn_id.is_empty())
            .and_then(|turn_id| {
                let turn_hash = hex_digest(turn_id.as_bytes());
                cursor?.turn_metadata.get(&turn_hash).cloned()
            })
            .unwrap_or_default(),
        None => cursor
            .map(|cursor| cursor.current_metadata.clone())
            .unwrap_or_default(),
    };
    let overrides = metadata_overrides(payload);
    if let Some(model) = overrides.model {
        metadata.model = model;
    }
    if let Some(reasoning_effort) = overrides.reasoning_effort {
        metadata.reasoning_effort = reasoning_effort;
    }
    metadata.sanitized()
}

fn metadata_from_payload(payload: &serde_json::Value) -> TurnMetadata {
    let overrides = metadata_overrides(payload);
    TurnMetadata {
        model: overrides.model.flatten(),
        reasoning_effort: overrides.reasoning_effort.flatten(),
    }
    .sanitized()
}

fn metadata_overrides(payload: &serde_json::Value) -> MetadataOverrides {
    let model = payload
        .get("model")
        .map(|value| value.as_str().and_then(validate_model).map(str::to_owned));
    let reasoning_effort = payload
        .get("reasoning_effort")
        .or_else(|| payload.get("effort"))
        .map(|value| {
            value
                .as_str()
                .and_then(validate_reasoning_effort)
                .map(str::to_owned)
        });
    MetadataOverrides {
        model,
        reasoning_effort,
    }
}

fn validate_model(value: &str) -> Option<&str> {
    let bytes = value.as_bytes();
    let (&first, rest) = bytes.split_first()?;
    if bytes.len() > 96
        || !first.is_ascii_alphanumeric()
        || !rest
            .iter()
            .all(|byte| byte.is_ascii_alphanumeric() || b"._:/-".contains(byte))
    {
        return None;
    }
    Some(value)
}

fn validate_reasoning_effort(value: &str) -> Option<&str> {
    matches!(
        value,
        "none" | "minimal" | "low" | "medium" | "high" | "xhigh" | "max" | "ultra"
    )
    .then_some(value)
}

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
            timestamp >= now - TimeDelta::days(29) && timestamp <= now + TimeDelta::minutes(4)
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
            renew_device_credential(client, config, credential)?;
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
) -> Result<(), String> {
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
    struct Renewal { device_id: uuid::Uuid, expires_at: String }
    let mut bytes = Vec::new();
    response.take(4097).read_to_end(&mut bytes).map_err(|_| "METER_RENEWAL_INVALID")?;
    if bytes.len() > 4096 { return Err("METER_RENEWAL_INVALID".into()); }
    let renewal: Renewal = serde_json::from_slice(&bytes).map_err(|_| "METER_RENEWAL_INVALID")?;
    if renewal.device_id != credential.device_id
        || !DateTime::parse_from_rfc3339(&renewal.expires_at).is_ok_and(|expires| expires > Utc::now())
    {
        return Err("METER_RENEWAL_INVALID".into());
    }
    Ok(())
}

#[derive(Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct MeterStatus {
    pub state: String,
    pub version: String,
    pub last_scan_epoch_seconds: u64,
    pub last_report_epoch_seconds: Option<u64>,
    pub reported_events: usize,
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
            })
            .map_err(|_| "METER_GUARD_THREAD_UNAVAILABLE")?;
        Ok(Self {
            snapshot,
            stop,
            worker: Mutex::new(Some(worker)),
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
    mut guard: GuardSnapshot,
    audit: &AuditLog,
    audit_state: &mut GuardAuditState,
    status_path: &Path,
) -> GuardSnapshot {
    if let Err(code) = audit_state.record(audit, &guard) {
        guard.state = "ERROR";
        guard.error_code = Some(code);
    }
    if write_guard_status(status_path, &guard).is_err() {
        guard.state = "ERROR";
        guard.error_code = Some("METER_STATUS_WRITE_FAILED".into());
        // An older CLEAR record must not remain readable after publication fails.
        let _ = fs::remove_file(status_path);
    }
    guard
}

pub struct MeterRuntime {
    config: MeterConfig,
    client: reqwest::blocking::Client,
    audit: AuditLog,
    last_logged_state: Option<String>,
    last_logged_error: Option<String>,
    last_logged_epoch_seconds: Option<u64>,
    last_heartbeat: Option<Instant>,
    last_report_epoch_seconds: Option<u64>,
    guard: GuardSupervisor,
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
        config.validate()?;
        let client = reqwest::blocking::Client::builder()
            .no_proxy()
            .connect_timeout(Duration::from_secs(5))
            .timeout(Duration::from_secs(10))
            .build()
            .map_err(|_| "METER_HTTP_UNAVAILABLE")?;
        let audit = AuditLog::new(&config.state_dir)?;
        audit.append(AuditKind::ServiceStarted, None, 0, false)?;
        let guard = GuardSupervisor::start(&config, &audit, checker, guard_interval)?;
        Ok(Self {
            config,
            client,
            audit,
            last_logged_state: None,
            last_logged_error: None,
            last_logged_epoch_seconds: None,
            last_heartbeat: None,
            last_report_epoch_seconds: None,
            guard,
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
        let mut guard = self.guard.snapshot();
        let prepublish = self.publish_guard_status(&guard);
        if let Err(code) = &prepublish {
            guard.state = "ERROR";
            guard.error_code = Some(code.clone());
        }
        let outcome = if let Err(code) = prepublish {
            Err(code)
        } else if let Some(error) = guard.error_code.clone() {
            Err(error)
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
                error_code: Some(code),
                cc_switch_guard: guard.state.into(),
                cc_switch_guard_checked_at: guard.checked_at,
                cc_switch_guard_lease_id: guard.lease_id,
            },
        };
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
        if write_public_status(&self.config.status_file, &status).is_err() {
            status.state = "ERROR".into();
            status.error_code = Some("METER_STATUS_WRITE_FAILED".into());
        } else {
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
            if self.audit.append(kind, Some(&status), 0, false).is_err() {
                status.state = "ERROR".into();
                status.error_code = Some("METER_LOG_WRITE_FAILED".into());
                if write_public_status(&self.config.status_file, &status).is_ok() {
                    self.last_public_status = Some(status.clone());
                }
            } else {
                self.last_logged_state = Some(status.state.clone());
                self.last_logged_error = status.error_code.clone();
                self.last_logged_epoch_seconds = Some(now);
            }
        }
        status
    }

    pub fn shutdown(&self) -> Result<(), String> {
        self.guard.stop();
        self.audit.append(AuditKind::ServiceStopped, None, 0, false)
    }

    fn publish_guard_status(&mut self, guard: &GuardSnapshot) -> Result<(), String> {
        let mut status = self.last_public_status.clone().unwrap_or(MeterStatus {
            state: "ERROR".into(),
            version: env!("CARGO_PKG_VERSION").into(),
            last_scan_epoch_seconds: 0,
            last_report_epoch_seconds: None,
            reported_events: 0,
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
        write_public_status(&self.config.status_file, &status)
    }

    fn tick_inner(&mut self) -> Result<(&'static str, usize), String> {
        self.guard.ensure_allowed()?;
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
            let baseline = fresh_baseline.ok_or("METER_STATE_INVALID")?;
            if let Err(code) = self
                .guard
                .with_allowed(|| save_state(&state_path, &baseline))
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
            self.guard
                .with_allowed(|| save_state(&state_path, &baseline))?;
            return Ok(("BASELINE_READY", 0));
        }
        let Some(credential) = load_credential(&self.config.state_dir)? else {
            return Ok(("UNPAIRED", 0));
        };
        if credential.device_id != self.config.device_id {
            return Err("METER_CREDENTIAL_INVALID".into());
        }
        // Capture before any network IO so source log rotation and service
        // restarts cannot erase unacknowledged usage. The journal is bound to
        // the paired device, origin and Windows profile's Codex directory.
        let mut outbox = outbox::Outbox::capture(&self.config, &previous.unwrap(), || {
            self.guard.ensure_allowed()
        })?;
        let now = Utc::now();
        let candidates: Vec<_> = outbox.pending.iter()
            .filter(|event| reportable_at(event, now)).cloned().collect();
        let mut reported_events = Vec::new();
        for batch in candidates.chunks(100) {
            self.guard.ensure_allowed()?;
            send_events_guarded(&self.client, &self.config, &credential, batch, || self.guard.ensure_allowed())?;
            reported_events.extend_from_slice(batch);
            self.guard.with_allowed(|| outbox.acknowledge(&self.config, batch))?;
        }
        self.guard.with_allowed(|| save_state(&state_path, outbox.scan_state()))?;
        if !outbox.pending.is_empty() {
            // Preserve quarantined timestamps, but never let them prevent
            // later valid records from being sent to their paired user.
            return Err("METER_EVENT_OUTSIDE_REPORT_WINDOW".into());
        }
        let reported = ScanOutcome { events: reported_events, next_state: outbox.scan_state().clone() };
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
                error_code: None,
                cc_switch_guard: guard.state.into(),
                cc_switch_guard_checked_at: guard.checked_at,
                cc_switch_guard_lease_id: guard.lease_id,
            };
            self.audit
                .append(AuditKind::UsageReported, Some(&status), tokens, false)?;
        }
        if self
            .last_heartbeat
            .is_none_or(|last| last.elapsed() >= Duration::from_secs(30))
        {
            self.guard.ensure_allowed()?;
            send_heartbeat_guarded(&self.client, &self.config, &credential, || self.guard.ensure_allowed())?;
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
                error_code: None,
                cc_switch_guard: guard.state.into(),
                cc_switch_guard_checked_at: guard.checked_at,
                cc_switch_guard_lease_id: guard.lease_id,
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

fn audit_kind_for_status(
    status: &MeterStatus,
    last_state: Option<&str>,
    last_error: Option<&str>,
    last_logged_at: Option<u64>,
    now: u64,
) -> Option<AuditKind> {
    let changed =
        last_state != Some(status.state.as_str()) || last_error != status.error_code.as_deref();
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
        assert_eq!(failed.state, "ERROR");
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

    fn read_http_request(stream: &mut std::net::TcpStream) -> Vec<u8> {
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
            owner_user_sid: None,
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
        };
        save_state(&config.state_dir.join("scan-state.json"),
            &baseline_codex_home(&config.codex_home).unwrap()).unwrap();
        save_credential(&config.state_dir, &MeterCredential {
            device_id: config.device_id, device_token: "A".repeat(43),
        }).unwrap();
        let config_path = root.path().join("synthetic-config.json");
        fs::write(&config_path, serde_json::to_vec(&config).unwrap()).unwrap();
        fs::write(&log, record("baseline", 1) + &record("offline", 42)).unwrap();
        drop(reserved);
        run_synthetic_worker(&config_path);
        let outbox = config.state_dir.join("usage-outbox.json");
        assert!(outbox.is_file(), "offline usage must be durably copied before upload");
        let offline_status: serde_json::Value = serde_json::from_slice(&fs::read(&config.status_file).unwrap()).unwrap();
        assert_eq!(offline_status["pendingEvents"], 1, "pending offline usage must be visible without exposing contents");
        fs::remove_file(&log).unwrap();

        let listener = TcpListener::bind(address).unwrap();
        let receiver = thread::spawn(move || {
            let mut uploads = Vec::new();
            for _ in 0..2 {
                let (mut stream, _) = listener.accept().unwrap();
                stream.set_read_timeout(Some(Duration::from_secs(5))).unwrap();
                let request = read_http_request(&mut stream);
                assert!(!String::from_utf8_lossy(&request).to_ascii_lowercase().contains("authorization:"));
                if request.starts_with(b"POST /api/v1/meter/usage/events ") {
                    let end = request.windows(4).position(|value| value == b"\r\n\r\n").unwrap() + 4;
                    uploads.push(serde_json::from_slice::<serde_json::Value>(&request[end..]).unwrap());
                }
                stream.write_all(b"HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}").unwrap();
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

    fn mock_meter_http(responses: Vec<(u16, String)>) -> (String, thread::JoinHandle<Vec<Vec<u8>>>) {
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
                            if Instant::now() >= deadline { return requests; }
                            thread::sleep(Duration::from_millis(10));
                        }
                        Err(error) => panic!("mock listener failed: {error}"),
                    }
                };
                stream.set_read_timeout(Some(Duration::from_secs(5))).unwrap();
                requests.push(read_http_request(&mut stream));
                write!(stream, "HTTP/1.1 {status} Mock\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}", body.len()).unwrap();
            }
            requests
        });
        (url, handle)
    }

    #[test]
    fn expired_meter_renews_with_only_device_key_then_retries_the_same_usage() {
        let root = TestDir::new();
        let device_id = uuid::Uuid::new_v4();
        let (url, receiver) = mock_meter_http(vec![
            (401, "{}".into()),
            (200, serde_json::json!({ "deviceId": device_id, "expiresAt": (Utc::now() + TimeDelta::days(30)).to_rfc3339() }).to_string()),
            (200, "{\"acceptedEvents\":1}".into()),
        ]);
        let config = MeterConfig {
            server_url: url, codex_home: root.path().join(".codex"),
            pairing_inbox: root.path().join("pairing.json"), state_dir: root.path().join("state"),
            status_file: root.path().join("status.json"), device_id, owner_user_sid: None,
        };
        let credential = MeterCredential { device_id, device_token: "A".repeat(43) };
        let event = parse_direct_record(record("retry-stable", 42).trim().as_bytes()).unwrap();
        let result = send_events(&reqwest::blocking::Client::new(), &config, &credential, &[event]);
        let requests = receiver.join().unwrap();
        assert!(result.is_ok(), "expired device grant must recover without a desktop session");
        assert_eq!(requests.len(), 3);
        assert!(requests[1].starts_with(b"POST /api/v1/meter/credential/renew HTTP/1.1"));
        assert_eq!(requests[0], requests[2]);
        assert!(requests.iter().all(|request| !String::from_utf8_lossy(request).to_ascii_lowercase().contains("authorization:")));
    }

    #[test]
    fn stale_usage_is_retained_while_new_usage_continues_to_upload() {
        let root = TestDir::new();
        let (url, receiver) = mock_meter_http(vec![(200, "{}".into()), (200, "{}".into())]);
        let config = MeterConfig {
            server_url: url, codex_home: root.path().join(".codex"),
            pairing_inbox: root.path().join("pairing.json"), state_dir: root.path().join("state"),
            status_file: root.path().join("status.json"), device_id: uuid::Uuid::new_v4(), owner_user_sid: None,
        };
        fs::create_dir_all(config.codex_home.join("sessions")).unwrap();
        save_state(&config.state_dir.join("scan-state.json"), &ScanState::default()).unwrap();
        save_credential(&config.state_dir, &MeterCredential { device_id: config.device_id, device_token: "A".repeat(43) }).unwrap();
        let stale = (Utc::now() - TimeDelta::days(31)).to_rfc3339();
        let future = (Utc::now() + TimeDelta::days(1)).to_rfc3339();
        fs::write(config.codex_home.join("sessions/run.jsonl"),
            record_at("stale", 10, &stale) + &record_at("future", 20, &future) + &record("fresh", 99)).unwrap();
        let mut runtime = MeterRuntime::new_for_test(config.clone()).unwrap();
        let status = runtime.tick();
        let requests = receiver.join().unwrap();
        assert_eq!(requests.len(), 2, "invalid old records must not prevent valid uploads or heartbeat");
        assert!(String::from_utf8_lossy(&requests[0]).contains("\"totalTokens\":99"));
        assert_eq!(status.error_code.as_deref(), Some("METER_EVENT_OUTSIDE_REPORT_WINDOW"));
        let outbox: serde_json::Value = serde_json::from_slice(&fs::read(config.state_dir.join("usage-outbox.json")).unwrap()).unwrap();
        assert_eq!(outbox["pending"].as_array().unwrap().len(), 2);
        let public: serde_json::Value = serde_json::from_slice(&fs::read(&config.status_file).unwrap()).unwrap();
        assert_eq!(public["pendingEvents"], 2);
    }

    #[test]
    fn changed_authenticated_owner_quarantines_old_pending_and_reports_only_new_activity() {
        let root = TestDir::new();
        let (url, receiver) = mock_meter_http(vec![(200, "{}".into()), (200, "{}".into())]);
        let config = MeterConfig {
            server_url: url, codex_home: root.path().join(".codex"),
            pairing_inbox: root.path().join("pairing.json"), state_dir: root.path().join("state"),
            status_file: root.path().join("status.json"), device_id: uuid::Uuid::new_v4(), owner_user_sid: None,
        };
        fs::create_dir_all(config.codex_home.join("sessions")).unwrap();
        let log = config.codex_home.join("sessions/run.jsonl");
        fs::write(&log, record("previous-owner", 41)).unwrap();
        save_state(&config.state_dir.join("scan-state.json"), &ScanState::default()).unwrap();
        let old = outbox::Outbox::capture(&config, &ScanState::default(), || Ok(())).unwrap();
        assert_eq!(old.pending.len(), 1);
        let outbox_path = config.state_dir.join("usage-outbox.json");
        let mut journal: serde_json::Value = serde_json::from_slice(&fs::read(&outbox_path).unwrap()).unwrap();
        journal["userId"] = uuid::Uuid::from_u128(1).to_string().into();
        fs::write(&outbox_path, serde_json::to_vec(&journal).unwrap()).unwrap();
        // Simulate deletion of the original system user and a later enrollment
        // with the same installation UUID but another authenticated owner.
        fs::write(credential_file(&config.state_dir), serde_json::json!({
            "deviceId": config.device_id, "deviceToken": "B".repeat(43),
            "userId": uuid::Uuid::from_u128(2), "ownerVerified": true,
        }).to_string()).unwrap();
        let mut runtime = MeterRuntime::new_for_test(config.clone()).unwrap();
        let first = runtime.tick();
        assert_eq!(first.error_code.as_deref(), Some("METER_OUTBOX_OWNER_MISMATCH"));
        fs::write(&log, record("previous-owner", 41) + &record("new-owner", 99)).unwrap();
        runtime.tick();
        let requests = receiver.join().unwrap();
        assert_eq!(requests.len(), 2);
        assert!(requests[0].starts_with(b"POST /api/v1/meter/heartbeat "));
        assert!(requests[1].starts_with(b"POST /api/v1/meter/usage/events "));
        assert!(String::from_utf8_lossy(&requests[1]).contains("\"totalTokens\":99"));
        assert!(!String::from_utf8_lossy(&requests[1]).contains("\"totalTokens\":41"));
        let saved: serde_json::Value = serde_json::from_slice(&fs::read(outbox_path).unwrap()).unwrap();
        assert_eq!(saved["quarantined"][0]["events"][0]["totalTokens"], 41);
        assert_eq!(saved["pending"].as_array().unwrap().len(), 0);
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
    fn audit_write_failure_is_visible_in_the_public_status() {
        let root = TestDir::new();
        let config = MeterConfig {
            server_url: "http://127.0.0.1:1".into(),
            codex_home: root.path().join(".codex"),
            pairing_inbox: root.path().join("pairing.json"),
            state_dir: root.path().join("state"),
            status_file: root.path().join("status.json"),
            device_id: uuid::Uuid::new_v4(),
            owner_user_sid: None,
        };
        let mut runtime = MeterRuntime::new_for_test(config).unwrap();
        fs::remove_dir_all(root.path().join("state/logs")).unwrap();
        fs::write(root.path().join("state/logs"), b"blocked").unwrap();

        let status = runtime.tick();
        assert_eq!(status.state, "ERROR");
        assert_eq!(status.error_code.as_deref(), Some("METER_LOG_WRITE_FAILED"));
        let public: serde_json::Value =
            serde_json::from_slice(&fs::read(root.path().join("status.json")).unwrap()).unwrap();
        assert_eq!(public["errorCode"], "METER_LOG_WRITE_FAILED");
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
    fn public_status_write_failure_is_recorded_in_the_private_audit_log() {
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
        };
        let mut runtime = MeterRuntime::new_for_test(config).unwrap();

        let status = runtime.tick();
        assert_eq!(
            status.error_code.as_deref(),
            Some("METER_STATUS_WRITE_FAILED")
        );
        let content = fs::read_to_string(root.path().join("state/logs/meter.jsonl")).unwrap();
        assert!(content.contains("METER_STATUS_WRITE_FAILED"));
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
    fn guard_audit_failure_stops_scan_before_cursor_is_created() {
        let root = TestDir::new();
        let config = MeterConfig {
            server_url: "http://127.0.0.1:1".into(),
            codex_home: root.path().join(".codex"),
            pairing_inbox: root.path().join("pairing.json"),
            state_dir: root.path().join("state"),
            status_file: root.path().join("status.json"),
            device_id: uuid::Uuid::new_v4(),
            owner_user_sid: None,
        };
        let runtime = MeterRuntime::new_for_test(config).unwrap();
        fs::remove_dir_all(root.path().join("state/logs")).unwrap();
        fs::write(root.path().join("state/logs"), b"blocked").unwrap();
        let guard = GuardSnapshot::new("CLEAR", 123, Some(uuid::Uuid::new_v4()), None, false);
        let mut audit_state = GuardAuditState::default();
        assert_eq!(
            audit_state.record(&runtime.audit, &guard),
            Err("METER_LOG_WRITE_FAILED".into())
        );
        assert!(!root.path().join("state/scan-state.json").exists());
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
                    && public["lastScanEpochSeconds"] == 0;
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
        };
        let mut runtime = MeterRuntime::new_for_test(config).unwrap();
        let status = runtime.tick();
        assert!(
            receiver.join().unwrap(),
            "guard status was not visible before HTTP reporting"
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
            model: None,
            reasoning_effort: None,
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
