//! The Windows service only enforces this guard while a live, elevated desktop
//! process for the configured user holds the lease. All process actions are
//! behind `GuardOps` so policy tests never inspect or terminate host processes.

use crate::{epoch_seconds, MeterConfig};
use serde::{Deserialize, Serialize};
use std::fs;
use std::io::Read;
use std::os::windows::fs::OpenOptionsExt;
use std::path::{Path, PathBuf};
use uuid::Uuid;
use windows_sys::Win32::Storage::FileSystem::FILE_FLAG_OPEN_REPARSE_POINT;

const ERROR: &str = "METER_CC_SWITCH_GUARD_FAILED";
const LEASE_NAME: &str = "ccswitch-guard.json";
const DESKTOP_EXE: &str = "codex-quota-guard.exe";
const DESKTOP_PRODUCT: &str = "Codex Quota Guard";
const CC_EXE: &str = "cc-switch.exe";
const CC_PRODUCT: &str = "CC Switch";

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(super) enum GuardState {
    Inactive,
    Clear,
    Terminated,
    Error,
}

impl GuardState {
    pub(super) fn as_str(self) -> &'static str {
        match self {
            Self::Inactive => "INACTIVE",
            Self::Clear => "CLEAR",
            Self::Terminated => "TERMINATED",
            Self::Error => "ERROR",
        }
    }
}

pub(super) struct GuardCheck {
    pub(super) state: GuardState,
    pub(super) lease_id: Option<Uuid>,
    pub(super) checked_at: u64,
    pub(super) detected: bool,
    pub(super) confirmed_termination_count: u64,
    pub(super) last_terminated_at: Option<u64>,
}

impl GuardCheck {
    fn new(
        state: GuardState,
        lease_id: Option<Uuid>,
        detected: bool,
        confirmed_termination_count: u64,
        last_terminated_at: Option<u64>,
    ) -> Self {
        Self {
            state,
            lease_id,
            checked_at: epoch_seconds(),
            detected,
            confirmed_termination_count,
            last_terminated_at,
        }
    }

    pub(super) fn error_code(&self) -> Option<String> {
        (self.state == GuardState::Error).then(|| ERROR.to_owned())
    }
}

#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct GuardLease {
    pid: u32,
    created_at: u64, // raw Windows FILETIME, in 100 ns units
    lease_id: Uuid,
}

fn read_lease(status_file: &Path) -> Result<Option<GuardLease>, ()> {
    let file = status_file.parent().ok_or(())?.join(LEASE_NAME);
    let metadata = match fs::symlink_metadata(&file) {
        Ok(metadata) => metadata,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(None),
        Err(_) => return Err(()),
    };
    if !metadata.file_type().is_file() || metadata.len() == 0 || metadata.len() > 4096 {
        return Err(());
    }
    // The leaf must remain a regular file even if it is swapped after the
    // path-level check; opening the reparse point itself avoids following it.
    let input = fs::OpenOptions::new()
        .read(true)
        .custom_flags(FILE_FLAG_OPEN_REPARSE_POINT)
        .open(file)
        .map_err(|_| ())?;
    let opened_metadata = input.metadata().map_err(|_| ())?;
    if !opened_metadata.is_file() || opened_metadata.len() == 0 || opened_metadata.len() > 4096 {
        return Err(());
    }
    let mut bytes = Vec::new();
    input.take(4097).read_to_end(&mut bytes).map_err(|_| ())?;
    if bytes.is_empty() || bytes.len() > 4096 {
        return Err(());
    }
    let lease: GuardLease = serde_json::from_slice(&bytes).map_err(|_| ())?;
    if lease.pid == 0 || lease.created_at == 0 || lease.lease_id.is_nil() {
        return Err(());
    }
    Ok(Some(lease))
}

#[derive(Clone, Debug)]
struct ProcessIdentity {
    pid: u32,
    created_at: u64,
    owner_sid: String,
    session_id: u32,
    image: PathBuf,
    elevated: bool,
}

fn exact_exe(image: &Path, name: &str) -> bool {
    image
        .file_name()
        .and_then(|part| part.to_str())
        .is_some_and(|part| part.eq_ignore_ascii_case(name))
}

trait GuardProcess {
    fn identity(&self) -> Result<ProcessIdentity, ()>;
    fn product_names(&self) -> Result<Vec<String>, ()>;
    fn exited(&self, timeout_ms: u32) -> Result<bool, ()>;
    fn terminate(&self) -> Result<(), ()>;
}

trait GuardOps {
    type Process: GuardProcess;
    fn lease_current(&self, lease: &GuardLease) -> Result<bool, ()>;
    fn open(&self, pid: u32) -> Result<Option<Self::Process>, ()>;
    fn cc_candidates(&self) -> Result<Vec<u32>, ()>;
}

fn enforce<O: GuardOps>(lease: &GuardLease, owner_sid: &str, ops: &O) -> GuardCheck {
    let mut detected = false;
    let mut confirmed_termination_count = 0u64;
    let mut last_terminated_at = None;
    let result = (|| {
        if !ops.lease_current(lease)? {
            return Ok(GuardState::Inactive);
        }
        let Some(app) = ops.open(lease.pid)? else {
            return Ok(GuardState::Inactive);
        };
        let app_id = app.identity()?;
        if app_id.pid != lease.pid
            || app_id.created_at != lease.created_at
            || app_id.owner_sid != owner_sid
            || !app_id.elevated
            || !exact_exe(&app_id.image, DESKTOP_EXE)
            || app.exited(0)?
        {
            return Ok(GuardState::Inactive);
        }
        if !app
            .product_names()?
            .iter()
            .any(|name| name == DESKTOP_PRODUCT)
        {
            return Err(());
        }
        let mut terminated = false;
        for pid in ops.cc_candidates()? {
            let Some(cc) = ops.open(pid)? else { continue };
            let cc_id = cc.identity()?;
            if cc_id.pid != pid
                || !exact_exe(&cc_id.image, CC_EXE)
                || cc_id.owner_sid != owner_sid
                || cc_id.session_id != app_id.session_id
                || cc.exited(0)?
            {
                continue;
            }
            // A same-name executable with missing or different product identity
            // must never be killed. The unresolved conflict blocks reporting.
            if !cc.product_names()?.iter().any(|name| name == CC_PRODUCT) {
                return Err(());
            }
            detected = true;
            if !ops.lease_current(lease)? || app.exited(0)? {
                return Ok(GuardState::Inactive);
            }
            // The service runs in Session 0 and cannot close the user's windows.
            // End the verified process directly, then confirm it exited.
            cc.terminate()?;
            if !cc.exited(2_000)? {
                return Err(());
            }
            confirmed_termination_count = confirmed_termination_count.saturating_add(1);
            last_terminated_at = Some(epoch_seconds());
            terminated = true;
        }
        if !ops.lease_current(lease)? || app.exited(0)? {
            return Ok(GuardState::Inactive);
        }
        Ok(if terminated {
            GuardState::Terminated
        } else {
            GuardState::Clear
        })
    })();
    GuardCheck::new(
        result.unwrap_or(GuardState::Error),
        Some(lease.lease_id),
        detected,
        confirmed_termination_count,
        last_terminated_at,
    )
}

pub(super) fn check(config: &MeterConfig) -> GuardCheck {
    let lease = match read_lease(&config.status_file) {
        Ok(Some(lease)) => lease,
        Ok(None) => return GuardCheck::new(GuardState::Inactive, None, false, 0, None),
        Err(()) => return GuardCheck::new(GuardState::Error, None, false, 0, None),
    };
    let lease_id = Some(lease.lease_id);
    let Some(owner_sid) = config.owner_user_sid.as_deref() else {
        // Old installations remain readable. Upgrade must add the SID before
        // a desktop lease can activate process enforcement.
        return GuardCheck::new(GuardState::Error, lease_id, false, 0, None);
    };
    enforce(
        &lease,
        owner_sid,
        &WindowsOps {
            status_file: config.status_file.clone(),
        },
    )
}

struct WindowsOps {
    status_file: PathBuf,
}

mod native {
    use super::*;
    use std::ffi::{c_void, OsStr};
    use std::mem::size_of;
    use std::os::windows::ffi::{OsStrExt, OsStringExt};
    use std::ptr;
    use windows_sys::Win32::Foundation::{
        CloseHandle, GetLastError, LocalFree, ERROR_INVALID_PARAMETER, ERROR_NO_MORE_FILES,
        FILETIME, HANDLE, INVALID_HANDLE_VALUE, WAIT_OBJECT_0, WAIT_TIMEOUT,
    };
    use windows_sys::Win32::Security::Authorization::ConvertSidToStringSidW;
    use windows_sys::Win32::Security::{
        GetTokenInformation, TokenElevation, TokenUser, TOKEN_ELEVATION, TOKEN_QUERY, TOKEN_USER,
    };
    use windows_sys::Win32::Storage::FileSystem::{
        GetFileVersionInfoSizeW, GetFileVersionInfoW, VerQueryValueW,
    };
    use windows_sys::Win32::System::Diagnostics::ToolHelp::{
        CreateToolhelp32Snapshot, Process32FirstW, Process32NextW, PROCESSENTRY32W,
        TH32CS_SNAPPROCESS,
    };
    use windows_sys::Win32::System::RemoteDesktop::ProcessIdToSessionId;
    use windows_sys::Win32::System::Threading::{
        GetProcessTimes, OpenProcess, OpenProcessToken, QueryFullProcessImageNameW,
        TerminateProcess, WaitForSingleObject, PROCESS_QUERY_LIMITED_INFORMATION,
        PROCESS_SYNCHRONIZE, PROCESS_TERMINATE,
    };

    pub(super) struct OwnedHandle(HANDLE);
    impl Drop for OwnedHandle {
        fn drop(&mut self) {
            // SAFETY: Handles stored here come from successful Win32 open calls.
            unsafe { CloseHandle(self.0) };
        }
    }

    pub(super) struct WindowsProcess {
        handle: OwnedHandle,
        pid: u32,
    }

    fn filetime(value: FILETIME) -> u64 {
        ((value.dwHighDateTime as u64) << 32) | value.dwLowDateTime as u64
    }

    fn creation_time(handle: HANDLE) -> Result<u64, ()> {
        let mut created = FILETIME::default();
        let mut exited = FILETIME::default();
        let mut kernel = FILETIME::default();
        let mut user = FILETIME::default();
        // SAFETY: All four FILETIME pointers are writable and handle is open.
        if unsafe { GetProcessTimes(handle, &mut created, &mut exited, &mut kernel, &mut user) }
            == 0
        {
            return Err(());
        }
        Ok(filetime(created))
    }

    fn image_path(handle: HANDLE) -> Result<PathBuf, ()> {
        let mut buffer = vec![0u16; 32768];
        let mut length = buffer.len() as u32;
        // SAFETY: Buffer and size are valid for this open process handle.
        if unsafe { QueryFullProcessImageNameW(handle, 0, buffer.as_mut_ptr(), &mut length) } == 0
            || length == 0
            || length as usize >= buffer.len()
        {
            return Err(());
        }
        Ok(PathBuf::from(std::ffi::OsString::from_wide(
            &buffer[..length as usize],
        )))
    }

    fn token(handle: HANDLE) -> Result<OwnedHandle, ()> {
        let mut token = ptr::null_mut();
        // SAFETY: Query-only access to a valid process handle.
        if unsafe { OpenProcessToken(handle, TOKEN_QUERY, &mut token) } == 0 {
            return Err(());
        }
        Ok(OwnedHandle(token))
    }

    fn owner_sid(token: HANDLE) -> Result<String, ()> {
        let mut length = 0u32;
        // SAFETY: First call asks only for the required size.
        unsafe { GetTokenInformation(token, TokenUser, ptr::null_mut(), 0, &mut length) };
        if length < size_of::<TOKEN_USER>() as u32 || length > 4096 {
            return Err(());
        }
        let mut words = vec![0usize; (length as usize).div_ceil(size_of::<usize>())];
        // SAFETY: Aligned buffer is at least `length` bytes long.
        if unsafe {
            GetTokenInformation(
                token,
                TokenUser,
                words.as_mut_ptr().cast(),
                length,
                &mut length,
            )
        } == 0
        {
            return Err(());
        }
        // SAFETY: Successful TokenUser query initialized the buffer.
        let user = unsafe { &*(words.as_ptr().cast::<TOKEN_USER>()) };
        let mut sid = ptr::null_mut();
        // SAFETY: User SID remains in the token information buffer.
        if unsafe { ConvertSidToStringSidW(user.User.Sid, &mut sid) } == 0 {
            return Err(());
        }
        let mut count = 0usize;
        // SAFETY: Returned SID string is NUL-terminated; count is bounded.
        while count < 256 && unsafe { *sid.add(count) } != 0 {
            count += 1
        }
        let result = (count < 256).then(|| {
            // SAFETY: Count was bounded by the NUL terminator above.
            String::from_utf16_lossy(unsafe { std::slice::from_raw_parts(sid, count) })
        });
        // SAFETY: ConvertSidToStringSidW allocated this pointer.
        unsafe { LocalFree(sid.cast()) };
        result.filter(|value| value.starts_with("S-1-")).ok_or(())
    }

    fn elevated(token: HANDLE) -> Result<bool, ()> {
        let mut elevation = TOKEN_ELEVATION::default();
        let mut length = 0u32;
        // SAFETY: Output struct and length are valid.
        if unsafe {
            GetTokenInformation(
                token,
                TokenElevation,
                (&mut elevation as *mut TOKEN_ELEVATION).cast(),
                size_of::<TOKEN_ELEVATION>() as u32,
                &mut length,
            )
        } == 0
        {
            return Err(());
        }
        Ok(elevation.TokenIsElevated != 0)
    }

    fn wide(value: &OsStr) -> Vec<u16> {
        value.encode_wide().chain(std::iter::once(0)).collect()
    }

    fn query_version_string(block: &[u8], key: &str) -> Result<String, ()> {
        let path = wide(OsStr::new(key));
        let mut value: *mut c_void = ptr::null_mut();
        let mut length = 0u32;
        // SAFETY: Version block is valid for the lifetime of returned pointer.
        if unsafe {
            VerQueryValueW(
                block.as_ptr().cast(),
                path.as_ptr(),
                &mut value,
                &mut length,
            )
        } == 0
            || value.is_null()
            || length == 0
            || length > 4096
        {
            return Err(());
        }
        // Version resources can be byte-aligned. Read UTF-16 units without
        // assuming that the returned pointer has u16 alignment.
        let units: Vec<u16> = (0..length as usize)
            .map(|index| {
                // SAFETY: VerQueryValueW returned `length` UTF-16 code units.
                unsafe {
                    value
                        .cast::<u8>()
                        .add(index * 2)
                        .cast::<u16>()
                        .read_unaligned()
                }
            })
            .collect();
        let text = String::from_utf16_lossy(&units);
        Ok(text.trim_end_matches('\0').to_owned())
    }

    fn product_names(path: &Path) -> Result<Vec<String>, ()> {
        let path = wide(path.as_os_str());
        let size = unsafe { GetFileVersionInfoSizeW(path.as_ptr(), ptr::null_mut()) };
        if size == 0 || size > 1024 * 1024 {
            return Err(());
        }
        let mut block = vec![0u8; size as usize];
        // SAFETY: Version block is `size` bytes, and path is NUL-terminated.
        if unsafe { GetFileVersionInfoW(path.as_ptr(), 0, size, block.as_mut_ptr().cast()) } == 0 {
            return Err(());
        }
        let mut translations: *mut c_void = ptr::null_mut();
        let mut length = 0u32;
        let key = wide(OsStr::new("\\VarFileInfo\\Translation"));
        // SAFETY: Query keeps returned translation inside `block`.
        if unsafe {
            VerQueryValueW(
                block.as_ptr().cast(),
                key.as_ptr(),
                &mut translations,
                &mut length,
            )
        } == 0
            || translations.is_null()
            || !(4..=4096).contains(&length)
            || length % 4 != 0
        {
            return Err(());
        }
        // SAFETY: VerQueryValueW returned `length` bytes in the version block.
        let bytes =
            unsafe { std::slice::from_raw_parts(translations.cast::<u8>(), length as usize) };
        let mut names = Vec::new();
        for pair in bytes.chunks_exact(4) {
            let language = u16::from_le_bytes([pair[0], pair[1]]);
            let codepage = u16::from_le_bytes([pair[2], pair[3]]);
            if let Ok(name) = query_version_string(
                &block,
                &format!("\\StringFileInfo\\{language:04X}{codepage:04X}\\ProductName"),
            ) {
                if !name.is_empty() {
                    names.push(name)
                }
            }
        }
        if names.is_empty() {
            Err(())
        } else {
            Ok(names)
        }
    }

    impl GuardProcess for WindowsProcess {
        fn identity(&self) -> Result<ProcessIdentity, ()> {
            let token = token(self.handle.0)?;
            let mut session_id = 0u32;
            // SAFETY: Output pointer is valid; PID belongs to held process.
            if unsafe { ProcessIdToSessionId(self.pid, &mut session_id) } == 0 {
                return Err(());
            }
            Ok(ProcessIdentity {
                pid: self.pid,
                created_at: creation_time(self.handle.0)?,
                owner_sid: owner_sid(token.0)?,
                session_id,
                image: image_path(self.handle.0)?,
                elevated: elevated(token.0)?,
            })
        }

        fn product_names(&self) -> Result<Vec<String>, ()> {
            product_names(&image_path(self.handle.0)?)
        }

        fn exited(&self, timeout_ms: u32) -> Result<bool, ()> {
            // SAFETY: Handle has PROCESS_SYNCHRONIZE access.
            match unsafe { WaitForSingleObject(self.handle.0, timeout_ms) } {
                WAIT_OBJECT_0 => Ok(true),
                WAIT_TIMEOUT => Ok(false),
                _ => Err(()),
            }
        }

        fn terminate(&self) -> Result<(), ()> {
            // Keep the original handle open throughout, so its PID cannot be
            // recycled. Recheck creation time on the terminate-capable handle.
            let handle = unsafe {
                OpenProcess(
                    PROCESS_TERMINATE | PROCESS_QUERY_LIMITED_INFORMATION,
                    0,
                    self.pid,
                )
            };
            if handle.is_null() {
                return if self.exited(0)? { Ok(()) } else { Err(()) };
            }
            let handle = OwnedHandle(handle);
            if creation_time(handle.0)? != creation_time(self.handle.0)? {
                return Err(());
            }
            // SAFETY: Handle targets the same verified process object.
            if unsafe { TerminateProcess(handle.0, 1) } == 0 && !self.exited(0)? {
                return Err(());
            }
            Ok(())
        }
    }

    impl GuardOps for WindowsOps {
        type Process = WindowsProcess;

        fn lease_current(&self, lease: &GuardLease) -> Result<bool, ()> {
            Ok(read_lease(&self.status_file)?.as_ref() == Some(lease))
        }

        fn open(&self, pid: u32) -> Result<Option<Self::Process>, ()> {
            // SAFETY: Query and wait rights only; no termination from this open.
            let handle = unsafe {
                OpenProcess(
                    PROCESS_QUERY_LIMITED_INFORMATION | PROCESS_SYNCHRONIZE,
                    0,
                    pid,
                )
            };
            if handle.is_null() {
                // A process may exit between snapshot and open.
                return if unsafe { GetLastError() } == ERROR_INVALID_PARAMETER {
                    Ok(None)
                } else {
                    Err(())
                };
            }
            Ok(Some(WindowsProcess {
                handle: OwnedHandle(handle),
                pid,
            }))
        }

        fn cc_candidates(&self) -> Result<Vec<u32>, ()> {
            // SAFETY: Snapshot handle is closed by OwnedHandle below.
            let snapshot = unsafe { CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0) };
            if snapshot == INVALID_HANDLE_VALUE {
                return Err(());
            }
            let snapshot = OwnedHandle(snapshot);
            let mut entry = PROCESSENTRY32W {
                dwSize: size_of::<PROCESSENTRY32W>() as u32,
                ..Default::default()
            };
            // SAFETY: Entry size is initialized as required by ToolHelp.
            if unsafe { Process32FirstW(snapshot.0, &mut entry) } == 0 {
                return Err(());
            }
            let mut pids = Vec::new();
            loop {
                let end = entry
                    .szExeFile
                    .iter()
                    .position(|&unit| unit == 0)
                    .unwrap_or(entry.szExeFile.len());
                if String::from_utf16_lossy(&entry.szExeFile[..end]).eq_ignore_ascii_case(CC_EXE) {
                    pids.push(entry.th32ProcessID);
                }
                // SAFETY: Snapshot and entry are valid for iteration.
                if unsafe { Process32NextW(snapshot.0, &mut entry) } == 0 {
                    if unsafe { GetLastError() } == ERROR_NO_MORE_FILES {
                        break;
                    }
                    return Err(());
                }
            }
            Ok(pids)
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::cell::Cell;
    use std::collections::BTreeMap;
    use std::rc::Rc;

    #[derive(Clone)]
    struct MockProcess {
        id: ProcessIdentity,
        product: Result<String, ()>,
        exits: Rc<Cell<bool>>,
        on_product: Option<Rc<dyn Fn()>>,
        terminate_count: Rc<Cell<u32>>,
        exit_after_terminate: bool,
    }
    impl GuardProcess for MockProcess {
        fn identity(&self) -> Result<ProcessIdentity, ()> {
            Ok(self.id.clone())
        }
        fn product_names(&self) -> Result<Vec<String>, ()> {
            if let Some(on_product) = &self.on_product {
                on_product()
            }
            self.product.clone().map(|name| vec![name])
        }
        fn exited(&self, _: u32) -> Result<bool, ()> {
            Ok(self.exits.get())
        }
        fn terminate(&self) -> Result<(), ()> {
            self.terminate_count.set(self.terminate_count.get() + 1);
            if self.exit_after_terminate {
                self.exits.set(true);
            }
            Ok(())
        }
    }
    struct MockOps {
        processes: BTreeMap<u32, MockProcess>,
        candidates: Vec<u32>,
        lease_current: Rc<Cell<bool>>,
    }
    impl GuardOps for MockOps {
        type Process = MockProcess;
        fn lease_current(&self, _: &GuardLease) -> Result<bool, ()> {
            Ok(self.lease_current.get())
        }
        fn open(&self, pid: u32) -> Result<Option<Self::Process>, ()> {
            Ok(self.processes.get(&pid).cloned())
        }
        fn cc_candidates(&self) -> Result<Vec<u32>, ()> {
            Ok(self.candidates.clone())
        }
    }
    fn process(pid: u32, exe: &str, sid: &str, session_id: u32) -> MockProcess {
        MockProcess {
            id: ProcessIdentity {
                pid,
                created_at: pid as u64 + 100,
                owner_sid: sid.into(),
                session_id,
                image: PathBuf::from(format!("C:\\Apps\\{exe}")),
                elevated: true,
            },
            product: Ok(if exe == DESKTOP_EXE {
                DESKTOP_PRODUCT
            } else {
                CC_PRODUCT
            }
            .into()),
            exits: Rc::new(Cell::new(false)),
            on_product: None,
            terminate_count: Rc::new(Cell::new(0)),
            exit_after_terminate: true,
        }
    }
    fn lease() -> GuardLease {
        GuardLease {
            pid: 10,
            created_at: 110,
            lease_id: Uuid::new_v4(),
        }
    }
    fn ops(cc: MockProcess) -> MockOps {
        MockOps {
            processes: BTreeMap::from([(10, process(10, DESKTOP_EXE, "S-1-5-21-1", 2)), (20, cc)]),
            candidates: vec![20],
            lease_current: Rc::new(Cell::new(true)),
        }
    }

    #[test]
    fn desktop_lease_and_cc_switch_process_alone_never_authorize_termination() {
        let cc = process(20, CC_EXE, "S-1-5-21-1", 2);
        let count = cc.terminate_count.clone();
        let check = enforce(&lease(), "S-1-5-21-1", &ops(cc));
        assert_eq!(count.get(), 0);
        assert_eq!(check.state, GuardState::Clear);
        assert_eq!(check.confirmed_termination_count, 0);
    }

    #[test]
    fn active_lease_ends_only_exact_product_for_same_user_and_session() {
        let cc = process(20, CC_EXE, "S-1-5-21-1", 2);
        let count = cc.terminate_count.clone();
        let check = enforce(&lease(), "S-1-5-21-1", &ops(cc));
        assert_eq!(check.state, GuardState::Terminated);
        assert!(check.detected);
        assert_eq!(check.confirmed_termination_count, 1);
        assert!(check.last_terminated_at.is_some());
        assert_eq!(count.get(), 1);
    }

    #[test]
    fn reports_only_processes_whose_termination_is_confirmed() {
        let mut cc = process(20, CC_EXE, "S-1-5-21-1", 2);
        cc.exit_after_terminate = false;
        let mock = ops(cc.clone());

        let check = enforce(&lease(), "S-1-5-21-1", &mock);

        assert_eq!(check.state, GuardState::Error);
        assert_eq!(check.confirmed_termination_count, 0);
        assert_eq!(check.last_terminated_at, None);
        assert_eq!(cc.terminate_count.get(), 1);
    }

    #[test]
    fn counts_each_confirmed_process() {
        let first = process(20, CC_EXE, "S-1-5-21-1", 2);
        let second = process(21, CC_EXE, "S-1-5-21-1", 2);
        let mut mock = ops(first.clone());
        mock.processes.insert(21, second.clone());
        mock.candidates = vec![20, 21];

        let check = enforce(&lease(), "S-1-5-21-1", &mock);

        assert_eq!(check.state, GuardState::Terminated);
        assert_eq!(check.confirmed_termination_count, 2);
        assert!(check.last_terminated_at.is_some());
        assert_eq!(first.terminate_count.get(), 1);
        assert_eq!(second.terminate_count.get(), 1);
    }

    #[test]
    fn keeps_confirmed_termination_count_when_later_candidate_errors() {
        let first = process(20, CC_EXE, "S-1-5-21-1", 2);
        let mut second = process(21, CC_EXE, "S-1-5-21-1", 2);
        second.product = Err(());
        let mut mock = ops(first.clone());
        mock.processes.insert(21, second);
        mock.candidates = vec![20, 21];

        let check = enforce(&lease(), "S-1-5-21-1", &mock);

        assert_eq!(check.state, GuardState::Error);
        assert_eq!(check.confirmed_termination_count, 1);
        assert!(check.last_terminated_at.is_some());
        assert_eq!(first.terminate_count.get(), 1);
    }

    #[test]
    fn keeps_confirmed_termination_count_when_lease_changes_later() {
        let first = process(20, CC_EXE, "S-1-5-21-1", 2);
        let mut second = process(21, CC_EXE, "S-1-5-21-1", 2);
        let mut mock = ops(first.clone());
        let lease_current = mock.lease_current.clone();
        second.on_product = Some(Rc::new(move || lease_current.set(false)));
        mock.processes.insert(21, second.clone());
        mock.candidates = vec![20, 21];

        let check = enforce(&lease(), "S-1-5-21-1", &mock);

        assert_eq!(check.state, GuardState::Inactive);
        assert_eq!(check.confirmed_termination_count, 1);
        assert!(check.last_terminated_at.is_some());
        assert_eq!(first.terminate_count.get(), 1);
        assert_eq!(second.terminate_count.get(), 0);
    }

    #[test]
    fn stale_lease_or_non_elevated_desktop_never_ends_cc() {
        let cc = process(20, CC_EXE, "S-1-5-21-1", 2);
        let count = cc.terminate_count.clone();
        let mut stale = lease();
        stale.created_at += 1;
        assert_eq!(
            enforce(&stale, "S-1-5-21-1", &ops(cc.clone())).state,
            GuardState::Inactive
        );
        let mut mock = ops(cc);
        mock.processes.get_mut(&10).unwrap().id.elevated = false;
        assert_eq!(
            enforce(&lease(), "S-1-5-21-1", &mock).state,
            GuardState::Inactive
        );
        assert_eq!(count.get(), 0);
    }

    #[test]
    fn other_user_or_session_and_other_name_are_never_ended() {
        for cc in [
            process(20, CC_EXE, "S-1-5-21-2", 2),
            process(20, CC_EXE, "S-1-5-21-1", 3),
            process(20, "other.exe", "S-1-5-21-1", 2),
        ] {
            let count = cc.terminate_count.clone();
            let check = enforce(&lease(), "S-1-5-21-1", &ops(cc));
            assert_eq!(check.state, GuardState::Clear);
            assert!(!check.detected);
            assert_eq!(check.confirmed_termination_count, 0);
            assert_eq!(count.get(), 0);
        }
    }

    #[test]
    fn unknown_product_fails_closed_without_ending_process() {
        let mut cc = process(20, CC_EXE, "S-1-5-21-1", 2);
        cc.product = Ok("Unrelated program".into());
        let count = cc.terminate_count.clone();
        let check = enforce(&lease(), "S-1-5-21-1", &ops(cc));
        assert_eq!(check.state, GuardState::Error);
        assert!(!check.detected);
        assert_eq!(check.confirmed_termination_count, 0);
        assert_eq!(count.get(), 0);
    }

    #[test]
    fn withdrawn_lease_never_closes_candidate() {
        let cc = process(20, CC_EXE, "S-1-5-21-1", 2);
        let count = cc.terminate_count.clone();
        let mock = ops(cc);
        mock.lease_current.set(false);
        assert_eq!(
            enforce(&lease(), "S-1-5-21-1", &mock).state,
            GuardState::Inactive
        );
        assert_eq!(count.get(), 0);
    }

    #[test]
    fn lease_withdrawn_after_product_check_prevents_termination() {
        let mut cc = process(20, CC_EXE, "S-1-5-21-1", 2);
        let count = cc.terminate_count.clone();
        let mock = ops(cc.clone());
        let current = mock.lease_current.clone();
        cc.on_product = Some(Rc::new(move || current.set(false)));
        let mock = MockOps {
            processes: BTreeMap::from([(10, process(10, DESKTOP_EXE, "S-1-5-21-1", 2)), (20, cc)]),
            ..mock
        };
        assert_eq!(
            enforce(&lease(), "S-1-5-21-1", &mock).state,
            GuardState::Inactive
        );
        assert_eq!(count.get(), 0);
    }

    #[test]
    fn desktop_exit_after_product_check_prevents_termination() {
        let mut cc = process(20, CC_EXE, "S-1-5-21-1", 2);
        let count = cc.terminate_count.clone();
        let mock = ops(cc.clone());
        let app_exits = mock.processes.get(&10).unwrap().exits.clone();
        cc.on_product = Some(Rc::new(move || app_exits.set(true)));
        let mut mock = mock;
        mock.processes.insert(20, cc);
        assert_eq!(
            enforce(&lease(), "S-1-5-21-1", &mock).state,
            GuardState::Inactive
        );
        assert_eq!(count.get(), 0);
    }

    #[test]
    fn desktop_product_mismatch_fails_without_ending_process() {
        let cc = process(20, CC_EXE, "S-1-5-21-1", 2);
        let count = cc.terminate_count.clone();
        let mut mock = ops(cc);
        mock.processes.get_mut(&10).unwrap().product = Ok("Another desktop".into());
        assert_eq!(
            enforce(&lease(), "S-1-5-21-1", &mock).state,
            GuardState::Error
        );
        assert_eq!(count.get(), 0);
    }

    #[test]
    fn missing_and_invalid_leases_are_not_accepted() {
        let directory = std::env::temp_dir().join(format!("meter-guard-test-{}", Uuid::new_v4()));
        fs::create_dir(&directory).unwrap();
        let status = directory.join("status.json");
        let lease_path = directory.join(LEASE_NAME);
        assert!(matches!(read_lease(&status), Ok(None)));
        for invalid in [
            "{}".to_owned(),
            format!(
                r#"{{"pid":0,"createdAt":110,"leaseId":"{}"}}"#,
                Uuid::new_v4()
            ),
            format!(
                r#"{{"pid":10,"createdAt":0,"leaseId":"{}"}}"#,
                Uuid::new_v4()
            ),
            format!(
                r#"{{"pid":10,"createdAt":110,"leaseId":"{}","extra":1}}"#,
                Uuid::new_v4()
            ),
            "x".repeat(4097),
        ] {
            fs::write(&lease_path, invalid).unwrap();
            assert!(read_lease(&status).is_err());
        }
        fs::write(&lease_path, serde_json::to_vec(&lease()).unwrap()).unwrap();
        assert_eq!(
            read_lease(&status).unwrap(),
            Some(lease_from_file(&lease_path))
        );
        fs::remove_dir_all(&directory).unwrap();
    }

    fn lease_from_file(path: &Path) -> GuardLease {
        serde_json::from_slice(&fs::read(path).unwrap()).unwrap()
    }
}
