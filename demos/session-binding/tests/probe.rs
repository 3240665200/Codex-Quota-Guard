#[path = "../src/probe.rs"]
mod probe;

use base64::{engine::general_purpose::URL_SAFE_NO_PAD, Engine};
use serde_json::{json, Value};

fn auth(claims: Value) -> Vec<u8> {
    let token = format!("e30.{}.fixture", URL_SAFE_NO_PAD.encode(serde_json::to_vec(&claims).unwrap()));
    serde_json::to_vec(&json!({"auth_mode":"chatgpt", "tokens":{"access_token":token,"refresh_token":"NEVER_PRINT_REFRESH"}})).unwrap()
}
fn snapshot() -> Vec<u8> {
    serde_json::to_vec(&json!({"show_session_manager":true,"devices":[
        {"session_id":"exact-session-fixture", "app_sessions":[{"client_name":"Codex"}]},
        {"session_id":"web-session-fixture", "app_sessions":[{"client_name":"ChatGPT Web"}]}
    ]})).unwrap()
}
fn report(auth: &[u8]) -> Value {
    serde_json::to_value(probe::compare(auth, &snapshot()).unwrap()).unwrap()
}
#[test]
fn exact_claim_match_is_evidence_only_and_never_proves_owner() {
    let r = report(&auth(json!({"sid":"exact-session-fixture"})));
    assert_eq!(r["matches"].as_array().unwrap().len(), 1);
    assert_eq!(r["matches"][0]["snapshot_row"], 0);
    assert_eq!(r["matches"][0]["claim_kind"], "sid");
    assert_eq!(r["signature_verified"], false);
    assert_eq!(r["account_scope_verified"], false);
    assert_eq!(r["owner_established"], false);
    assert_eq!(r["enforcement_allowed"], false);
}
#[test]
fn account_id_and_partial_matches_do_not_create_session_matches() {
    let r = report(&auth(json!({"chatgpt_account_id":"unrelated", "sid":"prefix-exact-session-fixture"})));
    assert_eq!(r["matches"], json!([]));
}
#[test]
fn nested_claims_are_compared_without_printing_values_or_unknown_keys() {
    let r = report(&auth(json!({"NEVER_PRINT_KEY":{"unknown":"exact-session-fixture"},"email":"PRIVATE_EMAIL"})));
    assert_eq!(r["matches"].as_array().unwrap().len(), 1);
    let out = r.to_string();
    for secret in ["exact-session-fixture", "NEVER_PRINT_KEY", "PRIVATE_EMAIL", "NEVER_PRINT_REFRESH"] {
        assert!(!out.contains(secret));
    }
}
#[test]
fn opaque_token_is_not_decoded_or_printed() {
    let r = report(br#"{"tokens":{"access_token":"OPAQUE_SECRET","refresh_token":"REFRESH_SECRET"}}"#);
    assert_eq!(r["tokens"][0]["status"], "UNREADABLE_OR_OPAQUE");
    assert!(!r.to_string().contains("SECRET"));
}
#[test]
fn refresh_token_and_root_fields_are_not_used_as_session_evidence() {
    let r = report(br#"{"tokens":{"refresh_token":"exact-session-fixture","account_id":"exact-session-fixture"},"session_id":"exact-session-fixture"}"#);
    assert_eq!(r["matches"], json!([]));
}
#[test]
fn non_codex_match_is_reported_but_not_mislabelled_as_codex() {
    let r = report(&auth(json!({"sid":"web-session-fixture"})));
    assert_eq!(r["matches"][0]["is_codex"], false);
}
#[test]
fn missing_false_and_duplicate_snapshots_are_rejected() {
    for invalid in [json!({}), json!({"show_session_manager":false,"devices":[]}),
        json!({"show_session_manager":true,"devices":[{"session_id":"same"},{"session_id":"same"}]})] {
        assert!(probe::compare(&auth(json!({})), &serde_json::to_vec(&invalid).unwrap()).is_err());
    }
}
#[test]
fn invalid_and_oversized_auth_fail_without_echo() {
    for invalid in [b"PRIVATE_SECRET".to_vec(), vec![b'x'; 1_048_577], b"[]".to_vec()] {
        let error = probe::compare(&invalid, &snapshot()).err().unwrap();
        assert!(!format!("{error:?} {error}").contains("PRIVATE_SECRET"));
    }
}
#[test]
fn absent_tokens_are_explicitly_missing() {
    let r = report(b"{}");
    assert_eq!(r["tokens"][0]["status"], "MISSING");
    assert_eq!(r["tokens"][1]["status"], "MISSING");
}
#[test]
fn id_token_and_arrays_are_supported_and_session_fields_listed_without_values() {
    let mut input: Value = serde_json::from_slice(&auth(json!({"items":[{"session_id":"exact-session-fixture"}]}))).unwrap();
    input["tokens"]["id_token"] = input["tokens"]["access_token"].take();
    let r = report(&serde_json::to_vec(&input).unwrap());
    assert_eq!(r["matches"][0]["token_kind"], "id_token");
    assert_eq!(r["matches"][0]["claim_kind"], "session_id");
    assert!(!r.to_string().contains("exact-session-fixture"));
}
