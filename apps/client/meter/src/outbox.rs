use super::{baseline_codex_home, scan, MeterConfig, ScanOutcome, ScanState, UsageEvent};
use serde::{Deserialize, Serialize};
use std::collections::BTreeSet;
use std::fs::{self, File};
use std::io::Write;
use std::path::PathBuf;

const MAX_OUTBOX_BYTES: usize = 64 * 1024 * 1024;

/// One atomic journal owns both the captured records and their scan cursor.
/// Upload acknowledgements remove records only after the server accepted them;
/// a crash before that save repeats the stable event IDs for server deduplication.
#[derive(Deserialize, Serialize)]
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

#[derive(Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
struct QuarantinedUsage {
    user_id: Option<uuid::Uuid>,
    events: Vec<UsageEvent>,
}

impl Outbox {
    pub(super) fn capture(
        config: &MeterConfig,
        previous: &ScanState,
        user_id: Option<uuid::Uuid>,
        mut ensure_allowed: impl FnMut() -> Result<(), String>,
    ) -> Result<Self, String> {
        ensure_allowed()?;
        let path = config.state_dir.join("usage-outbox.json");
        let origin = reqwest::Url::parse(&config.server_url)
            .map_err(|_| "METER_SERVER_URL_INVALID")?
            .origin()
            .ascii_serialization();
        let mut outbox = if path.exists() {
            if fs::metadata(&path).map_err(|_| "METER_OUTBOX_READ_FAILED")?.len()
                > MAX_OUTBOX_BYTES as u64
            {
                return Err("METER_OUTBOX_TOO_LARGE".into());
            }
            let bytes = fs::read(&path).map_err(|_| "METER_OUTBOX_READ_FAILED")?;
            let outbox: Self = serde_json::from_slice(&bytes).map_err(|_| "METER_OUTBOX_INVALID")?;
            if outbox.device_id != config.device_id
                || outbox.server_origin != origin
                || outbox.codex_home != config.codex_home
            {
                return Err("METER_OUTBOX_IDENTITY_MISMATCH".into());
            }
            outbox
        } else {
            Self {
                device_id: config.device_id,
                server_origin: origin,
                codex_home: config.codex_home.clone(),
                user_id,
                quarantined: Vec::new(),
                scan_state: previous.clone(),
                pending: Vec::new(),
            }
        };
        if outbox.user_id != user_id {
            // Device UUIDs can be reused after deletion of a system user. Keep
            // that owner's records in this same atomic journal, never attach
            // them to a newly authenticated owner or replay their source logs.
            if !outbox.pending.is_empty() {
                outbox.quarantined.push(QuarantinedUsage {
                    user_id: outbox.user_id,
                    events: std::mem::take(&mut outbox.pending),
                });
            }
            outbox.user_id = user_id;
            outbox.scan_state = baseline_codex_home(&config.codex_home)?;
        }
        let ScanOutcome { events, next_state } = scan(&config.codex_home, &outbox.scan_state)?;
        let mut identities: BTreeSet<String> = outbox.pending.iter().map(|event| event.event_id.clone()).collect();
        for event in events {
            if identities.insert(event.event_id.clone()) {
                outbox.pending.push(event);
            }
        }
        outbox.scan_state = next_state;
        ensure_allowed()?;
        outbox.save(config)?;
        Ok(outbox)
    }

    pub(super) fn acknowledge(&mut self, config: &MeterConfig, events: &[UsageEvent]) -> Result<(), String> {
        let acknowledged: BTreeSet<_> = events.iter().map(|event| event.event_id.as_str()).collect();
        self.pending.retain(|event| !acknowledged.contains(event.event_id.as_str()));
        self.save(config)
    }

    pub(super) fn scan_state(&self) -> &ScanState {
        &self.scan_state
    }

    pub(super) fn pending_count(&self) -> usize {
        self.pending.len() + self.quarantined.iter().map(|batch| batch.events.len()).sum::<usize>()
    }

    pub(super) fn owner_warning(&self) -> Option<&'static str> {
        if self.quarantined.iter().any(|batch| batch.user_id.is_none()) {
            Some("METER_OUTBOX_OWNER_UNKNOWN")
        } else if !self.quarantined.is_empty() {
            Some("METER_OUTBOX_OWNER_MISMATCH")
        } else { None }
    }

    fn save(&self, config: &MeterConfig) -> Result<(), String> {
        let bytes = serde_json::to_vec(self).map_err(|_| "METER_OUTBOX_WRITE_FAILED")?;
        if bytes.len() > MAX_OUTBOX_BYTES {
            return Err("METER_OUTBOX_TOO_LARGE".into());
        }
        fs::create_dir_all(&config.state_dir).map_err(|_| "METER_OUTBOX_WRITE_FAILED")?;
        let temporary = config.state_dir.join(format!(".meter-outbox-{}.tmp", uuid::Uuid::new_v4()));
        let result = (|| {
            let mut output = File::create_new(&temporary).map_err(|_| "METER_OUTBOX_WRITE_FAILED")?;
            output.write_all(&bytes).map_err(|_| "METER_OUTBOX_WRITE_FAILED")?;
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
