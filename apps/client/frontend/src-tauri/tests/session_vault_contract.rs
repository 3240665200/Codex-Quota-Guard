#![cfg(windows)]
#[path = "../src/session_vault.rs"]
#[allow(dead_code)]
mod session_vault;

use std::{fs, path::PathBuf, process::Command, sync::Arc, thread};

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

#[test]
fn windows_key_survives_restart_without_a_plaintext_key_file() {
    let fixture = Fixture::new();
    let first = session_vault::load_or_create_key(&fixture.0).unwrap();
    let second = session_vault::load_or_create_key(&fixture.0).unwrap();
    assert_eq!(first.hex().len(), 64);
    assert_eq!(first.hex(), second.hex());
    let encrypted = fs::read(fixture.0.join("key.dpapi")).unwrap();
    assert!(!encrypted
        .windows(64)
        .any(|bytes| bytes == first.hex().as_bytes()));
    assert_ne!(format!("{first:?}"), first.hex());
}

#[test]
fn concurrent_first_launches_obtain_the_same_key_without_overwriting_each_other() {
    let fixture = Fixture::new();
    let path = Arc::new(fixture.0.clone());
    let barrier = Arc::new(std::sync::Barrier::new(4));
    let handles: Vec<_> = (0..4)
        .map(|_| {
            let path = Arc::clone(&path);
            let barrier = Arc::clone(&barrier);
            thread::spawn(move || {
                barrier.wait();
                session_vault::load_or_create_key(&path).unwrap().hex()
            })
        })
        .collect();
    let values: Vec<_> = handles
        .into_iter()
        .map(|handle| handle.join().unwrap())
        .collect();
    assert!(values.iter().all(|value| value == &values[0]));
}

#[test]
fn corrupt_oversized_or_directory_keys_are_not_replaced() {
    let fixture = Fixture::new();
    let key_path = fixture.0.join("key.dpapi");
    for bad in [b"not-a-protected-key".to_vec(), vec![1; 20_000]] {
        fs::write(&key_path, &bad).unwrap();
        assert!(session_vault::load_or_create_key(&fixture.0).is_err());
        assert_eq!(fs::read(&key_path).unwrap(), bad);
    }
    fs::remove_file(&key_path).unwrap();
    fs::create_dir(&key_path).unwrap();
    assert!(session_vault::load_or_create_key(&fixture.0).is_err());
}

#[test]
fn only_the_owned_child_environment_receives_the_key_and_failure_never_uses_inherited_secrets() {
    let fixture = Fixture::new();
    let mut command = Command::new("java");
    command.env(
        "QUOTA_GUARD_SESSION_STORAGE_KEY",
        "untrusted-inherited-value",
    );
    session_vault::configure(&mut command, &fixture.0);
    let env: std::collections::HashMap<_, _> = command.get_envs().collect();
    let get = |name: &str| {
        env.get(std::ffi::OsStr::new(name))
            .unwrap()
            .unwrap()
            .to_string_lossy()
            .into_owned()
    };
    assert_eq!(get("QUOTA_GUARD_SESSION_STORAGE_REQUIRED"), "true");
    assert_eq!(get("QUOTA_GUARD_SESSION_STORAGE_KEY").len(), 64);
    assert_eq!(
        PathBuf::from(get("QUOTA_GUARD_SESSION_STORAGE_DIR")),
        fixture.0.join("secure-session")
    );
    assert_eq!(command.get_args().count(), 0);

    fs::write(fixture.0.join("secure-session/key.dpapi"), b"broken").unwrap();
    let mut rejected = Command::new("java");
    rejected.env("QUOTA_GUARD_SESSION_STORAGE_KEY", "must-not-be-used");
    session_vault::configure(&mut rejected, &fixture.0);
    let entry = rejected
        .get_envs()
        .find(|(key, _)| *key == "QUOTA_GUARD_SESSION_STORAGE_KEY")
        .unwrap();
    assert!(entry.1.map_or(true, |value| value.is_empty()));
}
