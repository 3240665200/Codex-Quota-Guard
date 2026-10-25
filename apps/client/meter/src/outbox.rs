use super::{scan, MeterConfig, ScanOutcome, ScanState, UsageEvent};
use serde::{Deserialize, Serialize};
use std::collections::BTreeSet;
use std::fs::{self, File};
use std::io::Write;
use std::path::PathBuf;
use std::sync::Mutex;

pub(super) const MAX_OUTBOX_BYTES: usize = 64 * 1024 * 1024;
const LATE_DIRECT_REVIEW_SOURCE: &str = "QUARANTINED_LEGACY_DIRECT_LATE";

/// One atomic journal owns both the captured records and their scan cursor.
/// Upload acknowledgements remove records only after the server accepted them;
/// a crash before that save repeats the stable event IDs for server deduplication.
#[derive(Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub(super) struct Outbox {
    device_id: uuid::Uuid,
    server_origin: String,
    codex_home: PathBuf,
    #[serde(default)]
    user_id: Option<uuid::Uuid>,
    #[serde(default)]
    quarantined: Vec<QuarantinedUsage>,
    scan_state: ScanState,
    pub(super) pending: Vec<UsageEvent>,
}

#[derive(Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
struct QuarantinedUsage {
    user_id: Option<uuid::Uuid>,
    events: Vec<UsageEvent>,
    #[serde(default)]
    error_code: Option<String>,
}

impl Outbox {
    fn load_current(config: &MeterConfig) -> Result<Self, String> {
        let path = config.state_dir.join("usage-outbox.json");
        if fs::metadata(&path)
            .map_err(|_| "METER_OUTBOX_READ_FAILED")?
            .len()
            > MAX_OUTBOX_BYTES as u64
        {
            return Err("METER_OUTBOX_TOO_LARGE".into());
        }
        let bytes = fs::read(&path).map_err(|_| "METER_OUTBOX_READ_FAILED")?;
        let outbox: Self = serde_json::from_slice(&bytes).map_err(|_| "METER_OUTBOX_INVALID")?;
        outbox.validate_identity(config)?;
        Ok(outbox)
    }

    fn load_or_create(
        config: &MeterConfig,
        previous: &ScanState,
        user_id: Option<uuid::Uuid>,
    ) -> Result<Self, String> {
        let path = config.state_dir.join("usage-outbox.json");
        if path.exists() {
            Self::load_current(config)
        } else {
            Ok(Self {
                device_id: config.device_id,
                server_origin: server_origin(config)?,
                codex_home: config.codex_home.clone(),
                user_id,
                quarantined: Vec::new(),
                scan_state: previous.clone(),
                pending: Vec::new(),
            })
        }
    }

    fn validate_identity(&self, config: &MeterConfig) -> Result<(), String> {
        let origin = server_origin(config)?;
        if self.device_id != config.device_id
            || self.server_origin != origin
            || self.codex_home != config.codex_home
        {
            return Err("METER_OUTBOX_IDENTITY_MISMATCH".into());
        }
        Ok(())
    }

    pub(super) fn server_transition_source(
        &self,
        config: &MeterConfig,
    ) -> Result<(&str, &ScanState), String> {
        // A previous installer may already have changed only the origin. It
        // must never make an unrelated device or profile's journal eligible.
        if self.device_id != config.device_id || self.codex_home != config.codex_home {
            return Err("METER_OUTBOX_IDENTITY_MISMATCH".into());
        }
        Ok((&self.server_origin, &self.scan_state))
    }

    #[cfg(test)]
    pub(super) fn capture(
        config: &MeterConfig,
        previous: &ScanState,
        user_id: Option<uuid::Uuid>,
        mut ensure_allowed: impl FnMut() -> Result<(), String>,
    ) -> Result<Self, String> {
        ensure_allowed()?;
        let mut outbox = Self::load_or_create(config, previous, user_id)?;
        let ScanOutcome { events, next_state } = scan(&config.codex_home, &outbox.scan_state)?;
        outbox.merge_capture(user_id, events, next_state);
        ensure_allowed()?;
        outbox.save(config)?;
        Ok(outbox)
    }

    pub(super) fn capture_with_short_lock(
        config: &MeterConfig,
        previous: &ScanState,
        user_id: Option<uuid::Uuid>,
        journal_lock: &Mutex<()>,
    ) -> Result<Self, String> {
        for _ in 0..3 {
            let snapshot = {
                let _journal_guard = journal_lock
                    .lock()
                    .unwrap_or_else(|poisoned| poisoned.into_inner());
                Self::load_or_create(config, previous, user_id)?
            };
            let ScanOutcome { events, next_state } =
                scan(&config.codex_home, &snapshot.scan_state)?;
            let current = {
                let _journal_guard = journal_lock
                    .lock()
                    .unwrap_or_else(|poisoned| poisoned.into_inner());
                let credential = super::load_credential(&config.state_dir)?;
                if credential.as_ref().is_none_or(|credential| {
                    credential.device_id != config.device_id || credential.user_id != user_id
                }) {
                    return Err("METER_OUTBOX_OWNER_CHANGED".into());
                }
                let mut current = Self::load_or_create(config, previous, user_id)?;
                if current.scan_state != snapshot.scan_state || current.user_id != snapshot.user_id
                {
                    None
                } else {
                    current.merge_capture(user_id, events, next_state);
                    current.save(config)?;
                    Some(current)
                }
            };
            if let Some(current) = current {
                return Ok(current);
            }
        }
        Err("METER_OUTBOX_BUSY".into())
    }

    fn merge_capture(
        &mut self,
        user_id: Option<uuid::Uuid>,
        events: Vec<UsageEvent>,
        next_state: ScanState,
    ) {
        if self.user_id != user_id {
            // Device UUIDs can be reused after deletion of a system user. Keep
            // that owner's records in this same atomic journal, never attach
            // them to a newly authenticated owner or replay their source logs.
            if !self.pending.is_empty() {
                self.quarantined.push(QuarantinedUsage {
                    user_id: self.user_id,
                    events: std::mem::take(&mut self.pending),
                    error_code: None,
                });
            }
            // These bytes arrived since the old cursor but before the new
            // owner was established. Preserve their uncertain attribution.
            if !events.is_empty() {
                self.quarantined.push(QuarantinedUsage {
                    user_id: None,
                    events,
                    error_code: None,
                });
            }
            self.user_id = user_id;
        } else {
            let mut ambiguous_direct = Vec::new();
            let mut identities: BTreeSet<String> = self
                .pending
                .iter()
                .map(|event| event.event_id.clone())
                .collect();
            for event in events {
                if event.source == LATE_DIRECT_REVIEW_SOURCE {
                    ambiguous_direct.push(event);
                    continue;
                }
                if identities.insert(event.event_id.clone()) {
                    self.pending.push(event);
                }
            }
            if !ambiguous_direct.is_empty() {
                self.quarantined.push(QuarantinedUsage {
                    user_id,
                    events: ambiguous_direct,
                    error_code: Some("METER_LEGACY_DIRECT_LATE".into()),
                });
            }
        }
        self.scan_state = next_state;
    }

    pub(super) fn acknowledge(
        &mut self,
        config: &MeterConfig,
        events: &[UsageEvent],
    ) -> Result<(), String> {
        let acknowledged: BTreeSet<_> =
            events.iter().map(|event| event.event_id.as_str()).collect();
        self.pending
            .retain(|event| !acknowledged.contains(event.event_id.as_str()));
        self.save(config)
    }

    pub(super) fn acknowledge_latest(
        config: &MeterConfig,
        expected_user_id: uuid::Uuid,
        events: &[UsageEvent],
    ) -> Result<Self, String> {
        let mut current = Self::load_current(config)?;
        if current.user_id != Some(expected_user_id) {
            return Err("METER_OUTBOX_OWNER_CHANGED".into());
        }
        current.acknowledge(config, events)?;
        Ok(current)
    }

    pub(super) fn quarantine_rejected_latest(
        config: &MeterConfig,
        expected_user_id: uuid::Uuid,
        events: &[UsageEvent],
    ) -> Result<Self, String> {
        let mut current = Self::load_current(config)?;
        if current.user_id != Some(expected_user_id) {
            return Err("METER_OUTBOX_OWNER_CHANGED".into());
        }
        current.quarantine_rejected(config, events)?;
        Ok(current)
    }

    pub(super) fn scan_state(&self) -> &ScanState {
        &self.scan_state
    }

    pub(super) fn pending_count(&self) -> usize {
        self.pending.len()
            + self
                .quarantined
                .iter()
                .map(|batch| batch.events.len())
                .sum::<usize>()
    }

    pub(super) fn quarantine_rejected(
        &mut self,
        config: &MeterConfig,
        events: &[UsageEvent],
    ) -> Result<(), String> {
        self.quarantined.push(QuarantinedUsage {
            user_id: self.user_id,
            events: events.to_vec(),
            error_code: Some("METER_REPORT_REJECTED".into()),
        });
        self.acknowledge(config, events)
    }

    pub(super) fn owner_warning(&self) -> Option<&'static str> {
        if self.quarantined.iter().any(|batch| batch.user_id.is_none()) {
            Some("METER_OUTBOX_OWNER_UNKNOWN")
        } else if self
            .quarantined
            .iter()
            .any(|batch| batch.error_code.is_none())
        {
            Some("METER_OUTBOX_OWNER_MISMATCH")
        } else if self
            .quarantined
            .iter()
            .any(|batch| batch.error_code.as_deref() == Some("METER_LEGACY_DIRECT_LATE"))
        {
            Some("METER_LEGACY_DIRECT_LATE")
        } else if !self.quarantined.is_empty() {
            Some("METER_REPORT_REJECTED")
        } else {
            None
        }
    }

    fn save(&self, config: &MeterConfig) -> Result<(), String> {
        let bytes = serde_json::to_vec(self).map_err(|_| "METER_OUTBOX_WRITE_FAILED")?;
        if bytes.len() > MAX_OUTBOX_BYTES {
            return Err("METER_OUTBOX_TOO_LARGE".into());
        }
        fs::create_dir_all(&config.state_dir).map_err(|_| "METER_OUTBOX_WRITE_FAILED")?;
        let temporary = config
            .state_dir
            .join(format!(".meter-outbox-{}.tmp", uuid::Uuid::new_v4()));
        let result = (|| {
            let mut output =
                File::create_new(&temporary).map_err(|_| "METER_OUTBOX_WRITE_FAILED")?;
            output
                .write_all(&bytes)
                .map_err(|_| "METER_OUTBOX_WRITE_FAILED")?;
            output.sync_all().map_err(|_| "METER_OUTBOX_WRITE_FAILED")?;
            fs::rename(&temporary, config.state_dir.join("usage-outbox.json"))
                .map_err(|_| "METER_OUTBOX_WRITE_FAILED")
        })();
        if result.is_err() {
            let _ = fs::remove_file(&temporary);
        }
        result.map_err(str::to_owned)
    }
}

fn server_origin(config: &MeterConfig) -> Result<String, String> {
    reqwest::Url::parse(&config.server_url)
        .map_err(|_| "METER_SERVER_URL_INVALID".to_owned())
        .map(|url| url.origin().ascii_serialization())
}
