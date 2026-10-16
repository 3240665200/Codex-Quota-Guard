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

#[cfg(windows)]
pub(crate) use native_security::ProtectedEvidenceChannel;

/// This is a privilege boundary, not a secret. Only elevated administrators and
/// SYSTEM may publish evidence. Keep all directory handles pinned through each
/// read/write, so an unprivileged junction/rename cannot redirect a checked path.
#[cfg(windows)]
mod native_security {
    use super::*;
    use std::{
        ffi::OsString,
        fs::{File, OpenOptions},
        mem::size_of,
        os::windows::{
            ffi::{OsStrExt, OsStringExt},
            fs::{MetadataExt, OpenOptionsExt},
            io::{AsRawHandle, FromRawHandle},
        },
        path::{Component, PathBuf},
        ptr,
    };
    use windows_sys::Win32::{
        Foundation::{CloseHandle, LocalFree, INVALID_HANDLE_VALUE},
        Security::{
            Authorization::{
                ConvertSidToStringSidW, ConvertStringSecurityDescriptorToSecurityDescriptorW,
                GetSecurityInfo, SE_FILE_OBJECT,
            },
            GetAce, GetSecurityDescriptorControl, GetTokenInformation, IsValidSid, TokenUser,
            ACCESS_ALLOWED_ACE, ACE_HEADER, ACL, DACL_SECURITY_INFORMATION, INHERIT_ONLY_ACE,
            OWNER_SECURITY_INFORMATION, PSECURITY_DESCRIPTOR, PSID, SECURITY_ATTRIBUTES,
            SE_DACL_PROTECTED, TOKEN_QUERY, TOKEN_USER,
        },
        Storage::FileSystem::{
            CreateFileW, GetFileInformationByHandle, BY_HANDLE_FILE_INFORMATION, CREATE_NEW,
            FILE_FLAG_BACKUP_SEMANTICS, FILE_FLAG_OPEN_REPARSE_POINT, FILE_SHARE_READ,
        },
        System::{
            Com::CoTaskMemFree,
            Threading::{GetCurrentProcess, OpenProcessToken},
        },
        UI::Shell::{FOLDERID_ProgramData, SHGetKnownFolderPath},
    };

    const SYSTEM: &str = "S-1-5-18";
    const ADMINS: &str = "S-1-5-32-544";
    const TRUSTED_INSTALLER: &str =
        "S-1-5-80-956008885-3418522649-1831038044-1853292631-2271478464";
    const ROOT_NAME: &str = "CodexQuotaGuardConflictEvidence";
    // Ancestors may allow adding children (the Windows ProgramData default),
    // but cannot allow deleting/replacing existing children or changing ACLs.
    const REPLACE_RIGHTS: u32 = 0x40 | 0x10000 | 0x40000 | 0x80000 | 0x10000000;

    #[derive(Clone)]
    pub(super) struct AccessGrant {
        pub(super) sid: String,
        pub(super) mask: u32,
        pub(super) inherit_only: bool,
    }

    #[derive(Clone)]
    pub(super) struct SecuritySummary {
        pub(super) owner: String,
        pub(super) protected: bool,
        pub(super) grants: Vec<AccessGrant>,
    }

    fn privileged(sid: &str) -> bool {
        matches!(sid, SYSTEM | ADMINS)
    }

    fn validate_private(summary: &SecuritySummary, directory: bool) -> Result<(), ()> {
        if !privileged(&summary.owner)
            || (directory && !summary.protected)
            || summary.grants.is_empty()
            || summary.grants.iter().any(|grant| !privileged(&grant.sid))
        {
            return Err(());
        }
        Ok(())
    }

    pub(super) fn validate_security(summary: &SecuritySummary, private: bool) -> Result<(), ()> {
        if private {
            return validate_private(summary, true);
        }
        if !privileged(&summary.owner) && summary.owner != TRUSTED_INSTALLER {
            return Err(());
        }
        if summary.grants.iter().any(|grant| {
            !grant.inherit_only
                && !privileged(&grant.sid)
                && grant.sid != TRUSTED_INSTALLER
                && grant.mask & REPLACE_RIGHTS != 0
        }) {
            return Err(());
        }
        Ok(())
    }

    pub(super) fn channel_directory(common: &Path, sid: &str) -> Result<PathBuf, ()> {
        // Account SIDs only, with no filesystem metacharacters or aliases.
        if !common.is_absolute()
            || sid.len() > 184
            || !sid.starts_with("S-1-5-21-")
            || sid
                .split('-')
                .skip(4)
                .any(|part| part.is_empty() || !part.bytes().all(|c| c.is_ascii_digit()))
        {
            return Err(());
        }
        Ok(common.join(ROOT_NAME).join(sid))
    }

    fn common_data() -> Result<PathBuf, ()> {
        let mut allocated = ptr::null_mut();
        // SAFETY: Known Folder is a machine path from Windows, not environment
        // variables a lower-privilege launcher could substitute.
        if unsafe {
            SHGetKnownFolderPath(&FOLDERID_ProgramData, 0, ptr::null_mut(), &mut allocated)
        } < 0
        {
            return Err(());
        }
        let mut length = 0;
        // SAFETY: Windows returned a NUL-terminated CoTaskMem allocation.
        while length < 32768 && unsafe { *allocated.add(length) } != 0 {
            length += 1;
        }
        let result = if length == 32768 {
            Err(())
        } else {
            // SAFETY: Bounded by the terminator in the allocation above.
            Ok(PathBuf::from(OsString::from_wide(unsafe {
                std::slice::from_raw_parts(allocated, length)
            })))
        };
        // SAFETY: SHGetKnownFolderPath allocated this buffer.
        unsafe { CoTaskMemFree(allocated.cast()) };
        result
    }

    fn sid_text(sid: PSID) -> Result<String, ()> {
        // SAFETY: Callers pass a SID within a Windows-owned security buffer.
        if sid.is_null() || unsafe { IsValidSid(sid) } == 0 {
            return Err(());
        }
        let mut text = ptr::null_mut();
        // SAFETY: Valid SID; Windows allocates the output string.
        if unsafe { ConvertSidToStringSidW(sid, &mut text) } == 0 {
            return Err(());
        }
        let mut length = 0;
        // SAFETY: The output is a NUL-terminated LocalAlloc string.
        while length < 256 && unsafe { *text.add(length) } != 0 {
            length += 1;
        }
        let result = if length == 256 {
            Err(())
        } else {
            // SAFETY: length was bounded by the terminator above.
            Ok(String::from_utf16_lossy(unsafe {
                std::slice::from_raw_parts(text, length)
            }))
        };
        // SAFETY: ConvertSidToStringSidW allocated this buffer.
        unsafe { LocalFree(text.cast()) };
        result
    }

    fn current_sid() -> Result<String, ()> {
        let mut token = ptr::null_mut();
        // SAFETY: The current-process pseudo handle is valid; token is output.
        if unsafe { OpenProcessToken(GetCurrentProcess(), TOKEN_QUERY, &mut token) } == 0 {
            return Err(());
        }
        let result = (|| {
            let mut length = 0;
            // SAFETY: First call requests the token buffer length.
            unsafe { GetTokenInformation(token, TokenUser, ptr::null_mut(), 0, &mut length) };
            if length < size_of::<TOKEN_USER>() as u32 || length > 4096 {
                return Err(());
            }
            let mut buffer = vec![0usize; (length as usize).div_ceil(size_of::<usize>())];
            // SAFETY: Buffer is aligned and contains at least length bytes.
            if unsafe {
                GetTokenInformation(
                    token,
                    TokenUser,
                    buffer.as_mut_ptr().cast(),
                    length,
                    &mut length,
                )
            } == 0
            {
                return Err(());
            }
            // SAFETY: A successful query initializes TOKEN_USER in buffer.
            sid_text(unsafe { (*(buffer.as_ptr().cast::<TOKEN_USER>())).User.Sid })
        })();
        // SAFETY: token was successfully opened above.
        unsafe { CloseHandle(token) };
        result
    }

    fn security(file: &File) -> Result<SecuritySummary, ()> {
        let mut owner: PSID = ptr::null_mut();
        let mut dacl: *mut ACL = ptr::null_mut();
        let mut descriptor: PSECURITY_DESCRIPTOR = ptr::null_mut();
        // SAFETY: File handle is held, all output pointers are valid. Windows
        // allocates the descriptor containing the owner and ACL pointers.
        if unsafe {
            GetSecurityInfo(
                file.as_raw_handle(),
                SE_FILE_OBJECT,
                OWNER_SECURITY_INFORMATION | DACL_SECURITY_INFORMATION,
                &mut owner,
                ptr::null_mut(),
                &mut dacl,
                ptr::null_mut(),
                &mut descriptor,
            )
        } != 0
        {
            return Err(());
        }
        let result = (|| {
            if descriptor.is_null() || dacl.is_null() {
                return Err(());
            }
            let owner = sid_text(owner)?;
            let mut control = 0;
            let mut revision = 0;
            // SAFETY: descriptor came from a successful Windows query.
            if unsafe { GetSecurityDescriptorControl(descriptor, &mut control, &mut revision) } == 0
            {
                return Err(());
            }
            let mut grants = Vec::new();
            // SAFETY: dacl points within the descriptor allocation.
            for index in 0..u32::from(unsafe { (*dacl).AceCount }) {
                let mut ace = ptr::null_mut();
                // SAFETY: index is bounded by AceCount, and ace is an output.
                if unsafe { GetAce(dacl, index, &mut ace) } == 0 || ace.is_null() {
                    return Err(());
                }
                // SAFETY: GetAce returns an aligned ACE_HEADER.
                let header = unsafe { &*ace.cast::<ACE_HEADER>() };
                if header.AceType == 1 {
                    continue;
                } // Deny ACEs only remove access.
                if header.AceType != 0
                    || usize::from(header.AceSize) < size_of::<ACCESS_ALLOWED_ACE>()
                {
                    return Err(());
                }
                // SAFETY: Type and size match ACCESS_ALLOWED_ACE; SidStart is
                // the first DWORD of the trailing SID in this ACE.
                let allowed = unsafe { &*ace.cast::<ACCESS_ALLOWED_ACE>() };
                let sid = sid_text((&allowed.SidStart as *const u32).cast_mut().cast())?;
                grants.push(AccessGrant {
                    sid,
                    mask: allowed.Mask,
                    inherit_only: u32::from(header.AceFlags) & INHERIT_ONLY_ACE != 0,
                });
            }
            Ok(SecuritySummary {
                owner,
                protected: control & SE_DACL_PROTECTED != 0,
                grants,
            })
        })();
        // SAFETY: GetSecurityInfo allocated the descriptor using LocalAlloc.
        unsafe { LocalFree(descriptor) };
        result
    }

    fn create_private_output(path: &Path) -> std::io::Result<File> {
        let sddl: Vec<_> = "O:BAG:BAD:P(A;;FA;;;SY)(A;;FA;;;BA)"
            .encode_utf16()
            .chain(Some(0))
            .collect();
        let mut descriptor = ptr::null_mut();
        // SAFETY: sddl is NUL terminated and descriptor is a valid output.
        if unsafe {
            ConvertStringSecurityDescriptorToSecurityDescriptorW(
                sddl.as_ptr(),
                1,
                &mut descriptor,
                ptr::null_mut(),
            )
        } == 0
        {
            return Err(std::io::Error::last_os_error());
        }
        let attributes = SECURITY_ATTRIBUTES {
            nLength: size_of::<SECURITY_ATTRIBUTES>() as u32,
            lpSecurityDescriptor: descriptor,
            bInheritHandle: 0,
        };
        let wide: Vec<_> = path.as_os_str().encode_wide().chain(Some(0)).collect();
        // SAFETY: Path and descriptor remain alive for CreateFileW. Owner and
        // DACL are assigned atomically, never inherited from the user's token.
        let handle = unsafe {
            CreateFileW(
                wide.as_ptr(),
                0x40000000 | 0x20000,
                0,
                &attributes,
                CREATE_NEW,
                FILE_FLAG_OPEN_REPARSE_POINT,
                ptr::null_mut(),
            )
        };
        let error = std::io::Error::last_os_error();
        // SAFETY: The descriptor was allocated by the SDDL conversion above.
        unsafe { LocalFree(descriptor) };
        if handle == INVALID_HANDLE_VALUE {
            return Err(error);
        }
        // SAFETY: We uniquely own the newly created handle.
        let output = unsafe { File::from_raw_handle(handle) };
        if security(&output)
            .and_then(|summary| validate_private(&summary, true))
            .is_err()
        {
            drop(output);
            let _ = fs::remove_file(path);
            return Err(std::io::ErrorKind::PermissionDenied.into());
        }
        Ok(output)
    }

    fn pin_directory(path: &Path) -> std::io::Result<File> {
        OpenOptions::new()
            // FILE_LIST_DIRECTORY makes this a data-access handle; metadata-
            // only opens do not reliably enforce Windows share restrictions.
            .access_mode(0x20000 | 0x80 | 0x1)
            .share_mode(FILE_SHARE_READ)
            .custom_flags(FILE_FLAG_BACKUP_SEMANTICS | FILE_FLAG_OPEN_REPARSE_POINT)
            .open(path)
    }

    pub(crate) struct ProtectedEvidenceChannel {
        directory: PathBuf,
        _pinned_directories: Vec<File>,
    }

    impl ProtectedEvidenceChannel {
        pub(crate) fn for_owner_sid(sid: &str) -> Result<Self, ()> {
            let common = common_data()?;
            let directory = channel_directory(&common, sid)?;
            Self::open_directory(&directory, &common.join(ROOT_NAME))
        }

        pub(crate) fn for_current_user() -> Result<Self, ()> {
            Self::for_owner_sid(&current_sid()?)
        }

        pub(super) fn open_directory(directory: &Path, private_root: &Path) -> Result<Self, ()> {
            if !directory.is_absolute()
                || !directory.starts_with(private_root)
                || directory
                    .components()
                    .any(|part| matches!(part, Component::ParentDir | Component::CurDir))
            {
                return Err(());
            }
            let mut ancestors: Vec<_> = directory.ancestors().collect();
            ancestors.reverse();
            let mut pinned = Vec::new();
            for path in ancestors {
                let handle = pin_directory(path).map_err(|_| ())?;
                let metadata = handle.metadata().map_err(|_| ())?;
                if !metadata.is_dir() || metadata.file_attributes() & 0x400 != 0 {
                    return Err(());
                }
                validate_security(&security(&handle)?, path.starts_with(private_root))?;
                pinned.push(handle);
            }
            Ok(Self {
                directory: directory.into(),
                _pinned_directories: pinned,
            })
        }

        pub(crate) fn read(&self) -> Result<Option<Vec<u8>>, ()> {
            let input = match OpenOptions::new()
                .read(true)
                .share_mode(FILE_SHARE_READ)
                .custom_flags(FILE_FLAG_OPEN_REPARSE_POINT)
                .open(self.directory.join(FILE_NAME))
            {
                Ok(input) => input,
                Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(None),
                Err(_) => return Err(()),
            };
            let metadata = input.metadata().map_err(|_| ())?;
            if !metadata.is_file()
                || metadata.file_attributes() & 0x400 != 0
                || metadata.len() > 4096
            {
                return Err(());
            }
            validate_private(&security(&input)?, false)?;
            let mut info = BY_HANDLE_FILE_INFORMATION::default();
            // SAFETY: input is held and info points to the required output struct.
            if unsafe { GetFileInformationByHandle(input.as_raw_handle(), &mut info) } == 0
                || info.nNumberOfLinks != 1
            {
                return Err(());
            }
            let mut bytes = Vec::new();
            input.take(4097).read_to_end(&mut bytes).map_err(|_| ())?;
            if bytes.is_empty() || bytes.len() > 4096 {
                return Err(());
            }
            Ok(Some(bytes))
        }

        pub(crate) fn take(&self) -> Result<Option<Vec<u8>>, ()> {
            let Some(bytes) = self.read()? else {
                return Ok(None);
            };
            fs::remove_file(self.directory.join(FILE_NAME)).map_err(|_| ())?;
            Ok(Some(bytes))
        }

        pub(crate) fn publish(&self, evidence: &ConflictEvidence) -> bool {
            let target = self.directory.join(FILE_NAME);
            match self.read() {
                Ok(Some(bytes)) => {
                    if serde_json::from_slice::<ConflictEvidence>(&bytes)
                        .ok()
                        .is_some_and(|pending| {
                            pending.fresh_for(
                                &evidence.lease_id,
                                evidence.desktop_pid,
                                evidence.desktop_created_at,
                                evidence.observed_at,
                            )
                        })
                    {
                        return true;
                    }
                    if fs::remove_file(&target).is_err() {
                        return false;
                    }
                }
                Ok(None) => {}
                Err(()) => return false,
            }
            let temporary = self
                .directory
                .join(format!(".ccswitch-conflict-{}.tmp", uuid::Uuid::new_v4()));
            let result = (|| -> std::io::Result<()> {
                let mut output = create_private_output(&temporary)?;
                serde_json::to_writer(&mut output, evidence)?;
                output.sync_all()?;
                drop(output);
                // Atomic create-if-absent; the reader also rejects multiple
                // hard links until the temporary link has been removed.
                fs::hard_link(&temporary, &target)
            })();
            let removed = fs::remove_file(&temporary).is_ok();
            result.is_ok() && removed
        }
    }

    #[cfg(test)]
    mod output_tests {
        use super::*;

        #[test]
        fn pinned_directory_blocks_rename_and_reparse_write_access() {
            let directory =
                std::env::temp_dir().join(format!("guard-pin-{}", uuid::Uuid::new_v4()));
            fs::create_dir(&directory).unwrap();
            let renamed = directory.with_extension("moved");
            let pinned = pin_directory(&directory).unwrap();
            assert!(fs::rename(&directory, &renamed).is_err());
            assert!(OpenOptions::new()
                .access_mode(0x40000000)
                .share_mode(7)
                .custom_flags(FILE_FLAG_BACKUP_SEMANTICS)
                .open(&directory)
                .is_err());
            drop(pinned);
            fs::remove_dir(directory).unwrap();
        }

        #[test]
        fn evidence_output_is_created_with_privileged_owner_or_is_rejected() {
            let directory =
                std::env::temp_dir().join(format!("guard-output-{}", uuid::Uuid::new_v4()));
            fs::create_dir(&directory).unwrap();
            let output = directory.join("proof.tmp");
            match create_private_output(&output) {
                Ok(file) => {
                    let summary = security(&file).unwrap();
                    assert_eq!(summary.owner, ADMINS);
                    assert!(summary.protected);
                    assert!(validate_security(&summary, true).is_ok());
                    drop(file);
                    fs::remove_file(&output).unwrap();
                }
                Err(_) => assert!(!output.exists()),
            }
            fs::remove_dir(directory).unwrap();
        }
    }
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
        let temporary =
            std::env::temp_dir().join(format!("guard-evidence-{}", uuid::Uuid::new_v4()));
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
            common
                .join("CodexQuotaGuardConflictEvidence")
                .join("S-1-5-21-1-2-3-1001")
        );
        assert!(native_security::channel_directory(common, "S-1-12-1-1-2-3-4").is_ok());
        for invalid in [
            "",
            "S-1-5-18",
            "S-1-5-21-1\\escape",
            "S-1-5-21-1:stream",
            "S-1-5-21-..",
            "S-1-5-21-1/escape",
        ] {
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
            grants: vec![
                AccessGrant {
                    sid: "S-1-5-18".into(),
                    mask: 0x1f01ff,
                    inherit_only: false,
                },
                AccessGrant {
                    sid: "S-1-5-32-544".into(),
                    mask: 0x1f01ff,
                    inherit_only: false,
                },
            ],
        };
        assert!(validate_security(&summary, true).is_ok());
        let mut forged = summary.clone();
        forged.owner = "S-1-5-21-1-2-3-1001".into();
        assert!(validate_security(&forged, true).is_err());
        let mut writable = summary.clone();
        writable.grants.push(AccessGrant {
            sid: "S-1-5-21-1-2-3-1001".into(),
            mask: 2,
            inherit_only: false,
        });
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
            owner: "S-1-5-18".into(),
            protected: false,
            grants: vec![AccessGrant {
                sid: "S-1-5-32-545".into(),
                mask: 0x116,
                inherit_only: false,
            }],
        };
        assert!(validate_security(&summary, false).is_ok());
        for dangerous in [0x40, 0x10000, 0x40000, 0x80000, 0x10000000] {
            summary.grants[0].mask = dangerous;
            assert!(validate_security(&summary, false).is_err());
        }
    }
}
