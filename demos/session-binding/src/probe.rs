//! Optional offline, read-only comparison. No network or authentication operations.

use base64::{engine::general_purpose::URL_SAFE_NO_PAD, Engine};
use serde::{Deserialize, Serialize};
use serde_json::Value;
use std::collections::HashSet;
use std::fmt;

const LIMIT: usize = 1_048_576;

#[derive(Debug)]
pub struct ProbeError(&'static str);
impl fmt::Display for ProbeError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(self.0)
    }
}
impl std::error::Error for ProbeError {}

#[derive(Serialize)]
pub struct ProbeReport {
    snapshot_rows: usize,
    codex_rows: usize,
    tokens: Vec<TokenReport>,
    matches: Vec<ClaimMatch>,
    signature_verified: bool,
    account_scope_verified: bool,
    owner_established: bool,
    enforcement_allowed: bool,
    warnings: [&'static str; 3],
}
#[derive(Serialize)]
struct TokenReport {
    token_kind: &'static str,
    status: &'static str,
    string_claims_examined: usize,
    session_claim_names_present: Vec<&'static str>,
}
#[derive(Serialize)]
struct ClaimMatch {
    snapshot_row: usize,
    is_codex: bool,
    token_kind: &'static str,
    claim_kind: &'static str,
}
#[derive(Deserialize)]
struct Snapshot {
    show_session_manager: bool,
    devices: Vec<Device>,
}
#[derive(Deserialize)]
struct Device {
    session_id: Option<String>,
    #[serde(default)]
    app_sessions: Vec<App>,
}
#[derive(Deserialize)]
struct App {
    client_name: String,
}
impl Device {
    fn is_codex(&self) -> bool {
        self.app_sessions
            .iter()
            .any(|a| a.client_name.trim().eq_ignore_ascii_case("Codex"))
    }
}

// Never derive Debug for credential containers, copy tokens to reports, or return
// parser errors (which can include input). JWT decoding is NOT verification.
pub fn compare(auth: &[u8], snapshot: &[u8]) -> Result<ProbeReport, ProbeError> {
    if auth.len() > LIMIT || snapshot.len() > LIMIT {
        return Err(ProbeError("E_INPUT_TOO_LARGE"));
    }
    let auth: Value = serde_json::from_slice(auth).map_err(|_| ProbeError("E_INVALID_AUTH"))?;
    if !auth.is_object() {
        return Err(ProbeError("E_INVALID_AUTH"));
    }
    let snapshot: Snapshot =
        serde_json::from_slice(snapshot).map_err(|_| ProbeError("E_INVALID_SNAPSHOT"))?;
    if !snapshot.show_session_manager || snapshot.devices.len() > 1000 {
        return Err(ProbeError("E_INVALID_SNAPSHOT"));
    }
    let mut ids = HashSet::new();
    for device in &snapshot.devices {
        if let Some(id) = device.session_id.as_deref().filter(|s| !s.is_empty()) {
            if id.trim().is_empty() || id.len() > 256 || id.chars().any(char::is_control) || !ids.insert(id) {
                return Err(ProbeError("E_INVALID_SNAPSHOT"));
            }
        }
    }
    let mut report = ProbeReport {
        snapshot_rows: snapshot.devices.len(),
        codex_rows: snapshot.devices.iter().filter(|d| d.is_codex()).count(),
        tokens: Vec::new(), matches: Vec::new(),
        signature_verified: false, account_scope_verified: false,
        owner_established: false, enforcement_allowed: false,
        warnings: [
            "Exact string equality is only a candidate, not verified identity or proof of ownership.",
            "Snapshot account, freshness and completeness are not authenticated by this offline demo.",
            "No match means no exact claim match in this file/snapshot, not proof that no mapping exists.",
        ],
    };
    for token_kind in ["access_token", "id_token"] {
        let token = auth
            .get("tokens")
            .and_then(|v| v.get(token_kind))
            .and_then(Value::as_str);
        let mut summary = TokenReport {
            token_kind,
            status: "MISSING",
            string_claims_examined: 0,
            session_claim_names_present: Vec::new(),
        };
        if let Some(token) = token.filter(|t| !t.is_empty()) {
            summary.status = "UNREADABLE_OR_OPAQUE";
            if let Some(claims) = decode_claims(token) {
                summary.status = "DECODED_UNVERIFIED";
                walk_claims(&claims, "", 0, &snapshot, &mut summary, &mut report.matches)?;
                summary.session_claim_names_present.sort_unstable();
                summary.session_claim_names_present.dedup();
            }
        }
        report.tokens.push(summary);
    }
    Ok(report)
}

fn decode_claims(token: &str) -> Option<Value> {
    let mut parts = token.split('.');
    let _header = parts.next()?;
    let payload = parts.next()?;
    let _signature = parts.next()?;
    if parts.next().is_some() {
        return None;
    }
    let decoded = URL_SAFE_NO_PAD.decode(payload).ok()?;
    let value: Value = serde_json::from_slice(&decoded).ok()?;
    value.is_object().then_some(value)
}

fn claim_kind(key: &str) -> &'static str {
    match key {
        "sid" => "sid",
        "session_id" => "session_id",
        "sessionId" => "sessionId",
        "session_uuid" => "session_uuid",
        "jti" => "jti",
        _ => "other_string_claim",
    }
}

fn walk_claims(
    value: &Value,
    key: &str,
    depth: usize,
    snapshot: &Snapshot,
    summary: &mut TokenReport,
    matches: &mut Vec<ClaimMatch>,
) -> Result<(), ProbeError> {
    if depth > 16 {
        return Err(ProbeError("E_CLAIMS_TOO_DEEP"));
    }
    match value {
        Value::Object(fields) => {
            for (key, value) in fields {
                if claim_kind(key) != "other_string_claim" {
                    summary.session_claim_names_present.push(claim_kind(key));
                }
                walk_claims(value, key, depth + 1, snapshot, summary, matches)?;
            }
        }
        Value::Array(items) => {
            for item in items {
                walk_claims(item, key, depth + 1, snapshot, summary, matches)?;
            }
        }
        Value::String(text) => {
            summary.string_claims_examined += 1;
            for (row, device) in snapshot.devices.iter().enumerate() {
                if !text.is_empty() && device.session_id.as_deref() == Some(text.as_str()) {
                    if matches.len() >= 1000 {
                        return Err(ProbeError("E_TOO_MANY_MATCHES"));
                    }
                    matches.push(ClaimMatch {
                        snapshot_row: row,
                        is_codex: device.is_codex(),
                        token_kind: summary.token_kind,
                        claim_kind: claim_kind(key),
                    });
                }
            }
        }
        Value::Null | Value::Bool(_) | Value::Number(_) => {}
    }
    Ok(())
}
