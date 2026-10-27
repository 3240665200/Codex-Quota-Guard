//! Shared Codex JSONL protocol, reused by desktop and CLI adapters.
use crate::*;

pub fn scan(codex_home: &Path, previous: &ScanState) -> Result<ScanOutcome, String> {
    scan_with_boundary(codex_home, previous, None)
}

pub(crate) fn scan_with_boundary(
    codex_home: &Path,
    previous: &ScanState,
    mut incomplete_records: Option<&mut Vec<server_state::IncompleteRecord>>,
) -> Result<ScanOutcome, String> {
    let sessions = codex_home.join("sessions");
    let mut next_state = previous.clone();
    next_state.sanitize_metadata_caches();
    let mut events = Vec::new();
    let mut emitted_event_ids = std::collections::BTreeSet::new();
    let speed_reader = super::Reader::open(codex_home);
    if !sessions.is_dir() {
        return Ok(ScanOutcome { events, next_state });
    }

    for file in session_files(&sessions)? {
        if incomplete_records.is_some() {
            server_state::check_path(&file)?;
        }
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
        let prior = next_state.files.get(&key).cloned();
        let start = match prior.as_ref() {
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
        } else if let Some(cursor) = prior.as_ref() {
            (cursor.prefix_len, cursor.prefix_hash.clone())
        } else {
            (prefix_len, prefix_hash)
        };
        let mut cursor = if start == 0 {
            FileCursor::fresh()
        } else {
            prior.clone().unwrap_or_else(FileCursor::fresh)
        };
        if start > 0 && cursor.cursor_version < SCAN_CURSOR_VERSION {
            cursor = rebuild_legacy_cursor(&file, start)?;
        }
        cursor.sanitize_metadata_cache();
        let mut input = File::open(&file).map_err(|_| "SESSION_READ_UNAVAILABLE")?;
        input
            .seek(SeekFrom::Start(start))
            .map_err(|_| "SESSION_READ_UNAVAILABLE")?;
        let mut reader = BufReader::new(input);
        let speed = (metadata.len() > start).then(|| speed_reader.file(&file));
        let mut complete_offset = start;

        loop {
            let mut line = Vec::new();
            let bytes = reader
                .read_until(b'\n', &mut line)
                .map_err(|_| "SESSION_READ_UNAVAILABLE")?;
            if bytes == 0 {
                break;
            }
            if !line.ends_with(b"\n") {
                if let Some(records) = &mut incomplete_records {
                    records.push(server_state::IncompleteRecord {
                        file: key.clone(),
                        offset: complete_offset,
                        bytes: line,
                    });
                    // A partial row that began before the server boundary must
                    // never become a new owner's event when its suffix arrives.
                    complete_offset = complete_offset.saturating_add(bytes as u64);
                }
                break;
            }
            complete_offset = complete_offset.saturating_add(bytes as u64);
            if line.len() > MAX_RECORD_BYTES {
                continue;
            }
            if line.last() == Some(&b'\n') {
                line.pop();
            }
            if line.last() == Some(&b'\r') {
                line.pop();
            }
            if let Some(event) =
                process_session_record_with_speed(&line, &mut cursor, true, speed.as_ref())
            {
                if emitted_event_ids.insert(event.event_id.clone()) {
                    events.push(event);
                }
            }
        }

        let settled = metadata
            .modified()
            .ok()
            .and_then(|modified| SystemTime::now().duration_since(modified).ok())
            .is_some_and(|age| age >= LEGACY_FILE_SETTLING_DELAY);
        if settled && !cursor.direct_usage_record_observed {
            let settled_events = std::mem::take(&mut cursor.pending_fallback_events);
            cursor.legacy_fallback_released |= !settled_events.is_empty();
            let settled_cutoff = settled_events
                .iter()
                .filter_map(|event| {
                    DateTime::parse_from_rfc3339(&event.occurred_at)
                        .ok()
                        .map(|at| (at, event.occurred_at.as_str()))
                })
                .max_by_key(|(at, _)| *at)
                .map(|(_, timestamp)| timestamp.to_owned());
            if settled_cutoff.as_deref().is_some_and(|candidate| {
                cursor
                    .legacy_settled_cutoff
                    .as_deref()
                    .and_then(|current| DateTime::parse_from_rfc3339(current).ok())
                    .zip(DateTime::parse_from_rfc3339(candidate).ok())
                    .is_none_or(|(current, next)| next > current)
            }) {
                cursor.legacy_settled_cutoff = settled_cutoff;
            }
            for event in settled_events {
                if emitted_event_ids.insert(event.event_id.clone()) {
                    events.push(event);
                }
            }
        }

        cursor.offset = complete_offset;
        cursor.cursor_version = SCAN_CURSOR_VERSION;
        cursor.prefix_len = retained_prefix_len;
        cursor.prefix_hash = retained_prefix_hash;
        next_state.files.insert(key, cursor);
    }
    Ok(ScanOutcome { events, next_state })
}

pub(crate) fn rebuild_legacy_cursor(file: &Path, offset: u64) -> Result<FileCursor, String> {
    let mut cursor = FileCursor::fresh();
    let mut reader = BufReader::new(File::open(file).map_err(|_| "SESSION_READ_UNAVAILABLE")?);
    let mut position = 0u64;
    while position < offset {
        let mut line = Vec::new();
        let bytes = reader
            .read_until(b'\n', &mut line)
            .map_err(|_| "SESSION_READ_UNAVAILABLE")?;
        if bytes == 0 || !line.ends_with(b"\n") || position.saturating_add(bytes as u64) > offset {
            break;
        }
        position = position.saturating_add(bytes as u64);
        if line.len() > MAX_RECORD_BYTES {
            continue;
        }
        if line.last() == Some(&b'\n') {
            line.pop();
        }
        if line.last() == Some(&b'\r') {
            line.pop();
        }
        let _ = process_session_record(&line, &mut cursor, false);
    }
    // Historical usage rebuilds only the accounting context. It must never
    // create billable fallback records during a cursor format upgrade.
    cursor.pending_fallback_events.clear();
    cursor.offset = offset;
    Ok(cursor)
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
        let mut cursor = FileCursor::fresh();
        cursor.offset = metadata.len();
        cursor.prefix_len = prefix_len;
        cursor.prefix_hash = hash_file_prefix(&file, prefix_len)?;
        state.files.insert(key, cursor);
    }
    Ok(state)
}

pub(crate) fn session_files(root: &Path) -> Result<Vec<PathBuf>, String> {
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

#[cfg(test)]
pub(crate) fn parse_direct_record(line: &[u8]) -> Option<UsageEvent> {
    let row: serde_json::Value = serde_json::from_slice(line).ok()?;
    parse_token_usage_record(&row, line, None, false)
}

fn parse_late_direct_review_record(line: &[u8], cursor: &FileCursor) -> Option<UsageEvent> {
    let row: serde_json::Value = serde_json::from_slice(line).ok()?;
    parse_token_usage_record(&row, line, Some(cursor), true)
}

pub(crate) fn process_session_record(
    line: &[u8],
    cursor: &mut FileCursor,
    collect_fallback: bool,
) -> Option<UsageEvent> {
    process_session_record_with_speed(line, cursor, collect_fallback, None)
}

fn process_session_record_with_speed(
    line: &[u8],
    cursor: &mut FileCursor,
    collect_fallback: bool,
    speed: Option<&super::Timeline>,
) -> Option<UsageEvent> {
    let mut row: serde_json::Value = serde_json::from_slice(line).ok()?;
    if let Some(speed) = speed {
        speed.enrich(&mut row, cursor);
    }
    match row.get("type")?.as_str()? {
        "turn_context" => {
            update_turn_context(&row, cursor);
            None
        }
        "token_usage_record" => {
            let (mut event, missing_reliable_time) =
                match parse_token_usage_record(&row, line, None, false) {
                    Some(event) => (event, false),
                    None if cursor.legacy_fallback_released => {
                        (parse_late_direct_review_record(line, cursor)?, true)
                    }
                    None => return None,
                };
            let event_time = DateTime::parse_from_rfc3339(&event.occurred_at).ok();
            let settled_cutoff = cursor
                .legacy_settled_cutoff
                .as_deref()
                .and_then(|cutoff| DateTime::parse_from_rfc3339(cutoff).ok());
            let overlaps_settled_fallback = cursor.legacy_fallback_released
                && (missing_reliable_time
                    || !matches!((event_time, settled_cutoff), (Some(event), Some(cutoff)) if event > cutoff));
            cursor.direct_usage_record_observed = true;
            cursor.pending_fallback_events.clear();
            let metadata = metadata_for_usage(row.get("payload")?, Some(cursor));
            event.model = metadata.model;
            event.reasoning_effort = metadata.reasoning_effort;
            event.service_tier = metadata.service_tier;
            if overlaps_settled_fallback {
                // A settled fallback may already have reached the server. Keep
                // this direct record for review rather than charging both when
                // their relationship cannot be proven from the log.
                event.source = "QUARANTINED_LEGACY_DIRECT_LATE".to_owned();
            }
            Some(event)
        }
        "event_msg"
            if row
                .get("payload")
                .and_then(|payload| payload.get("type"))
                .and_then(serde_json::Value::as_str)
                == Some("token_count") =>
        {
            process_cumulative_snapshot(&row, line, cursor, collect_fallback)
        }
        _ => None,
    }
}

fn process_cumulative_snapshot(
    row: &serde_json::Value,
    line: &[u8],
    cursor: &mut FileCursor,
    collect_fallback: bool,
) -> Option<UsageEvent> {
    if cursor.direct_usage_record_observed {
        return None;
    }
    let payload = row.get("payload")?;
    let info = payload.get("info")?;
    let current = cumulative_usage(info.get("total_token_usage")?)?;
    let Some(previous) = cursor.legacy_usage.replace(current.clone()) else {
        return None;
    };
    if !current.is_at_least(&previous) {
        return None;
    }
    let delta = current.minus(&previous);
    if delta.is_zero() {
        return None;
    }
    let timestamp = row.get("timestamp")?.as_str()?;
    if !valid_timestamp(timestamp) {
        return None;
    }
    if !collect_fallback {
        // The retired Java collector may already have charged this historical
        // delta. Preserve its boundary when upgrading a byte-only Rust cursor
        // so late overlapping direct records cannot charge it a second time.
        cursor.legacy_fallback_released = true;
        let current_cutoff = cursor
            .legacy_settled_cutoff
            .as_deref()
            .and_then(|value| DateTime::parse_from_rfc3339(value).ok());
        if current_cutoff.is_none_or(|cutoff| {
            DateTime::parse_from_rfc3339(timestamp).is_ok_and(|at| at > cutoff)
        }) {
            cursor.legacy_settled_cutoff = Some(timestamp.to_owned());
        }
        return None;
    }
    let metadata = metadata_for_usage(payload, Some(cursor));
    let event = UsageEvent {
        event_id: format!("codex-local-cumulative:v1:{}", hex_digest(line)),
        occurred_at: timestamp.to_owned(),
        total_tokens: delta.total_tokens,
        source: "CUMULATIVE_TOKEN_COUNT_FALLBACK".to_owned(),
        model: metadata.model,
        reasoning_effort: metadata.reasoning_effort,
        service_tier: metadata.service_tier,
        input_tokens: delta.input_tokens,
        cached_input_tokens: delta.cached_input_tokens,
        cache_write_input_tokens: delta.cache_write_input_tokens,
        output_tokens: delta.output_tokens,
        reasoning_output_tokens: delta.reasoning_output_tokens,
    };
    if !cursor
        .pending_fallback_events
        .iter()
        .any(|pending| pending.event_id == event.event_id)
    {
        cursor.pending_fallback_events.push(event);
    }
    None
}

fn cumulative_usage(value: &serde_json::Value) -> Option<CumulativeUsage> {
    if !value.is_object() {
        return None;
    }
    let total_tokens = value.get("total_tokens")?.as_u64()?;
    if total_tokens > 100_000_000 {
        return None;
    }
    Some(CumulativeUsage {
        input_tokens: cumulative_component(value, "input_tokens")?,
        cached_input_tokens: cumulative_component(value, "cached_input_tokens")?,
        cache_write_input_tokens: cumulative_component(value, "cache_write_input_tokens")?,
        output_tokens: cumulative_component(value, "output_tokens")?,
        reasoning_output_tokens: cumulative_component(value, "reasoning_output_tokens")?,
        total_tokens,
    })
}

fn cumulative_component(value: &serde_json::Value, field: &str) -> Option<Option<u64>> {
    match value.get(field) {
        None | Some(serde_json::Value::Null) => Some(None),
        Some(component) => {
            let tokens = component.as_u64()?;
            (tokens <= 100_000_000).then_some(Some(tokens))
        }
    }
}

fn valid_timestamp(value: &str) -> bool {
    DateTime::parse_from_rfc3339(value).is_ok()
}

impl CumulativeUsage {
    fn is_at_least(&self, other: &Self) -> bool {
        component_is_at_least(self.input_tokens, other.input_tokens)
            && component_is_at_least(self.cached_input_tokens, other.cached_input_tokens)
            && component_is_at_least(
                self.cache_write_input_tokens,
                other.cache_write_input_tokens,
            )
            && component_is_at_least(self.output_tokens, other.output_tokens)
            && component_is_at_least(self.reasoning_output_tokens, other.reasoning_output_tokens)
            && self.total_tokens >= other.total_tokens
    }

    fn minus(&self, other: &Self) -> Self {
        Self {
            input_tokens: component_delta(self.input_tokens, other.input_tokens),
            cached_input_tokens: component_delta(
                self.cached_input_tokens,
                other.cached_input_tokens,
            ),
            cache_write_input_tokens: component_delta(
                self.cache_write_input_tokens,
                other.cache_write_input_tokens,
            ),
            output_tokens: component_delta(self.output_tokens, other.output_tokens),
            reasoning_output_tokens: component_delta(
                self.reasoning_output_tokens,
                other.reasoning_output_tokens,
            ),
            total_tokens: self.total_tokens - other.total_tokens,
        }
    }

    fn is_zero(&self) -> bool {
        self.total_tokens == 0
            && self.input_tokens.is_none_or(|tokens| tokens == 0)
            && self.cached_input_tokens.is_none_or(|tokens| tokens == 0)
            && self
                .cache_write_input_tokens
                .is_none_or(|tokens| tokens == 0)
            && self.output_tokens.is_none_or(|tokens| tokens == 0)
            && self
                .reasoning_output_tokens
                .is_none_or(|tokens| tokens == 0)
    }
}

fn component_is_at_least(current: Option<u64>, previous: Option<u64>) -> bool {
    current.is_none() || previous.is_none() || current >= previous
}

fn component_delta(current: Option<u64>, previous: Option<u64>) -> Option<u64> {
    Some(current? - previous?)
}

fn update_turn_context(row: &serde_json::Value, cursor: &mut FileCursor) {
    cursor.current_turn_hash = row
        .get("payload")
        .and_then(|p| p.get("turn_id"))
        .and_then(|v| v.as_str())
        .map(|id| hex_digest(id.as_bytes()));
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

pub(crate) fn parse_token_usage_record(
    row: &serde_json::Value,
    line: &[u8],
    cursor: Option<&FileCursor>,
    allow_missing_timestamp: bool,
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
    let timestamp = row
        .get("timestamp")
        .and_then(serde_json::Value::as_str)
        .unwrap_or_default();
    if !allow_missing_timestamp && !valid_timestamp(timestamp) {
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
        service_tier: metadata.service_tier,
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
    if let Some(service_tier) = overrides.service_tier {
        metadata.service_tier = service_tier;
    }
    metadata.sanitized()
}

fn metadata_from_payload(payload: &serde_json::Value) -> TurnMetadata {
    let overrides = metadata_overrides(payload);
    TurnMetadata {
        model: overrides.model.flatten(),
        reasoning_effort: overrides.reasoning_effort.flatten(),
        service_tier: overrides.service_tier.flatten(),
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
        service_tier: payload
            .get("service_tier")
            .or_else(|| payload.get("serviceTier"))
            .map(|value| {
                value
                    .as_str()
                    .and_then(validate_service_tier)
                    .map(str::to_owned)
            }),
    }
}

pub(crate) fn validate_service_tier(value: &str) -> Option<&str> {
    matches!(
        value,
        "default" | "standard" | "fast" | "priority" | "flex" | "auto" | "scale" | "unknown"
    )
    .then_some(value)
}

pub(crate) fn validate_model(value: &str) -> Option<&str> {
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

pub(crate) fn validate_reasoning_effort(value: &str) -> Option<&str> {
    matches!(
        value,
        "none" | "minimal" | "low" | "medium" | "high" | "xhigh" | "max" | "ultra"
    )
    .then_some(value)
}
