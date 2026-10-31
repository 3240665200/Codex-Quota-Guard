//! Retire only invalid or positively stale guard authority; never inspect usage journals.
use serde::{Deserialize, Serialize};
use std::{
    fs::{self, OpenOptions},
    io::Read,
    path::{Path, PathBuf},
};
use uuid::Uuid;

#[derive(Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub(crate) struct GuardLeaseRecord {
    pub(crate) pid: u32,
    pub(crate) created_at: u64,
    pub(crate) lease_id: String,
    pub(crate) policy: String,
}
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum Liveness {
    Active,
    Stale,
    Unknown,
}

#[cfg(windows)]
pub(crate) fn liveness(record: &GuardLeaseRecord) -> Liveness {
    use windows_sys::Win32::{
        Foundation::{
            CloseHandle, GetLastError, ERROR_INVALID_PARAMETER, FILETIME, WAIT_OBJECT_0,
            WAIT_TIMEOUT,
        },
        System::Threading::{
            GetProcessTimes, OpenProcess, WaitForSingleObject, PROCESS_QUERY_LIMITED_INFORMATION,
            PROCESS_SYNCHRONIZE,
        },
    };
    let handle = unsafe {
        OpenProcess(
            PROCESS_QUERY_LIMITED_INFORMATION | PROCESS_SYNCHRONIZE,
            0,
            record.pid,
        )
    };
    if handle.is_null() {
        return if unsafe { GetLastError() } == ERROR_INVALID_PARAMETER {
            Liveness::Stale
        } else {
            Liveness::Unknown
        };
    }
    let state = match unsafe { WaitForSingleObject(handle, 0) } {
        WAIT_OBJECT_0 => Liveness::Stale,
        WAIT_TIMEOUT => {
            let (mut created, mut exited, mut kernel, mut user) = (
                FILETIME::default(),
                FILETIME::default(),
                FILETIME::default(),
                FILETIME::default(),
            );
            if unsafe { GetProcessTimes(handle, &mut created, &mut exited, &mut kernel, &mut user) }
                != 0
            {
                if ((u64::from(created.dwHighDateTime) << 32) | u64::from(created.dwLowDateTime))
                    == record.created_at
                {
                    Liveness::Active
                } else {
                    Liveness::Stale
                }
            } else if unsafe { WaitForSingleObject(handle, 0) } == WAIT_OBJECT_0 {
                Liveness::Stale
            } else {
                Liveness::Unknown
            }
        }
        _ => Liveness::Unknown,
    };
    unsafe { CloseHandle(handle) };
    state
}
#[cfg(not(windows))]
pub(crate) fn liveness(_: &GuardLeaseRecord) -> Liveness {
    Liveness::Unknown
}

fn can_retire(
    bytes: &[u8],
    probe: impl FnOnce(&GuardLeaseRecord) -> Liveness,
) -> Result<bool, String> {
    let record = serde_json::from_slice::<GuardLeaseRecord>(bytes).ok();
    let Some(record) = record.filter(|r| {
        r.pid != 0
            && r.created_at != 0
            && Uuid::parse_str(&r.lease_id).is_ok_and(|id| !id.is_nil())
            && r.policy == crate::conflict_evidence::POLICY
    }) else {
        return Ok(true);
    };
    match probe(&record) {
        Liveness::Stale => Ok(true),
        Liveness::Active => Ok(false),
        Liveness::Unknown => Err("无法确认旧检测进程是否已退出，请关闭其他客户端后再修复。".into()),
    }
}

#[cfg(windows)]
pub(crate) fn archive_stale(path: &Path) -> Result<Option<PathBuf>, String> {
    use std::{
        mem::{offset_of, size_of},
        os::windows::{
            ffi::OsStrExt,
            fs::{MetadataExt, OpenOptionsExt},
            io::AsRawHandle,
        },
    };
    use windows_sys::Win32::Storage::FileSystem::{
        FileRenameInfo, SetFileInformationByHandle, FILE_FLAG_OPEN_REPARSE_POINT, FILE_RENAME_INFO,
        FILE_SHARE_DELETE, FILE_SHARE_READ, FILE_SHARE_WRITE,
    };
    if !path.is_absolute()
        || path.file_name().and_then(|v| v.to_str()) != Some("ccswitch-guard.json")
    {
        return Err("检测记录位置无效。".into());
    }
    let mut input = match OpenOptions::new()
        .access_mode(0x80000000 | 0x10000)
        .share_mode(FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE)
        .custom_flags(FILE_FLAG_OPEN_REPARSE_POINT)
        .open(path)
    {
        Ok(input) => input,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(None),
        Err(_) => return Err("无法读取旧检测记录，请检查客户端数据目录权限。".into()),
    };
    let metadata = input.metadata().map_err(|_| "无法检查旧检测记录。")?;
    if !metadata.is_file() || metadata.file_attributes() & 0x400 != 0 || metadata.len() > 4096 {
        return Err("旧检测记录不是安全的普通文件，请检查客户端数据目录。".into());
    }
    let mut bytes = Vec::new();
    input
        .by_ref()
        .take(4097)
        .read_to_end(&mut bytes)
        .map_err(|_| "无法读取旧检测记录。")?;
    if bytes.len() > 4096 {
        return Err("旧检测记录超过允许大小。".into());
    }
    if !can_retire(&bytes, liveness)? {
        return Ok(None);
    }
    let parent = path.parent().ok_or("检测记录位置无效。")?;
    let backup = parent.join(format!("ccswitch-guard.expired-{}.json", Uuid::new_v4()));
    let name: Vec<u16> = backup.as_os_str().encode_wide().collect();
    let offset = offset_of!(FILE_RENAME_INFO, FileName);
    let length = offset + name.len() * 2;
    let mut buffer = vec![0usize; length.div_ceil(size_of::<usize>())];
    let info = buffer.as_mut_ptr().cast::<FILE_RENAME_INFO>();
    // Rename this exact opened file object, even if a writer replaces the pathname.
    // RootDirectory and ReplaceIfExists remain zero; backup names are unique.
    unsafe {
        (*info).FileNameLength = (name.len() * 2) as u32;
        std::ptr::copy_nonoverlapping(
            name.as_ptr(),
            info.cast::<u8>().add(offset).cast(),
            name.len(),
        );
    }
    if unsafe {
        SetFileInformationByHandle(
            input.as_raw_handle().cast(),
            FileRenameInfo,
            info.cast(),
            length as u32,
        )
    } == 0
    {
        return Err("旧检测记录备份失败，请关闭其他客户端后再修复。".into());
    }
    Ok(Some(backup))
}
#[cfg(not(windows))]
pub(crate) fn archive_stale(_: &Path) -> Result<Option<PathBuf>, String> {
    Ok(None)
}

#[cfg(test)]
mod tests {
    use super::*;
    fn record() -> Vec<u8> {
        serde_json::to_vec(&GuardLeaseRecord {
            pid: 42,
            created_at: 100,
            lease_id: Uuid::new_v4().to_string(),
            policy: crate::conflict_evidence::POLICY.into(),
        })
        .unwrap()
    }
    #[test]
    fn only_confirmed_stale_leases_can_be_retired() {
        assert!(can_retire(&record(), |_| Liveness::Stale).unwrap());
        assert!(!can_retire(&record(), |_| Liveness::Active).unwrap());
        assert!(can_retire(&record(), |_| Liveness::Unknown).is_err());
    }
    #[test]
    fn invalid_records_are_withdrawn_without_inspecting_or_ending_any_process() {
        assert!(can_retire(b"invalid", |_| panic!("must not inspect an invalid record")).unwrap());
    }
    #[cfg(windows)]
    #[test]
    fn archives_malformed_file_without_deleting_its_contents() {
        let root = std::env::temp_dir().join(format!("cqg-guard-repair-{}", Uuid::new_v4()));
        std::fs::create_dir(&root).unwrap();
        let path = root.join("ccswitch-guard.json");
        std::fs::write(&path, b"invalid").unwrap();
        let backup = archive_stale(&path).unwrap().unwrap();
        assert!(!path.exists());
        assert_eq!(std::fs::read(&backup).unwrap(), b"invalid");
        assert_eq!(backup.parent(), Some(root.as_path()));
        std::fs::remove_file(backup).unwrap();
        std::fs::remove_dir(root).unwrap();
    }
}
