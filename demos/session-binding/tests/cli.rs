use serde_json::{json, Value};
use std::{fs, path::PathBuf, process::{Command, Output}, sync::atomic::{AtomicUsize, Ordering}};

static SEQUENCE: AtomicUsize = AtomicUsize::new(0);
struct Fixture(PathBuf);
impl Fixture {
    fn new() -> Self {
        let path = std::env::temp_dir().join(format!("qg-session-demo-test-{}-{}", std::process::id(), SEQUENCE.fetch_add(1, Ordering::SeqCst)));
        fs::create_dir(&path).unwrap();
        Self(path)
    }
    fn path(&self, name: &str) -> String { self.0.join(name).to_str().unwrap().to_owned() }
}
impl Drop for Fixture { fn drop(&mut self) { fs::remove_dir_all(&self.0).unwrap(); } }
fn run(args: &[&str]) -> Output { Command::new(env!("CARGO_BIN_EXE_quota-session-binding-demo")).args(args).output().unwrap() }
fn sample() -> Value {
    json!({"schema_version":1,"context":{"user_id":"alice","device_id":"pc-a","profile_id":"p-a","official_account_id":"account-a"},
        "before":{"show_session_manager":true,"devices":[]},
        "after":{"show_session_manager":true,"devices":[{"session_id":"raw-private-session","app_sessions":[{"client_name":"Codex"}]}]},"bindings":[]})
}
#[test]
fn demo_runs_offline_and_discloses_unverified_scope() {
    let r = run(&["demo"]);
    assert!(r.status.success());
    let v: Value = serde_json::from_slice(&r.stdout).unwrap();
    assert_eq!(v["enforcement_allowed"], false);
    assert_eq!(v["account_scope_verified"], false);
    assert!(v["sessions"].as_array().unwrap().iter().any(|s| s["status"] == "RECORDED_BINDING"));
    assert!(v["sessions"].as_array().unwrap().iter().any(|s| s["status"] == "CANDIDATE" && s["owner"].is_null()));
}
#[test]
fn explicit_confirm_creates_new_file_and_refuses_to_overwrite() {
    let fixture = Fixture::new(); let input = fixture.path("case.json"); let out = fixture.path("binding.local.json");
    fs::write(&input, serde_json::to_vec(&sample()).unwrap()).unwrap();
    let analyzed = run(&["analyze", &input]); assert!(analyzed.status.success());
    let report: Value = serde_json::from_slice(&analyzed.stdout).unwrap();
    assert!(!String::from_utf8_lossy(&analyzed.stdout).contains("raw-private-session"));
    let reference = report["sessions"][0]["session_ref"].as_str().unwrap();
    let args = ["confirm", &input, "--session-ref", reference, "--confirmed-by", "operator", "--acknowledge-owner", "--out", &out];
    let result = run(&args); assert!(result.status.success(), "{}", String::from_utf8_lossy(&result.stderr));
    let report: Value = serde_json::from_slice(&run(&["analyze", &out]).stdout).unwrap();
    assert_eq!(report["sessions"][0]["status"], "RECORDED_BINDING");
    assert_eq!(report["sessions"][0]["owner"]["user_id"], "alice");
    let previous = fs::read(&out).unwrap(); assert!(!run(&args).status.success());
    assert_eq!(fs::read(&out).unwrap(), previous);
}
#[test]
fn probe_requires_explicit_consent_and_never_echoes_path_or_payload() {
    let result = run(&["probe", "--auth-file", "PRIVATE_PATH", "--sessions-file", "PRIVATE_PATH"]);
    assert!(!result.status.success());
    assert!(result.stdout.is_empty()); assert!(!String::from_utf8_lossy(&result.stderr).contains("PRIVATE_PATH"));
}
#[test]
fn bounded_file_and_json_errors_are_safe() {
    let fixture = Fixture::new(); let input = fixture.path("secret.json");
    for bytes in [b"TOKEN_SECRET".to_vec(), vec![b'x'; 1_048_577]] {
        fs::write(&input, bytes).unwrap();
        let result = run(&["analyze", &input]); assert!(!result.status.success());
        assert!(result.stdout.is_empty()); assert!(!String::from_utf8_lossy(&result.stderr).contains("TOKEN_SECRET"));
    }
}
#[test]
fn probe_cli_is_read_only() {
    let fixture = Fixture::new(); let auth = fixture.path("auth.local.json"); let snapshot = fixture.path("snapshot.local.json");
    let bytes = br#"{"tokens":{"access_token":"OPAQUE_SECRET","refresh_token":"PRIVATE_REFRESH"}}"#;
    fs::write(&auth, bytes).unwrap(); fs::write(&snapshot, serde_json::to_vec(&sample()["after"]).unwrap()).unwrap();
    let result = run(&["probe","--auth-file",&auth,"--sessions-file",&snapshot,"--allow-read-auth"]);
    assert!(result.status.success()); let r: Value = serde_json::from_slice(&result.stdout).unwrap();
    assert_eq!(r["owner_established"], false); assert_eq!(fs::read(&auth).unwrap(), bytes);
    assert!(!String::from_utf8_lossy(&result.stdout).contains("SECRET"));
}
#[test]
fn help_and_unknown_command() {
    assert!(run(&["--help"]).status.success());
    assert!(!run(&["revoke", "PRIVATE_SESSION"]).status.success());
}
