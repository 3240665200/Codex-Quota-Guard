#![cfg(windows)]

use std::{
    fs,
    path::{Path, PathBuf},
    time::{SystemTime, UNIX_EPOCH},
};
use zeroize::Zeroize;

mod server_config {
    pub struct ServerConfig {
        pub url: String,
    }

    pub fn load(_app: &tauri::AppHandle) -> ServerConfig {
        ServerConfig {
            url: "https://meter.example".into(),
        }
    }
}

fn unix_seconds() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap()
        .as_secs()
}

#[path = "../src/session_vault.rs"]
#[allow(dead_code)]
mod session_vault;

struct Fixture(PathBuf);

impl Fixture {
    fn new() -> Self {
        let path =
            std::env::temp_dir().join(format!("cqg-session-vault-test-{}", uuid::Uuid::new_v4()));
        fs::create_dir(&path).unwrap();
        Self(path)
    }
}

impl Drop for Fixture {
    fn drop(&mut self) {
        let temp = std::env::temp_dir().canonicalize().unwrap();
        if let Ok(path) = self.0.canonicalize() {
            if path.starts_with(temp)
                && path
                    .file_name()
                    .unwrap()
                    .to_string_lossy()
                    .starts_with("cqg-session-vault-test-")
            {
                let _ = fs::remove_dir_all(path);
            }
        }
    }
}

fn protected_session(token: &str) -> Vec<u8> {
    let value = serde_json::json!({
        "serverOrigin": "https://meter.example",
        "refreshToken": token,
        "userId": uuid::Uuid::new_v4(),
        "loginAtEpochSeconds": 1_800_000_000u64,
        "expiresAtEpochSeconds": 1_800_604_800u64
    });
    serde_json::to_vec(&value).unwrap()
}

fn round_trip(path: &Path, expected: &[u8]) {
    let encrypted = session_vault::read_protected(path).unwrap().unwrap();
    let mut plaintext = session_vault::unprotect(&encrypted).unwrap();
    assert_eq!(plaintext, expected);
    plaintext.zeroize();
}

#[test]
fn first_save_creates_missing_secure_directory_and_overwrite_rotates_the_value() {
    let fixture = Fixture::new();
    let directory = fixture.0.join("secure-session");
    let path = directory.join("session-vault.dpapi");
    session_vault::prepare_secure_directory(&directory).unwrap();

    let first = protected_session("refresh-token-private-canary-one");
    let encrypted = session_vault::protect(&first).unwrap();
    assert!(!encrypted
        .windows(b"refresh-token-private-canary-one".len())
        .any(|window| window == b"refresh-token-private-canary-one"));
    session_vault::write_atomically(&path, &encrypted).unwrap();
    round_trip(&path, &first);

    let second = protected_session("refresh-token-private-canary-two");
    let encrypted = session_vault::protect(&second).unwrap();
    session_vault::write_atomically(&path, &encrypted).unwrap();
    round_trip(&path, &second);
    assert_eq!(fs::read_dir(directory).unwrap().count(), 1);
    assert!(!fs::read(&path)
        .unwrap()
        .windows(b"refresh-token-private-canary-two".len())
        .any(|window| window == b"refresh-token-private-canary-two"));
}

#[test]
fn corrupted_and_oversized_vault_files_are_not_loaded_or_replaced() {
    let fixture = Fixture::new();
    let directory = fixture.0.join("secure-session");
    session_vault::prepare_secure_directory(&directory).unwrap();
    let path = directory.join("session-vault.dpapi");
    for bytes in [b"not-dpapi".to_vec(), vec![7; 20_000]] {
        fs::write(&path, &bytes).unwrap();
        if bytes.len() <= 16 * 1024 {
            let encrypted = session_vault::read_protected(&path).unwrap().unwrap();
            assert!(session_vault::unprotect(&encrypted).is_err());
        } else {
            assert!(session_vault::read_protected(&path).unwrap().is_none());
        }
        assert_eq!(fs::read(&path).unwrap(), bytes);
    }
}

#[test]
fn linked_app_data_ancestors_cannot_redirect_vault_creation() {
    use std::os::windows::process::CommandExt;
    let fixture = Fixture::new();
    let target = fixture.0.join("outside-app-data");
    let linked = fixture.0.join("linked-app-data");
    fs::create_dir(&target).unwrap();
    let status = std::process::Command::new("powershell.exe")
        .args([
            "-NoProfile",
            "-NonInteractive",
            "-Command",
            "New-Item -ItemType Junction -Path $env:CQG_TEST_LINK -Target $env:CQG_TEST_TARGET -ErrorAction Stop | Out-Null",
        ])
        .env("CQG_TEST_LINK", &linked)
        .env("CQG_TEST_TARGET", &target)
        .creation_flags(0x08000000)
        .status()
        .unwrap();
    assert!(status.success());
    let result = session_vault::prepare_secure_directory(&linked.join("secure-session"));
    fs::remove_dir(&linked).unwrap();
    assert!(result.is_err());
    assert!(!target.join("secure-session").exists());
}

#[test]
fn a_second_desktop_process_cannot_acquire_the_refresh_token_lock() {
    if let Ok(first) = session_vault::acquire_process_lock() {
        assert!(session_vault::acquire_process_lock().is_err());
        drop(first);
    }
}
