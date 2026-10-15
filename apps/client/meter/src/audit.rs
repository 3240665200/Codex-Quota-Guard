use crate::{epoch_seconds, MeterStatus};
use serde::Serialize;
use std::fs::{self, OpenOptions};
use std::io::Write;
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};

const MAX_LOG_BYTES: u64 = 2 * 1024 * 1024;
const LOG_BACKUPS: usize = 5;

#[derive(Clone, Copy, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub(super) enum AuditKind {
    ServiceStarted,
    ServiceStopped,
    BaselineReady,
    Unpaired,
    UsageReported,
    Heartbeat,
    Error,
    StatusChange,
    GuardActive,
    GuardInactive,
    GuardDetected,
    GuardTerminated,
    GuardError,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct AuditEntry<'a> {
    at_epoch_seconds: u64,
    kind: AuditKind,
    state: Option<&'a str>,
    reported_events: usize,
    pending_events: Option<usize>,
    reported_tokens: u64,
    heartbeat_sent: bool,
    error_code: Option<&'a str>,
    cc_switch_guard: Option<&'a str>,
}

#[derive(Clone)]
pub(super) struct AuditLog {
    directory: PathBuf,
    max_bytes: u64,
    backups: usize,
    write_lock: Arc<Mutex<()>>,
}

impl AuditLog {
    pub(super) fn new(state_dir: &Path) -> Result<Self, String> {
        Self::with_limits(&state_dir.join("logs"), MAX_LOG_BYTES, LOG_BACKUPS)
    }

    fn with_limits(directory: &Path, max_bytes: u64, backups: usize) -> Result<Self, String> {
        if !directory.is_absolute() || max_bytes < 256 || !(1..=20).contains(&backups) {
            return Err("METER_LOG_CONFIG_INVALID".into());
        }
        fs::create_dir_all(directory).map_err(|_| "METER_LOG_UNAVAILABLE")?;
        let metadata = fs::symlink_metadata(directory).map_err(|_| "METER_LOG_UNAVAILABLE")?;
        if !metadata.file_type().is_dir() {
            return Err("METER_LOG_UNAVAILABLE".into());
        }
        Ok(Self {
            directory: directory.to_path_buf(),
            max_bytes,
            backups,
            write_lock: Arc::new(Mutex::new(())),
        })
    }

    pub(super) fn append(
        &self,
        kind: AuditKind,
        status: Option<&MeterStatus>,
        reported_tokens: u64,
        heartbeat_sent: bool,
    ) -> Result<(), String> {
        let entry = AuditEntry {
            at_epoch_seconds: epoch_seconds(),
            kind,
            state: status.map(|value| safe_state(&value.state)),
            reported_events: status.map_or(0, |value| value.reported_events),
            pending_events: status.and_then(|value| value.pending_events),
            reported_tokens,
            heartbeat_sent,
            error_code: status
                .and_then(|value| value.error_code.as_deref())
                .map(safe_error),
            cc_switch_guard: status.map(|value| safe_guard(&value.cc_switch_guard)),
        };
        self.append_entry(entry)
    }

    pub(super) fn append_guard(&self, kind: AuditKind, guard_state: &str) -> Result<(), String> {
        self.append_entry(AuditEntry {
            at_epoch_seconds: epoch_seconds(),
            kind,
            state: None,
            reported_events: 0,
            pending_events: None,
            reported_tokens: 0,
            heartbeat_sent: false,
            error_code: None,
            cc_switch_guard: Some(safe_guard(guard_state)),
        })
    }

    fn append_entry(&self, entry: AuditEntry<'_>) -> Result<(), String> {
        let _write_guard = self
            .write_lock
            .lock()
            .map_err(|_| "METER_LOG_WRITE_FAILED")?;
        let mut line = serde_json::to_vec(&entry).map_err(|_| "METER_LOG_WRITE_FAILED")?;
        line.push(b'\n');
        if line.len() as u64 > self.max_bytes {
            return Err("METER_LOG_WRITE_FAILED".into());
        }
        let current = self.current_path();
        if current.exists() {
            let metadata = fs::symlink_metadata(&current).map_err(|_| "METER_LOG_WRITE_FAILED")?;
            if !metadata.file_type().is_file() {
                return Err("METER_LOG_WRITE_FAILED".into());
            }
            if metadata.len().saturating_add(line.len() as u64) > self.max_bytes {
                self.rotate()?;
            }
        }
        let mut output = OpenOptions::new()
            .append(true)
            .create(true)
            .open(current)
            .map_err(|_| "METER_LOG_WRITE_FAILED")?;
        output
            .write_all(&line)
            .map_err(|_| "METER_LOG_WRITE_FAILED")?;
        output.sync_data().map_err(|_| "METER_LOG_WRITE_FAILED")?;
        Ok(())
    }

    fn current_path(&self) -> PathBuf {
        self.directory.join("meter.jsonl")
    }

    fn backup_path(&self, index: usize) -> PathBuf {
        self.directory.join(format!("meter.{index}.jsonl"))
    }

    fn rotate(&self) -> Result<(), String> {
        for index in (1..=self.backups).rev() {
            let source = if index == 1 {
                self.current_path()
            } else {
                self.backup_path(index - 1)
            };
            if !source.exists() {
                continue;
            }
            let target = self.backup_path(index);
            if target.exists() {
                let metadata =
                    fs::symlink_metadata(&target).map_err(|_| "METER_LOG_WRITE_FAILED")?;
                if !metadata.file_type().is_file() {
                    return Err("METER_LOG_WRITE_FAILED".into());
                }
                fs::remove_file(&target).map_err(|_| "METER_LOG_WRITE_FAILED")?;
            }
            fs::rename(source, target).map_err(|_| "METER_LOG_WRITE_FAILED")?;
        }
        Ok(())
    }
}

fn safe_guard(value: &str) -> &str {
    match value {
        "INACTIVE" | "CLEAR" | "TERMINATED" | "ERROR" => value,
        _ => "ERROR",
    }
}

fn safe_state(value: &str) -> &str {
    match value {
        "BASELINE_READY" | "UNPAIRED" | "UP_TO_DATE" | "REPORTED" | "ERROR" => value,
        _ => "UNKNOWN",
    }
}

fn safe_error(value: &str) -> &str {
    if value.len() <= 64
        && !value.is_empty()
        && value
            .bytes()
            .all(|byte| byte.is_ascii_uppercase() || byte == b'_')
    {
        value
    } else {
        "METER_ERROR_REDACTED"
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;

    fn temp_dir() -> PathBuf {
        let dir = std::env::temp_dir().join(format!("meter-audit-test-{}", uuid::Uuid::new_v4()));
        fs::create_dir_all(&dir).unwrap();
        dir
    }

    fn test_status(state: &str, error: Option<&str>) -> MeterStatus {
        MeterStatus {
            state: state.into(),
            version: env!("CARGO_PKG_VERSION").into(),
            last_scan_epoch_seconds: 1,
            last_report_epoch_seconds: None,
            reported_events: 2,
            pending_events: Some(1),
            error_code: error.map(str::to_owned),
            cc_switch_guard: "INACTIVE".into(),
            cc_switch_guard_checked_at: 1,
            cc_switch_guard_lease_id: None,
        }
    }

    #[test]
    fn writes_structured_summary_without_raw_secrets_or_untrusted_error_text() {
        let root = temp_dir();
        let log = AuditLog::with_limits(&root, 1024, 2).unwrap();
        let status = test_status("REPORTED", Some("refresh_token=private-canary"));
        log.append(AuditKind::UsageReported, Some(&status), 321, false)
            .unwrap();

        let content = fs::read_to_string(root.join("meter.jsonl")).unwrap();
        let row: serde_json::Value = serde_json::from_str(content.trim()).unwrap();
        assert_eq!(row["kind"], "USAGE_REPORTED");
        assert_eq!(row["reportedEvents"], 2);
        assert_eq!(row["reportedTokens"], 321);
        assert!(!content.contains("private-canary"));
        assert!(!content.contains("refresh_token"));
        fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn rotates_logs_with_a_fixed_bound_and_discards_only_the_oldest_backup() {
        let root = temp_dir();
        let log = AuditLog::with_limits(&root, 400, 2).unwrap();
        let status = test_status("UP_TO_DATE", None);
        for _ in 0..12 {
            log.append(AuditKind::Heartbeat, Some(&status), 0, true)
                .unwrap();
        }

        let files = fs::read_dir(&root).unwrap().count();
        assert_eq!(files, 3);
        for name in ["meter.jsonl", "meter.1.jsonl", "meter.2.jsonl"] {
            let path = root.join(name);
            assert!(path.is_file(), "missing {}", name);
            assert!(fs::metadata(path).unwrap().len() <= 400);
        }
        fs::remove_dir_all(root).unwrap();
    }
}
