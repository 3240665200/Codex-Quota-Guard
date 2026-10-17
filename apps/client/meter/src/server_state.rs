use super::{baseline_codex_home, outbox, scan_with_boundary, MeterConfig, ScanState, UsageEvent};
use serde::Serialize;
use std::fs::{self, File, Metadata};
use std::io::{Read, Write};
use std::path::{Component, Path, PathBuf};

const MAX_CONFIG_BYTES: usize = 8192;
const MAX_STATE_BYTES: usize = 16 * 1024 * 1024;
const PREPARE_PATH_UNSAFE: &str = "METER_SERVER_STATE_PATH_UNSAFE";
const PREPARE_READ_FAILED: &str = "METER_SERVER_STATE_READ_FAILED";
const PREPARE_WRITE_FAILED: &str = "METER_SERVER_STATE_WRITE_FAILED";

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct UnknownUsage<'a> {
    schema_version: u8,
    // The gap after the old cursor is never attributed to either owner.
    user_id: Option<uuid::Uuid>,
    server_origin: Option<&'a str>,
    device_id: uuid::Uuid,
    codex_home: &'a Path,
    events: &'a [UsageEvent],
    incomplete_records: &'a [IncompleteRecord],
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub(super) struct IncompleteRecord {
    pub(super) file: String,
    pub(super) offset: u64,
    pub(super) bytes: Vec<u8>,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct PreparedState<'a> {
    schema_version: u8,
    prepared_at_epoch_seconds: u64,
    source_state_dir: &'a Path,
    source_server_origin: Option<&'a str>,
    // This is configuration evidence only when no journal records an origin.
    source_config_server_origin: &'a str,
    device_id: uuid::Uuid,
    codex_home: &'a Path,
    archived_outbox: bool,
    unknown_events: usize,
    credential_copied: bool,
}

/// Prepare a new server context without changing the source state or making
/// network requests. The installer must stop the service first and protect the
/// destination parent directory. It switches stateDir only after this succeeds.
pub fn prepare_server_state(config_path: &Path, new_state_dir: &Path) -> Result<(), String> {
    check_path(config_path)?;
    check_path(new_state_dir)?;
    let parent = new_state_dir.parent().ok_or(PREPARE_PATH_UNSAFE)?;
    if !fs::metadata(parent).is_ok_and(|metadata| metadata.is_dir()) {
        return Err(PREPARE_PATH_UNSAFE.into());
    }
    match fs::symlink_metadata(new_state_dir) {
        Ok(_) => return Err("METER_SERVER_STATE_TARGET_EXISTS".into()),
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => {}
        Err(_) => return Err(PREPARE_PATH_UNSAFE.into()),
    }

    let config_bytes =
        read_bounded(config_path, MAX_CONFIG_BYTES)?.ok_or("METER_CONFIG_UNAVAILABLE")?;
    let config: MeterConfig =
        serde_json::from_slice(&config_bytes).map_err(|_| "METER_CONFIG_INVALID")?;
    config.validate()?;
    check_path(&config.state_dir)?;
    check_path(&config.codex_home)?;
    check_path(&config.codex_home.join("sessions"))?;
    let outbox_path = config.state_dir.join("usage-outbox.json");
    let state_path = config.state_dir.join("scan-state.json");
    let outbox_bytes = read_bounded(&outbox_path, outbox::MAX_OUTBOX_BYTES)?;
    let state_bytes = read_bounded(&state_path, MAX_STATE_BYTES)?;
    let journal = outbox_bytes
        .as_ref()
        .map(|bytes| serde_json::from_slice::<outbox::Outbox>(bytes))
        .transpose()
        .map_err(|_| "METER_OUTBOX_INVALID")?;
    let config_origin = reqwest::Url::parse(&config.server_url)
        .map_err(|_| "METER_SERVER_URL_INVALID")?
        .origin()
        .ascii_serialization();
    let (source_origin, cursor) = if let Some(journal) = &journal {
        let (origin, cursor) = journal.server_transition_source(&config)?;
        (Some(origin), Some(cursor.clone()))
    } else {
        let cursor = state_bytes
            .as_ref()
            .map(|bytes| serde_json::from_slice::<ScanState>(bytes))
            .transpose()
            .map_err(|_| "METER_STATE_INVALID")?;
        (None, cursor)
    };
    let mut incomplete_records = Vec::new();
    let captured = if let Some(cursor) = cursor {
        scan_with_boundary(&config.codex_home, &cursor, Some(&mut incomplete_records))?
    } else {
        // With no trustworthy cursor, existing logs have unknown ownership.
        super::ScanOutcome {
            events: Vec::new(),
            next_state: baseline_codex_home(&config.codex_home)?,
        }
    };
    // Use the same scan's completed cursor. A second baseline pass could skip
    // records appended between capture and baseline without archiving them.
    let baseline = serde_json::to_vec(&captured.next_state).map_err(|_| PREPARE_WRITE_FAILED)?;
    let unknown = serde_json::to_vec(&UnknownUsage {
        schema_version: 1,
        user_id: None,
        server_origin: source_origin,
        device_id: config.device_id,
        codex_home: &config.codex_home,
        events: &captured.events,
        incomplete_records: &incomplete_records,
    })
    .map_err(|_| PREPARE_WRITE_FAILED)?;
    if baseline.len() > MAX_STATE_BYTES || unknown.len() > outbox::MAX_OUTBOX_BYTES {
        return Err("METER_SERVER_STATE_TOO_LARGE".into());
    }
    // Fail closed if another writer changed the old context during capture.
    if read_bounded(config_path, MAX_CONFIG_BYTES)?.as_ref() != Some(&config_bytes)
        || read_bounded(&outbox_path, outbox::MAX_OUTBOX_BYTES)? != outbox_bytes
        || read_bounded(&state_path, MAX_STATE_BYTES)? != state_bytes
    {
        return Err("METER_SERVER_STATE_SOURCE_CHANGED".into());
    }

    let mut destination = CreatedState::new(new_state_dir)?;
    if let Some(bytes) = &outbox_bytes {
        destination.write("archived-outbox.json", bytes)?;
    }
    if let Some(bytes) = &state_bytes {
        destination.write("archived-scan-state.json", bytes)?;
    }
    destination.write("unknown-usage.json", &unknown)?;
    destination.write("scan-state.json", &baseline)?;
    // Written last: installers may inspect this marker after an interrupted call.
    let metadata = serde_json::to_vec(&PreparedState {
        schema_version: 1,
        prepared_at_epoch_seconds: super::epoch_seconds(),
        source_state_dir: &config.state_dir,
        source_server_origin: source_origin,
        source_config_server_origin: &config_origin,
        device_id: config.device_id,
        codex_home: &config.codex_home,
        archived_outbox: outbox_bytes.is_some(),
        unknown_events: captured.events.len(),
        credential_copied: false,
    })
    .map_err(|_| PREPARE_WRITE_FAILED)?;
    destination.write("server-state-prepared.json", &metadata)?;
    destination.complete = true;
    Ok(())
}

fn is_reparse(metadata: &Metadata) -> bool {
    if metadata.file_type().is_symlink() {
        return true;
    }
    #[cfg(windows)]
    {
        use std::os::windows::fs::MetadataExt;
        metadata.file_attributes() & 0x400 != 0
    }
    #[cfg(not(windows))]
    false
}

pub(super) fn check_path(path: &Path) -> Result<(), String> {
    if !path.is_absolute()
        || path
            .components()
            .any(|part| matches!(part, Component::ParentDir))
    {
        return Err(PREPARE_PATH_UNSAFE.into());
    }
    for ancestor in path.ancestors() {
        match fs::symlink_metadata(ancestor) {
            Ok(metadata) if is_reparse(&metadata) => return Err(PREPARE_PATH_UNSAFE.into()),
            Ok(_) => {}
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => {}
            Err(_) => return Err(PREPARE_PATH_UNSAFE.into()),
        }
    }
    Ok(())
}

fn read_bounded(path: &Path, maximum: usize) -> Result<Option<Vec<u8>>, String> {
    check_path(path)?;
    let metadata = match fs::symlink_metadata(path) {
        Ok(metadata) => metadata,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(None),
        Err(_) => return Err(PREPARE_READ_FAILED.into()),
    };
    if !metadata.is_file() || metadata.len() > maximum as u64 {
        return Err(PREPARE_READ_FAILED.into());
    }
    let mut bytes = Vec::new();
    File::open(path)
        .map_err(|_| PREPARE_READ_FAILED)?
        .take(maximum as u64 + 1)
        .read_to_end(&mut bytes)
        .map_err(|_| PREPARE_READ_FAILED)?;
    if bytes.len() > maximum {
        return Err(PREPARE_READ_FAILED.into());
    }
    Ok(Some(bytes))
}

struct CreatedState {
    path: PathBuf,
    files: Vec<PathBuf>,
    complete: bool,
}

impl CreatedState {
    fn new(path: &Path) -> Result<Self, String> {
        check_path(path)?;
        fs::create_dir(path).map_err(|_| PREPARE_WRITE_FAILED)?;
        Ok(Self {
            path: path.to_owned(),
            files: Vec::new(),
            complete: false,
        })
    }

    fn write(&mut self, name: &str, bytes: &[u8]) -> Result<(), String> {
        check_path(&self.path)?;
        let path = self.path.join(name);
        let mut file = File::create_new(&path).map_err(|_| PREPARE_WRITE_FAILED)?;
        self.files.push(path);
        file.write_all(bytes).map_err(|_| PREPARE_WRITE_FAILED)?;
        file.sync_all().map_err(|_| PREPARE_WRITE_FAILED.into())
    }
}

impl Drop for CreatedState {
    fn drop(&mut self) {
        if self.complete
            || !fs::symlink_metadata(&self.path)
                .is_ok_and(|metadata| metadata.is_dir() && !is_reparse(&metadata))
        {
            return;
        }
        for path in &self.files {
            let _ = fs::remove_file(path);
        }
        // No recursive deletion: leave anything this call did not create alone.
        let _ = fs::remove_dir(&self.path);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn failed_preparation_cleans_only_files_created_by_that_call() {
        let target = std::env::temp_dir().join(format!(
            "meter-server-state-cleanup-test-{}",
            uuid::Uuid::new_v4()
        ));
        let mut created = CreatedState::new(&target).unwrap();
        created.write("owned.json", b"owned").unwrap();
        fs::write(target.join("external.json"), b"external-must-survive").unwrap();
        assert!(created.write("external.json", b"replacement").is_err());
        drop(created);
        assert!(!target.join("owned.json").exists());
        assert_eq!(
            fs::read(target.join("external.json")).unwrap(),
            b"external-must-survive"
        );
        fs::remove_file(target.join("external.json")).unwrap();
        fs::remove_dir(target).unwrap();
    }
}
