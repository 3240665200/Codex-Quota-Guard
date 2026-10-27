use super::Reader;
use chrono::{DateTime, Utc};
use serde::Serialize;
use std::{
    collections::{BTreeMap, BTreeSet},
    fs,
    io::{BufRead, BufReader, Seek, SeekFrom},
    path::Path,
    time::Duration,
};
#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct Correction {
    pub(crate) event_id: String,
    pub(crate) service_tier: String,
}
/// Best-effort bounded repair of already-uploaded events. The server never
/// inserts missing events through this path, so a quota reset cannot be undone.
pub(crate) fn recent_corrections(home: &Path) -> Vec<Correction> {
    let reader = Reader::open(home);
    if !reader.available() {
        return Vec::new();
    }
    let Ok(mut files) = crate::session_files(&home.join("sessions")) else {
        return Vec::new();
    };
    files.sort_by_key(|p| std::cmp::Reverse(fs::metadata(p).and_then(|m| m.modified()).ok()));
    let mut remaining = 128 * 1024 * 1024u64;
    let mut result = BTreeMap::new();
    let mut seen = BTreeSet::new();
    for file in files.into_iter().take(16) {
        let Ok(meta) = fs::symlink_metadata(&file) else {
            continue;
        };
        if !meta.file_type().is_file()
            || !meta
                .modified()
                .ok()
                .and_then(|t| t.elapsed().ok())
                .is_some_and(|age| age < Duration::from_secs(86400))
        {
            continue;
        }
        let bytes = meta.len().min(32 * 1024 * 1024).min(remaining);
        remaining -= bytes;
        if bytes == 0 {
            break;
        }
        let Ok(mut input) = fs::File::open(&file) else {
            continue;
        };
        let start = meta.len().saturating_sub(bytes);
        if input.seek(SeekFrom::Start(start)).is_err() {
            continue;
        }
        let mut input = BufReader::new(input);
        let mut line = Vec::new();
        if start > 0 {
            let _ = input.read_until(b'\n', &mut line);
        }
        let timeline = reader.file(&file);
        loop {
            line.clear();
            if !input.read_until(b'\n', &mut line).is_ok_and(|n| n > 0) {
                break;
            }
            if !line.ends_with(b"\n") || line.len() > crate::MAX_RECORD_BYTES {
                continue;
            }
            let Ok(mut row) = serde_json::from_slice::<serde_json::Value>(&line) else {
                continue;
            };
            timeline.enrich(&mut row, &crate::FileCursor::fresh());
            // Keep the original newline convention used by the live scanner.
            let raw = line.strip_suffix(b"\n").unwrap_or(&line);
            let raw = raw.strip_suffix(b"\r").unwrap_or(raw);
            if let Some(event) = crate::parse_token_usage_record(&row, raw, None, false) {
                if let Some(tier) = event
                    .service_tier
                    .filter(|m| matches!(m.as_str(), "default" | "standard" | "fast" | "priority"))
                {
                    if let Ok(at) = DateTime::parse_from_rfc3339(&event.occurred_at) {
                        if seen.insert(event.event_id.clone()) {
                            result.insert(
                                (at.with_timezone(&Utc), event.event_id.clone()),
                                Correction {
                                    event_id: event.event_id,
                                    service_tier: tier,
                                },
                            );
                            if result.len() > 500 {
                                result.pop_first();
                            }
                        }
                    }
                }
            }
        }
    }
    result
        .into_iter()
        .rev()
        .map(|(_, correction)| correction)
        .collect()
}
