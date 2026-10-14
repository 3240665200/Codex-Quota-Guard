#![forbid(unsafe_code)]

//! Offline, conservative comparison of explicitly supplied session snapshots.
//!
//! These editable inputs cannot authenticate an owner or authorize revocation.

use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::collections::{BTreeMap, BTreeSet};
use std::fmt;

const MAX_INPUT_BYTES: usize = 1024 * 1024;
const MAX_ROWS: usize = 1000;
const MAX_ID_BYTES: usize = 256;

#[derive(Clone, Debug, Deserialize, Serialize)]
pub struct Case {
    schema_version: u32,
    context: Context,
    #[serde(default)]
    before: Option<Snapshot>,
    #[serde(default)]
    after: Option<Snapshot>,
    #[serde(default)]
    bindings: Vec<Binding>,
}

#[derive(Clone, Debug, Deserialize, Serialize)]
struct Context {
    user_id: String,
    device_id: String,
    profile_id: String,
    official_account_id: String,
}

impl Context {
    fn owner(&self) -> Owner {
        Owner {
            user_id: self.user_id.clone(),
            device_id: self.device_id.clone(),
            profile_id: self.profile_id.clone(),
        }
    }
}

#[derive(Clone, Debug, Deserialize, Serialize)]
struct Snapshot {
    #[serde(default)]
    show_session_manager: bool,
    #[serde(default)]
    devices: Option<Vec<Device>>,
}

/// Only explicitly needed official fields are retained. Unknown fields are discarded.
#[derive(Clone, Debug, Deserialize, Serialize)]
struct Device {
    #[serde(default)]
    session_id: Option<String>,
    #[serde(default)]
    is_current_device: bool,
    #[serde(default)]
    app_sessions: Vec<AppSession>,
    #[serde(default)]
    platform: String,
}

impl Device {
    fn usable_id(&self) -> Option<&str> {
        self.session_id.as_deref().filter(|id| !id.is_empty())
    }

    fn is_codex(&self) -> bool {
        self.app_sessions
            .iter()
            .any(|app| app.client_name.trim().eq_ignore_ascii_case("Codex"))
    }

    fn app_names(&self) -> Vec<String> {
        self.app_sessions
            .iter()
            .map(|app| app.client_name.trim())
            .filter(|name| !name.is_empty())
            .map(str::to_owned)
            .collect::<BTreeSet<_>>()
            .into_iter()
            .collect()
    }
}

#[derive(Clone, Debug, Deserialize, Serialize)]
struct AppSession {
    #[serde(default)]
    client_name: String,
}

#[derive(Clone, Debug, Deserialize, Serialize)]
struct Binding {
    official_account_id: String,
    session_id: String,
    user_id: String,
    device_id: String,
    profile_id: String,
    evidence: String,
    confirmed_by: String,
}

impl Binding {
    fn owner(&self) -> Owner {
        Owner {
            user_id: self.user_id.clone(),
            device_id: self.device_id.clone(),
            profile_id: self.profile_id.clone(),
        }
    }
}

#[derive(Clone, Debug, Eq, Ord, PartialEq, PartialOrd, Serialize)]
struct Owner {
    user_id: String,
    device_id: String,
    profile_id: String,
}

#[derive(Debug, Serialize)]
pub struct Report {
    context: Context,
    sessions: Vec<SessionReport>,
    stale_bindings: Vec<StaleBinding>,
    candidate_count: usize,
    enforcement_allowed: bool,
    warnings: Vec<&'static str>,
}

#[derive(Debug, Serialize)]
struct SessionReport {
    session_ref: Option<String>,
    status: Status,
    owner: Option<Owner>,
    reason: &'static str,
    app_names: Vec<String>,
    is_current_device: bool,
}

#[derive(Debug, Eq, PartialEq, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum Status {
    RecordedBinding,
    Candidate,
    Unbound,
    Conflict,
    Unavailable,
}

#[derive(Debug, Serialize)]
struct StaleBinding {
    session_ref: String,
    owner: Owner,
    reason: &'static str,
}

/// Safe error variants deliberately carry no input text, paths, or parser diagnostics.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum DemoError {
    InputTooLarge,
    InvalidJson,
    InvalidSchema,
    InvalidSnapshot,
    LimitExceeded,
    InvalidIdentifier,
    DuplicateBeforeSession,
    InvalidBindingEvidence,
    ConfirmationRejected,
}

impl fmt::Display for DemoError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter.write_str(match self {
            Self::InputTooLarge => "E_INPUT_TOO_LARGE",
            Self::InvalidJson => "E_INVALID_JSON",
            Self::InvalidSchema => "E_INVALID_SCHEMA",
            Self::InvalidSnapshot => "E_INVALID_SNAPSHOT",
            Self::LimitExceeded => "E_LIMIT_EXCEEDED",
            Self::InvalidIdentifier => "E_INVALID_IDENTIFIER",
            Self::DuplicateBeforeSession => "E_DUPLICATE_BEFORE_SESSION",
            Self::InvalidBindingEvidence => "E_INVALID_BINDING_EVIDENCE",
            Self::ConfirmationRejected => "E_CONFIRMATION_REJECTED",
        })
    }
}

impl std::error::Error for DemoError {}

pub fn parse_case(bytes: &[u8]) -> Result<Case, DemoError> {
    if bytes.len() > MAX_INPUT_BYTES {
        return Err(DemoError::InputTooLarge);
    }
    let case: Case = serde_json::from_slice(bytes).map_err(|_| DemoError::InvalidJson)?;
    validate_case(&case)?;
    Ok(case)
}

fn validate_identifier(value: &str) -> Result<(), DemoError> {
    if value.trim().is_empty() || value.len() > MAX_ID_BYTES || value.chars().any(char::is_control)
    {
        return Err(DemoError::InvalidIdentifier);
    }
    Ok(())
}

fn snapshot_devices(snapshot: Option<&Snapshot>) -> Result<&[Device], DemoError> {
    let snapshot = snapshot.ok_or(DemoError::InvalidSnapshot)?;
    if !snapshot.show_session_manager {
        return Err(DemoError::InvalidSnapshot);
    }
    snapshot
        .devices
        .as_deref()
        .ok_or(DemoError::InvalidSnapshot)
}

fn validate_devices(devices: &[Device]) -> Result<(), DemoError> {
    if devices.len() > MAX_ROWS {
        return Err(DemoError::LimitExceeded);
    }
    for device in devices {
        if let Some(id) = device.usable_id() {
            validate_identifier(id)?;
        }
        if device.app_sessions.len() > MAX_ROWS {
            return Err(DemoError::LimitExceeded);
        }
        // Bound retained display metadata as well as identifiers.
        for value in std::iter::once(device.platform.as_str()).chain(
            device
                .app_sessions
                .iter()
                .map(|app| app.client_name.as_str()),
        ) {
            if value.len() > MAX_ID_BYTES || value.chars().any(char::is_control) {
                return Err(DemoError::InvalidIdentifier);
            }
        }
    }
    Ok(())
}

fn validate_case(case: &Case) -> Result<(), DemoError> {
    if case.schema_version != 1 {
        return Err(DemoError::InvalidSchema);
    }
    for id in [
        &case.context.user_id,
        &case.context.device_id,
        &case.context.profile_id,
        &case.context.official_account_id,
    ] {
        validate_identifier(id)?;
    }
    let before = snapshot_devices(case.before.as_ref())?;
    let after = snapshot_devices(case.after.as_ref())?;
    validate_devices(before)?;
    validate_devices(after)?;
    let mut before_ids = BTreeSet::new();
    for id in before.iter().filter_map(Device::usable_id) {
        if !before_ids.insert(id) {
            return Err(DemoError::DuplicateBeforeSession);
        }
    }
    if case.bindings.len() > MAX_ROWS {
        return Err(DemoError::LimitExceeded);
    }
    for binding in &case.bindings {
        for id in [
            &binding.official_account_id,
            &binding.session_id,
            &binding.user_id,
            &binding.device_id,
            &binding.profile_id,
            &binding.confirmed_by,
        ] {
            validate_identifier(id)?;
        }
        if binding.evidence != "operator_confirmed" {
            return Err(DemoError::InvalidBindingEvidence);
        }
    }
    Ok(())
}

fn session_ref(account_id: &str, session_id: &str) -> String {
    let mut digest = Sha256::new();
    digest.update(account_id.as_bytes());
    // IDs cannot contain controls, making NUL an unambiguous separator.
    digest.update([0]);
    digest.update(session_id.as_bytes());
    format!("{:x}", digest.finalize())
}

pub fn analyze(case: &Case) -> Result<Report, DemoError> {
    validate_case(case)?;
    let before = snapshot_devices(case.before.as_ref())?;
    let after = snapshot_devices(case.after.as_ref())?;
    let account_id = &case.context.official_account_id;
    let before_ids: BTreeSet<_> = before.iter().filter_map(Device::usable_id).collect();
    let mut after_counts = BTreeMap::<&str, usize>::new();
    for id in after.iter().filter_map(Device::usable_id) {
        *after_counts.entry(id).or_default() += 1;
    }
    let mut owners_by_id = BTreeMap::<&str, BTreeSet<Owner>>::new();
    for binding in &case.bindings {
        if &binding.official_account_id == account_id {
            owners_by_id
                .entry(&binding.session_id)
                .or_default()
                .insert(binding.owner());
        }
    }

    let mut sessions = Vec::with_capacity(after.len());
    for device in after {
        let (status, owner, reason) = match device.usable_id() {
            None => (Status::Unavailable, None, "MISSING_SESSION_ID"),
            Some(id) if after_counts.get(id).copied().unwrap_or(0) > 1 => {
                (Status::Conflict, None, "DUPLICATE_AFTER_SESSION_ID")
            }
            Some(id) => match owners_by_id.get(id) {
                Some(owners) if owners.len() > 1 => {
                    (Status::Conflict, None, "MULTIPLE_DISTINCT_RECORDED_OWNERS")
                }
                Some(owners) => (
                    Status::RecordedBinding,
                    owners.first().cloned(),
                    "OPERATOR_RECORD_NOT_IDENTITY_VERIFICATION",
                ),
                None if !device.is_codex() => {
                    (Status::Unbound, None, "NOT_AN_EXPLICIT_CODEX_CLIENT")
                }
                None if before_ids.contains(id) => {
                    (Status::Unbound, None, "EXISTING_SESSION_WITHOUT_BINDING")
                }
                None => (
                    Status::Candidate,
                    None,
                    "NEW_CODEX_SESSION_IS_ONLY_A_CANDIDATE",
                ),
            },
        };
        sessions.push(SessionReport {
            session_ref: device.usable_id().map(|id| session_ref(account_id, id)),
            status,
            owner,
            reason,
            app_names: device.app_names(),
            is_current_device: device.is_current_device,
        });
    }

    let mut stale_bindings = Vec::new();
    for (id, owners) in owners_by_id {
        if !after_counts.contains_key(id) {
            for owner in owners {
                stale_bindings.push(StaleBinding {
                    session_ref: session_ref(account_id, id),
                    owner,
                    reason: "ABSENT_FROM_AFTER_SNAPSHOT_NOT_PROOF_OF_REVOCATION",
                });
            }
        }
    }
    let candidate_count = sessions
        .iter()
        .filter(|row| row.status == Status::Candidate)
        .count();
    Ok(Report {
        context: case.context.clone(),
        sessions,
        stale_bindings,
        candidate_count,
        enforcement_allowed: false,
        warnings: vec![
            "OFFLINE_EDITABLE_INPUT_NOT_IDENTITY_VERIFICATION",
            "OPERATOR_RECORDS_ARE_NOT_CRYPTOGRAPHIC_AUTHENTICATION",
            "AUTOMATIC_ENFORCEMENT_DISABLED",
        ],
    })
}

/// Append an explicit operator record. This does not authenticate the official owner.
pub fn confirm_owner(case: &Case, reference: &str, confirmed_by: &str) -> Result<Case, DemoError> {
    validate_identifier(confirmed_by)?;
    let report = analyze(case)?;
    let after = snapshot_devices(case.after.as_ref())?;
    let matches: Vec<_> = report
        .sessions
        .iter()
        .zip(after)
        .filter(|(row, _)| row.session_ref.as_deref() == Some(reference))
        .collect();
    let [(row, device)] = matches.as_slice() else {
        return Err(DemoError::ConfirmationRejected);
    };
    if !device.is_codex() {
        return Err(DemoError::ConfirmationRejected);
    }
    match row.status {
        Status::RecordedBinding if row.owner.as_ref() == Some(&case.context.owner()) => {
            return Ok(case.clone());
        }
        Status::Candidate | Status::Unbound => {}
        Status::RecordedBinding | Status::Conflict | Status::Unavailable => {
            return Err(DemoError::ConfirmationRejected);
        }
    }
    if case.bindings.len() >= MAX_ROWS {
        return Err(DemoError::LimitExceeded);
    }
    let id = device.usable_id().ok_or(DemoError::ConfirmationRejected)?;
    let mut confirmed = case.clone();
    confirmed.bindings.push(Binding {
        official_account_id: case.context.official_account_id.clone(),
        session_id: id.to_owned(),
        user_id: case.context.user_id.clone(),
        device_id: case.context.device_id.clone(),
        profile_id: case.context.profile_id.clone(),
        evidence: "operator_confirmed".to_owned(),
        confirmed_by: confirmed_by.to_owned(),
    });
    Ok(confirmed)
}
