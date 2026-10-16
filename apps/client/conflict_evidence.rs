//! Native-only, short-lived proof of an observed CC Switch session conflict.
//! No paths, secrets or process IDs can be supplied by the renderer.
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::{fs, io::Read, path::Path};

pub(crate) const POLICY: &str = "CONFIRMED_SESSION_CONFLICT_V1";
pub(crate) const FILE_NAME: &str = "ccswitch-conflict-evidence.json";
pub(crate) const MAX_AGE_SECONDS: u64 = 15;

#[derive(Clone, Deserialize, Serialize, Eq, PartialEq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub(crate) struct ConflictSnapshot {
    config_sha256: String,
    auth_sha256: String,
    cc_db_sha256: String,
    cc_wal_sha256: Option<String>,
}

impl ConflictSnapshot {
    pub(crate) fn capture(codex_home: &Path) -> Option<Self> {
        let cc_home = codex_home.parent()?.join(".cc-switch");
        Some(Self {
            config_sha256: fingerprint(&codex_home.join("config.toml"), 4 * 1024 * 1024).ok()??,
            auth_sha256: fingerprint(&codex_home.join("auth.json"), 4 * 1024 * 1024).ok()??,
            cc_db_sha256: fingerprint(&cc_home.join("cc-switch.db"), 32 * 1024 * 1024).ok()??,
            cc_wal_sha256: fingerprint(&cc_home.join("cc-switch.db-wal"), 32 * 1024 * 1024).ok()?,
        })
    }

    pub(crate) fn matches_current(&self, codex_home: &Path) -> bool {
        Self::capture(codex_home).as_ref() == Some(self)
    }
}

#[derive(Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub(crate) struct ConflictEvidence {
    pub(crate) policy: String,
    pub(crate) request_id: String,
    pub(crate) lease_id: String,
    pub(crate) desktop_pid: u32,
    pub(crate) desktop_created_at: u64,
    pub(crate) observed_at: u64,
    pub(crate) snapshot: ConflictSnapshot,
}

impl ConflictEvidence {
    pub(crate) fn fresh_for(&self, lease_id: &str, pid: u32, created_at: u64, now: u64) -> bool {
        self.policy == POLICY
            && uuid::Uuid::parse_str(&self.request_id).is_ok_and(|id| !id.is_nil())
            && self.lease_id == lease_id
            && self.desktop_pid == pid
            && self.desktop_created_at == created_at
            && self.observed_at != 0
            && self.observed_at <= now
            && now.saturating_sub(self.observed_at) <= MAX_AGE_SECONDS
    }
}

/// Reject reparse points on the full path, and bound reads on the open handle.
pub(crate) fn read_regular(path: &Path, limit: u64) -> Result<Option<Vec<u8>>, ()> {
    if !path.is_absolute() {
        return Err(());
    }
    for ancestor in path.ancestors() {
        match fs::symlink_metadata(ancestor) {
            Ok(metadata) => {
                if metadata.file_type().is_symlink() {
                    return Err(());
                }
                #[cfg(windows)]
                {
                    use std::os::windows::fs::MetadataExt;
                    if metadata.file_attributes() & 0x400 != 0 {
                        return Err(());
                    }
                }
            }
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => {}
            Err(_) => return Err(()),
        }
    }
    let mut options = fs::OpenOptions::new();
    options.read(true);
    #[cfg(windows)]
    {
        use std::os::windows::fs::OpenOptionsExt;
        use windows_sys::Win32::Storage::FileSystem::FILE_FLAG_OPEN_REPARSE_POINT;
        options.custom_flags(FILE_FLAG_OPEN_REPARSE_POINT);
    }
    let input = match options.open(path) {
        Ok(input) => input,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(None),
        Err(_) => return Err(()),
    };
    let metadata = input.metadata().map_err(|_| ())?;
    #[cfg(windows)]
    {
        use std::os::windows::fs::MetadataExt;
        if metadata.file_attributes() & 0x400 != 0 {
            return Err(());
        }
    }
    if !metadata.is_file() || metadata.len() > limit {
        return Err(());
    }
    let mut bytes = Vec::new();
    input
        .take(limit + 1)
        .read_to_end(&mut bytes)
        .map_err(|_| ())?;
    if bytes.len() as u64 > limit {
        return Err(());
    }
    Ok(Some(bytes))
}

fn fingerprint(path: &Path, limit: u64) -> Result<Option<String>, ()> {
    Ok(read_regular(path, limit)?.map(|bytes| format!("{:x}", Sha256::digest(bytes))))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn evidence_is_bound_to_exact_lease_process_policy_and_short_observation_window() {
        let proof = ConflictEvidence {
            policy: POLICY.into(),
            request_id: uuid::Uuid::new_v4().to_string(),
            lease_id: "lease-a".into(),
            desktop_pid: 10,
            desktop_created_at: 100,
            observed_at: 500,
            snapshot: ConflictSnapshot {
                config_sha256: "a".into(),
                auth_sha256: "b".into(),
                cc_db_sha256: "c".into(),
                cc_wal_sha256: None,
            },
        };
        assert!(proof.fresh_for("lease-a", 10, 100, 515));
        assert!(!proof.fresh_for("lease-a", 10, 100, 516));
        assert!(!proof.fresh_for("lease-a", 10, 100, 499));
        assert!(!proof.fresh_for("lease-b", 10, 100, 500));
        assert!(!proof.fresh_for("lease-a", 11, 100, 500));
        assert!(!proof.fresh_for("lease-a", 10, 101, 500));
        let mut other = proof.clone();
        other.policy = "legacy".into();
        assert!(!other.fresh_for("lease-a", 10, 100, 500));
    }

    #[cfg(windows)]
    #[test]
    fn evidence_channel_rejects_same_user_writable_forged_directory() {
        let temporary = std::env::temp_dir().join(format!("guard-evidence-{}", uuid::Uuid::new_v4()));
        fs::create_dir(&temporary).unwrap();
        fs::write(temporary.join(FILE_NAME), b"forged native proof").unwrap();
        assert!(ProtectedEvidenceChannel::open_directory(&temporary, &temporary).is_err());
        fs::remove_dir_all(temporary).unwrap();
    }

    #[cfg(windows)]
    #[test]
    fn evidence_path_is_sid_scoped_and_never_uses_a_user_supplied_path() {
        let common = Path::new(r"C:\ProgramData");
        assert_eq!(
            native_security::channel_directory(common, "S-1-5-21-1-2-3-1001").unwrap(),
            common.join("CodexQuotaGuardConflictEvidence").join("S-1-5-21-1-2-3-1001")
        );
        for invalid in ["", "S-1-5-18", "S-1-5-21-1\\escape", "S-1-5-21-1:stream", "S-1-5-21-..", "S-1-5-21-1/escape"] {
            assert!(native_security::channel_directory(common, invalid).is_err());
        }
    }

    #[cfg(windows)]
    #[test]
    fn evidence_acl_rejects_lower_privilege_owners_and_grants() {
        use native_security::{validate_security, AccessGrant, SecuritySummary};
        let summary = SecuritySummary {
            owner: "S-1-5-32-544".into(),
            protected: true,
            grants: vec![AccessGrant { sid: "S-1-5-18".into(), mask: 0x1f01ff, inherit_only: false },
                AccessGrant { sid: "S-1-5-32-544".into(), mask: 0x1f01ff, inherit_only: false }],
        };
        assert!(validate_security(&summary, true).is_ok());
        let mut forged = summary.clone();
        forged.owner = "S-1-5-21-1-2-3-1001".into();
        assert!(validate_security(&forged, true).is_err());
        let mut writable = summary.clone();
        writable.grants.push(AccessGrant { sid: "S-1-5-21-1-2-3-1001".into(), mask: 2, inherit_only: false });
        assert!(validate_security(&writable, true).is_err());
        let mut inherited = summary;
        inherited.protected = false;
        assert!(validate_security(&inherited, true).is_err());
    }

    #[cfg(windows)]
    #[test]
    fn ancestor_acl_cannot_authorize_unprivileged_replacement_of_protected_root() {
        use native_security::{validate_security, AccessGrant, SecuritySummary};
        let mut summary = SecuritySummary {
            owner: "S-1-5-18".into(), protected: false,
            grants: vec![AccessGrant { sid: "S-1-5-32-545".into(), mask: 0x116, inherit_only: false }],
        };
        assert!(validate_security(&summary, false).is_ok());
        for dangerous in [0x40, 0x10000, 0x40000, 0x80000, 0x10000000] {
            summary.grants[0].mask = dangerous;
            assert!(validate_security(&summary, false).is_err());
        }
    }
}
