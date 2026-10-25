use super::{
    migration::{migrate, rollback, MigrationStore, RollbackStore},
    same_logon_user, startup_action,
    startup_entry::{EntrySnapshot, StartupEntry},
    uninstall, LaunchMode, StartupAction, TaskStore, CONTEXT_ERROR, READ_ERROR, WRITE_ERROR,
};
use std::{
    ffi::OsString,
    mem::size_of,
    os::windows::ffi::OsStringExt,
    path::{Path, PathBuf},
    ptr,
};
use windows::{
    core::{Interface, BSTR},
    Win32::{
        Foundation::{VARIANT_FALSE, VARIANT_TRUE},
        System::{
            Com::{
                CoCreateInstance, CoInitializeEx, CoUninitialize, CLSCTX_INPROC_SERVER, COINIT,
                COINIT_MULTITHREADED,
            },
            TaskScheduler::{
                IExecAction, ILogonTrigger, IRegisteredTask, ITaskFolder, ITaskService,
                TaskScheduler, TASK_ACTION_EXEC, TASK_LOGON_INTERACTIVE_TOKEN,
                TASK_RUNLEVEL_HIGHEST, TASK_RUNLEVEL_LUA, TASK_TRIGGER_LOGON,
            },
            Variant::VARIANT,
        },
    },
};
use windows_sys::Win32::{
    Foundation::{
        CloseHandle, GetLastError, LocalFree, ERROR_INSUFFICIENT_BUFFER, HANDLE,
    },
    Security::{
        Authorization::{ConvertSidToStringSidW, ConvertStringSidToSidW},
        EqualSid, GetLengthSid, GetTokenInformation, IsValidSid, LookupAccountNameW,
        SidTypeUnknown, TokenUser, PSID, SECURITY_MAX_SID_SIZE, TOKEN_QUERY, TOKEN_USER,
    },
    System::{
        Registry::{
            RegCloseKey, RegCreateKeyExW, RegGetValueW, RegSetValueExW, HKEY, HKEY_CURRENT_USER,
            HKEY_LOCAL_MACHINE, KEY_SET_VALUE, REG_DWORD, RRF_RT_REG_DWORD,
        },
        Threading::{
            GetCurrentProcess, OpenProcess, OpenProcessToken, PROCESS_QUERY_LIMITED_INFORMATION,
        },
    },
    UI::{
        WindowsAndMessaging::{
            GetShellWindow, GetWindowThreadProcessId, MessageBoxW, MB_ICONEXCLAMATION, MB_OK,
        },
    },
};

const IDENTITY: &str = "com.codexquotaguard.desktop.autostart";
const ARGUMENT: &str = "--autostart-launch";
const PATH_ERROR: &str = "无法读取客户端安装路径，请检查安装文件。";
const OWNERSHIP_ERROR: &str =
    "开机自启动任务与当前用户或安装目录不匹配，已阻止自动修改，请检查旧版本残留任务。";
const PREFERENCE_ERROR: &str = "无法保存开机自启动安装偏好，请检查 Windows 用户设置权限。";
const PREFERENCE_KEY: &str = "Software\\Codex Quota Guard\\Desktop";

struct Handle(HANDLE);
impl Drop for Handle {
    fn drop(&mut self) {
        // SAFETY: This wrapper contains a successfully opened owned handle.
        unsafe { CloseHandle(self.0) };
    }
}

struct ComApartment;
impl ComApartment {
    fn new() -> Result<Self, &'static str> {
        Self::initialize(COINIT_MULTITHREADED)
    }

    fn initialize(model: COINIT) -> Result<Self, &'static str> {
        // SAFETY: All scheduler operations and COM objects stay on this thread.
        unsafe { CoInitializeEx(None, model) }
            .ok()
            .map_err(|_| READ_ERROR)?;
        Ok(Self)
    }
}

struct OwnedTask {
    registered: IRegisteredTask,
}
impl Drop for ComApartment {
    fn drop(&mut self) {
        // SAFETY: Only constructed after a successful CoInitializeEx.
        unsafe { CoUninitialize() };
    }
}

pub(super) struct WindowsTasks {
    sid: String,
    executable: PathBuf,
}

struct LegacyTasks {
    folder: ITaskFolder,
    sid: String,
    executable: PathBuf,
    // COM interfaces are dropped before the apartment is uninitialized.
    _apartment: ComApartment,
}

impl WindowsTasks {
    pub(super) fn connect() -> Result<Self, &'static str> {
        let sid = current_desktop_user()?;
        let executable = std::env::current_exe().map_err(|_| PATH_ERROR)?;
        Ok(Self { sid, executable })
    }

    fn entry(&self) -> Result<StartupEntry, &'static str> {
        StartupEntry::new(&self.executable)
    }

    fn legacy(&self) -> Result<LegacyTasks, &'static str> {
        LegacyTasks::connect(&self.sid, &self.executable)
    }
}

impl LegacyTasks {
    fn connect(sid: &str, executable: &Path) -> Result<Self, &'static str> {
        let apartment = ComApartment::new()?;
        // SAFETY: COM is initialized above and all argument values are owned.
        let service: ITaskService =
            unsafe { CoCreateInstance(&TaskScheduler, None, CLSCTX_INPROC_SERVER) }
                .map_err(|_| READ_ERROR)?;
        let empty = VARIANT::default();
        unsafe { service.Connect(&empty, &empty, &empty, &empty) }.map_err(|_| READ_ERROR)?;
        let folder = unsafe { service.GetFolder(&BSTR::from("\\")) }.map_err(|_| READ_ERROR)?;
        Ok(Self {
            folder,
            sid: sid.to_owned(),
            executable: executable.to_owned(),
            _apartment: apartment,
        })
    }

    fn name(&self) -> BSTR {
        BSTR::from(format!("{IDENTITY}.{}", self.sid))
    }

    fn task(&self) -> Result<Option<OwnedTask>, &'static str> {
        // SAFETY: The folder is connected to the local scheduler; name is fixed
        // except for a SID obtained from a Windows token, never renderer input.
        match unsafe { self.folder.GetTask(&self.name()) } {
            Ok(task) => {
                self.validate_identity(&task)?;
                Ok(Some(OwnedTask { registered: task }))
            }
            Err(error) if error.code().0 as u32 == 0x80070002 => Ok(None),
            Err(_) => Err(READ_ERROR),
        }
    }

    fn validate_identity(&self, task: &IRegisteredTask) -> Result<(), &'static str> {
        let sid = self.sid.as_str();
        let result = (|| -> windows::core::Result<bool> {
            // SAFETY: Every out parameter is initialized; COM owns the returned
            // interfaces and BSTR buffers. This function only reads task metadata.
            unsafe {
                let definition = task.Definition()?;
                let mut marker = BSTR::default();
                definition.RegistrationInfo()?.URI(&mut marker)?;
                let principal = definition.Principal()?;
                let mut user = BSTR::default();
                principal.UserId(&mut user)?;
                let mut logon = TASK_LOGON_INTERACTIVE_TOKEN;
                principal.LogonType(&mut logon)?;
                let mut level = TASK_RUNLEVEL_HIGHEST;
                principal.RunLevel(&mut level)?;
                let actions = definition.Actions()?;
                let mut count = 0;
                actions.Count(&mut count)?;
                if count != 1 {
                    return Ok(false);
                }
                let action = actions.get_Item(1)?;
                let mut action_type = TASK_ACTION_EXEC;
                action.Type(&mut action_type)?;
                if action_type != TASK_ACTION_EXEC {
                    return Ok(false);
                }
                let action: IExecAction = action.cast()?;
                let mut executable = BSTR::default();
                let mut arguments = BSTR::default();
                let mut directory = BSTR::default();
                action.Path(&mut executable)?;
                action.Arguments(&mut arguments)?;
                action.WorkingDirectory(&mut directory)?;
                let triggers = definition.Triggers()?;
                triggers.Count(&mut count)?;
                if count != 1 {
                    return Ok(false);
                }
                let trigger = triggers.get_Item(1)?;
                let mut trigger_type = TASK_TRIGGER_LOGON;
                trigger.Type(&mut trigger_type)?;
                if trigger_type != TASK_TRIGGER_LOGON {
                    return Ok(false);
                }
                let trigger: ILogonTrigger = trigger.cast()?;
                let mut trigger_user = BSTR::default();
                trigger.UserId(&mut trigger_user)?;
                let matches = task.Path()? == format!("\\{IDENTITY}.{sid}")
                    && same_registration_uri(&marker, sid)
                    && same_account_sid(&user, sid)
                    && same_account_sid(&trigger_user, sid)
                    && logon == TASK_LOGON_INTERACTIVE_TOKEN
                    && same_path_text(&executable.to_string(), &self.executable)
                    && self
                        .executable
                        .parent()
                        .is_some_and(|parent| same_path_text(&directory.to_string(), parent))
                    && arguments == ARGUMENT;
                if !matches {
                    return Ok(false);
                }
                Ok(level == TASK_RUNLEVEL_LUA || level == TASK_RUNLEVEL_HIGHEST)
            }
        })();
        match result {
            Ok(true) => Ok(()),
            Ok(false) => Err(OWNERSHIP_ERROR),
            Err(_) => Err(READ_ERROR),
        }
    }
}

impl TaskStore for WindowsTasks {
    fn read(&self) -> Result<Option<bool>, &'static str> {
        self.entry()?.read()
    }

    fn read_legacy(&self) -> Result<Option<bool>, &'static str> {
        self.legacy()?
            .task()?
            .map(|task| task_enabled(&task.registered))
            .transpose()
    }

    fn registration_override(&self) -> Result<Option<bool>, &'static str> {
        self.entry()?.registration_override()
    }

    fn write(&mut self, enabled: bool) -> Result<(), &'static str> {
        let mut migration = NativeMigration {
            entry: self.entry()?,
            legacy: self.legacy()?,
            last_entry: None,
        };
        migrate(&mut migration, enabled)
    }

    fn migrate(&mut self, enabled: bool) -> Result<(), &'static str> {
        let legacy = self.legacy()?;
        legacy.task()?;
        let entry = self.entry()?;
        let snapshot = entry.snapshot()?;
        if snapshot.enabled == Some(enabled) && !enabled {
            // Preserve Task Manager's OFF record byte-for-byte, checking it
            // before deletion. Do not restore over a later external change.
            entry.ensure_matches(&snapshot)?;
            legacy.remove()?;
            if legacy.task()?.is_some() {
                return Err(super::VERIFY_ERROR);
            }
            entry.ensure_matches(&snapshot)?;
            return Ok(());
        }
        let mut migration = NativeMigration {
            entry,
            legacy,
            last_entry: None,
        };
        migrate(&mut migration, enabled)
    }

    fn remove(&mut self) -> Result<(), &'static str> {
        let entry = self.entry()?;
        entry.snapshot()?;
        let legacy = self.legacy()?;
        legacy.task()?;
        legacy.remove()?;
        if legacy.task()?.is_some() {
            return Err(super::VERIFY_ERROR);
        }
        entry.remove()?;
        Ok(())
    }

    fn install_preference(&self) -> Result<Option<bool>, &'static str> {
        match read_preference(HKEY_CURRENT_USER, &self.sid)? {
            Some(enabled) => Ok(Some(enabled)),
            // Only a boolean is migrated from the previous admin-controlled key.
            // It is never treated as permission to launch or create a task.
            None => read_preference(HKEY_LOCAL_MACHINE, &self.sid),
        }
    }

    fn remember(&mut self, enabled: bool) -> Result<(), &'static str> {
        remember_sid(&self.sid, enabled)
    }
}

impl LegacyTasks {
    fn remove(&self) -> Result<(), &'static str> {
        if self.task()?.is_some() {
            // SAFETY: task() verified the exact product/SID/action/trigger. This
            // only deletes an owned old task; ordinary startup never creates one.
            unsafe { self.folder.DeleteTask(&self.name(), 0) }.map_err(|_| WRITE_ERROR)?;
        }
        Ok(())
    }
}

struct LegacySnapshot {
    registered_enabled: bool,
    actual_enabled: bool,
}

struct NativeMigration {
    entry: StartupEntry,
    legacy: LegacyTasks,
    last_entry: Option<EntrySnapshot>,
}

impl MigrationStore for NativeMigration {
    type Snapshot = (EntrySnapshot, Option<LegacySnapshot>);

    fn checkpoint(&mut self) -> Result<Self::Snapshot, &'static str> {
        let entry = self.entry.snapshot()?;
        let legacy = self
            .legacy
            .task()?
            .map(|task| -> Result<LegacySnapshot, &'static str> {
                Ok(LegacySnapshot {
                    // SAFETY: The task has passed the complete identity validation.
                    registered_enabled: unsafe { task.registered.Enabled() }
                        .map_err(|_| READ_ERROR)?
                        .as_bool(),
                    actual_enabled: task_enabled(&task.registered)?,
                })
            })
            .transpose()?;
        self.last_entry = Some(entry.clone());
        Ok((entry, legacy))
    }

    fn stage_disabled(&mut self) -> Result<(), &'static str> {
        let snapshot = self.last_entry.as_mut().ok_or(super::VERIFY_ERROR)?;
        self.entry.set(false, snapshot)
    }

    fn disable_legacy(&mut self) -> Result<(), &'static str> {
        if let Some(task) = self.legacy.task()? {
            // SAFETY: Only the freshly verified old task's enabled flag changes.
            unsafe { task.registered.SetEnabled(VARIANT_FALSE) }.map_err(|_| WRITE_ERROR)?;
            if task_enabled(&task.registered)? {
                return Err(super::VERIFY_ERROR);
            }
        }
        Ok(())
    }

    fn activate(&mut self, enabled: bool) -> Result<(), &'static str> {
        let snapshot = self.last_entry.as_mut().ok_or(super::VERIFY_ERROR)?;
        self.entry.set(enabled, snapshot)
    }

    fn remove_legacy(&mut self) -> Result<(), &'static str> {
        self.legacy.remove()
    }

    fn verify_removed(&self) -> Result<(), &'static str> {
        if self.legacy.task()?.is_none() {
            Ok(())
        } else {
            Err(super::VERIFY_ERROR)
        }
    }

    fn rollback(&mut self, (entry, legacy): Self::Snapshot) -> Result<(), &'static str> {
        rollback(self, &(entry, legacy))
    }
}

impl RollbackStore for NativeMigration {
    type Snapshot = (EntrySnapshot, Option<LegacySnapshot>);

    fn hold_disabled(&mut self) -> Result<(), &'static str> {
        // Keep the new entry inactive before restoring an old available task.
        let expected = self.last_entry.as_mut().ok_or(super::VERIFY_ERROR)?;
        self.entry.hold_disabled(expected)
    }

    fn restore_run(&mut self, (entry, _): &Self::Snapshot) -> Result<(), &'static str> {
        let expected = self.last_entry.as_mut().ok_or(super::VERIFY_ERROR)?;
        self.entry.restore_run(entry, expected)
    }

    fn remove_run(&mut self) -> Result<(), &'static str> {
        let expected = self.last_entry.as_mut().ok_or(super::VERIFY_ERROR)?;
        self.entry.remove_run(expected)
    }

    fn restore_legacy(&mut self, (_, legacy): &Self::Snapshot) -> Result<(), &'static str> {
        if let Some(snapshot) = legacy.as_ref() {
            let task = self
                .legacy
                .task()?
                .ok_or(super::migration::ROLLBACK_ERROR)?;
            // SAFETY: This read-only task was freshly validated above.
            let actual = unsafe { task.registered.Enabled() }
                .map_err(|_| READ_ERROR)?
                .as_bool();
            if actual == snapshot.registered_enabled {
                return if task_enabled(&task.registered)? == snapshot.actual_enabled {
                    Ok(())
                } else {
                    Err(super::startup_entry::CONCURRENT_ERROR)
                };
            }
            if actual {
                return Err(super::startup_entry::CONCURRENT_ERROR);
            }
            // SAFETY: Identity is validated again; restore only its original flag.
            unsafe {
                task.registered.SetEnabled(if snapshot.registered_enabled {
                    VARIANT_TRUE
                } else {
                    VARIANT_FALSE
                })
            }
            .map_err(|_| WRITE_ERROR)?;
            if task_enabled(&task.registered)? != snapshot.actual_enabled {
                return Err(super::VERIFY_ERROR);
            }
        }
        Ok(())
    }

    fn restoring_available_legacy((_, legacy): &Self::Snapshot) -> bool {
        // Remove Run before restoring an available old task, so status can
        // observe the old entry directly and a new OFF entry cannot hide it.
        legacy
            .as_ref()
            .is_some_and(|snapshot| snapshot.actual_enabled)
    }

    fn restore_approval(&mut self, (entry, _): &Self::Snapshot) -> Result<(), &'static str> {
        let expected = self.last_entry.as_mut().ok_or(super::VERIFY_ERROR)?;
        self.entry.restore_approval(entry, expected)
    }
}

pub(super) fn remove_installation_tasks() -> Result<(), &'static str> {
    let mut tasks = WindowsTasks::connect()?;
    uninstall(&mut tasks, false)
}

fn task_enabled(task: &IRegisteredTask) -> Result<bool, &'static str> {
    // SAFETY: Initialized out parameters receive read-only task metadata.
    unsafe {
        (|| -> windows::core::Result<bool> {
            let definition = task.Definition()?;
            let mut trigger_enabled = VARIANT_FALSE;
            definition
                .Triggers()?
                .get_Item(1)?
                .Enabled(&mut trigger_enabled)?;
            let mut settings_enabled = VARIANT_FALSE;
            definition.Settings()?.Enabled(&mut settings_enabled)?;
            Ok(
                task.Enabled()?.as_bool()
                    && trigger_enabled.as_bool()
                    && settings_enabled.as_bool(),
            )
        })()
    }
    .map_err(|_| READ_ERROR)
}

fn read_preference(root: HKEY, sid: &str) -> Result<Option<bool>, &'static str> {
    let key = wide(PREFERENCE_KEY);
    let value = wide(sid);
    let mut enabled = 0u32;
    let mut size = size_of::<u32>() as u32;
    // SAFETY: Reads only a fixed product boolean named by the inspected token
    // SID. No paths, users, or command arguments are recovered from the registry.
    let code = unsafe {
        RegGetValueW(
            root,
            key.as_ptr(),
            value.as_ptr(),
            RRF_RT_REG_DWORD,
            ptr::null_mut(),
            (&mut enabled as *mut u32).cast(),
            &mut size,
        )
    };
    match code {
        0 if size == 4 && enabled <= 1 => Ok(Some(enabled != 0)),
        2 => Ok(None),
        _ => Err(PREFERENCE_ERROR),
    }
}

fn remember_sid(sid: &str, enabled: bool) -> Result<(), &'static str> {
    let mut key = ptr::null_mut();
    let path = wide(PREFERENCE_KEY);
    // SAFETY: Writes one fixed product boolean in this process user's hive.
    // All callers first verify this is the current interactive desktop user.
    let code = unsafe {
        RegCreateKeyExW(
            HKEY_CURRENT_USER,
            path.as_ptr(),
            0,
            ptr::null(),
            0,
            KEY_SET_VALUE,
            ptr::null(),
            &mut key,
            ptr::null_mut(),
        )
    };
    if code != 0 {
        return Err(PREFERENCE_ERROR);
    }
    let data = u32::from(enabled).to_le_bytes();
    let value = wide(sid);
    let code = unsafe { RegSetValueExW(key, value.as_ptr(), 0, REG_DWORD, data.as_ptr(), 4) };
    unsafe { RegCloseKey(key) };
    if code != 0 {
        return Err(PREFERENCE_ERROR);
    }
    Ok(())
}

fn wide(value: &str) -> Vec<u16> {
    value.encode_utf16().chain(Some(0)).collect()
}

fn same_path_text(value: &str, path: &Path) -> bool {
    value.eq_ignore_ascii_case(&path.to_string_lossy())
}

fn same_registration_uri(value: &BSTR, sid: &str) -> bool {
    // Task Scheduler may replace our URI with the registered task's root path.
    // Both alternatives remain fixed to this product and this exact user SID.
    value == &format!("{IDENTITY}/{sid}") || value == &format!("\\{IDENTITY}.{sid}")
}

struct LocalSid(PSID);

impl LocalSid {
    fn parse(value: &str) -> Option<Self> {
        if value.is_empty() || value.contains('\0') || value.len() > 32768 {
            return None;
        }
        let value = wide(value);
        let mut sid = ptr::null_mut();
        // SAFETY: The input is NUL terminated and the initialized out pointer
        // receives a LocalAlloc-owned SID only on success.
        if unsafe { ConvertStringSidToSidW(value.as_ptr(), &mut sid) } == 0 {
            return None;
        }
        let sid = Self(sid);
        // SAFETY: ConvertStringSidToSidW returned this allocation successfully.
        (!sid.0.is_null() && unsafe { IsValidSid(sid.0) } != 0).then_some(sid)
    }
}

impl Drop for LocalSid {
    fn drop(&mut self) {
        // SAFETY: This pointer is owned memory from ConvertStringSidToSidW.
        unsafe { LocalFree(self.0) };
    }
}

fn same_account_sid(account: &BSTR, expected_sid: &str) -> bool {
    // A BSTR may contain embedded NULs or invalid UTF-16. Do not let conversion
    // or Win32's NUL-terminated input silently change the identity being checked.
    if account.is_empty() || account.len() > 32767 || account.contains(&0) {
        return false;
    }
    let Ok(account) = String::try_from(account) else {
        return false;
    };
    let Some(expected) = LocalSid::parse(expected_sid) else {
        return false;
    };
    if account
        .get(..2)
        .is_some_and(|prefix| prefix.eq_ignore_ascii_case("S-"))
    {
        // Malformed SID strings must not fall back to account-name resolution.
        return LocalSid::parse(&account).is_some_and(|actual| {
            // SAFETY: Both pointers own validated SIDs and remain alive here.
            unsafe { EqualSid(actual.0, expected.0) != 0 }
        });
    }

    let account = wide(&account);
    let mut sid_size = 0;
    let mut domain_size = 0;
    let mut usage = SidTypeUnknown;
    // SAFETY: NULL output buffers with zero capacities ask Windows for sizes;
    // lookup begins on the local system and does not trust a username suffix.
    let result = unsafe {
        LookupAccountNameW(
            ptr::null(),
            account.as_ptr(),
            ptr::null_mut(),
            &mut sid_size,
            ptr::null_mut(),
            &mut domain_size,
            &mut usage,
        )
    };
    if result != 0
        || unsafe { GetLastError() } != ERROR_INSUFFICIENT_BUFFER
        || !(8..=SECURITY_MAX_SID_SIZE).contains(&sid_size)
        || domain_size > 32768
    {
        return false;
    }
    let sid_capacity = sid_size;
    let domain_capacity = domain_size.max(1);
    let mut sid = vec![0usize; (sid_capacity as usize).div_ceil(size_of::<usize>())];
    let mut domain = vec![0u16; domain_capacity as usize];
    domain_size = domain_capacity;
    // SAFETY: The SID buffer is aligned and contains at least sid_capacity
    // bytes; the UTF-16 domain buffer contains domain_capacity code units.
    let result = unsafe {
        LookupAccountNameW(
            ptr::null(),
            account.as_ptr(),
            sid.as_mut_ptr().cast(),
            &mut sid_size,
            domain.as_mut_ptr(),
            &mut domain_size,
            &mut usage,
        )
    };
    let sid = sid.as_mut_ptr().cast();
    if result == 0 || sid_size > sid_capacity || domain_size > domain_capacity {
        return false;
    }
    // SAFETY: A successful lookup initialized a SID in an aligned buffer of at
    // least eight bytes. Validate its header and full size before EqualSid.
    unsafe {
        IsValidSid(sid) != 0 && GetLengthSid(sid) <= sid_capacity && EqualSid(sid, expected.0) != 0
    }
}

fn token(process: HANDLE) -> Result<Handle, &'static str> {
    let mut handle = ptr::null_mut();
    // SAFETY: process is a valid pseudo-handle or owned process query handle.
    if unsafe { OpenProcessToken(process, TOKEN_QUERY, &mut handle) } == 0 {
        return Err(CONTEXT_ERROR);
    }
    Ok(Handle(handle))
}

fn token_sid(token: &Handle) -> Result<String, &'static str> {
    let mut length = 0;
    // SAFETY: This call asks Windows for the required buffer length.
    unsafe { GetTokenInformation(token.0, TokenUser, ptr::null_mut(), 0, &mut length) };
    if length < size_of::<TOKEN_USER>() as u32 || length > 4096 {
        return Err(CONTEXT_ERROR);
    }
    let mut buffer = vec![0usize; (length as usize).div_ceil(size_of::<usize>())];
    // SAFETY: The aligned buffer has at least `length` bytes.
    if unsafe {
        GetTokenInformation(
            token.0,
            TokenUser,
            buffer.as_mut_ptr().cast(),
            length,
            &mut length,
        )
    } == 0
    {
        return Err(CONTEXT_ERROR);
    }
    // SAFETY: A successful query initialized TOKEN_USER and its SID in buffer.
    let user = unsafe { &*buffer.as_ptr().cast::<TOKEN_USER>() };
    sid_text(user.User.Sid).map_err(|_| CONTEXT_ERROR)
}

fn current_desktop_user() -> Result<String, &'static str> {
    let process = token(unsafe { GetCurrentProcess() })?;
    let sid = token_sid(&process)?;
    // GetShellWindow is scoped to our interactive desktop/session. Reading its
    // token detects over-the-shoulder UAC credentials for a different account.
    let shell = unsafe { GetShellWindow() };
    if shell.is_null() {
        return Err(CONTEXT_ERROR);
    }
    let mut pid = 0;
    unsafe { GetWindowThreadProcessId(shell, &mut pid) };
    if pid == 0 {
        return Err(CONTEXT_ERROR);
    }
    let handle = unsafe { OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, 0, pid) };
    if handle.is_null() {
        return Err(CONTEXT_ERROR);
    }
    let shell_process = Handle(handle);
    let desktop_sid = token_sid(&token(shell_process.0)?)?;
    if !same_logon_user(&sid, &desktop_sid) {
        return Err(CONTEXT_ERROR);
    }
    Ok(sid)
}

fn sid_text(sid: *mut core::ffi::c_void) -> Result<String, &'static str> {
    // SAFETY: Callers pass a SID from a successful Windows security API.
    if sid.is_null() || unsafe { IsValidSid(sid) } == 0 {
        return Err(PATH_ERROR);
    }
    let mut output = ptr::null_mut();
    if unsafe { ConvertSidToStringSidW(sid, &mut output) } == 0 {
        return Err(PATH_ERROR);
    }
    let value = wide_string(output);
    // SAFETY: ConvertSidToStringSidW allocated this buffer with LocalAlloc.
    unsafe { LocalFree(output.cast()) };
    value.map(|value| value.to_string_lossy().into_owned())
}

fn wide_string(value: *const u16) -> Result<OsString, &'static str> {
    if value.is_null() {
        return Err(PATH_ERROR);
    }
    let mut length = 0;
    // SAFETY: This helper receives a NUL-terminated Windows API result and bounds
    // the scan to Windows' maximum path length (also bounds malformed responses).
    while length < 32768 && unsafe { *value.add(length) } != 0 {
        length += 1;
    }
    if length == 32768 {
        return Err(PATH_ERROR);
    }
    Ok(OsString::from_wide(unsafe {
        std::slice::from_raw_parts(value, length)
    }))
}

fn show_startup_error(message: &'static str) {
    let title = wide("Codex Quota Guard");
    let message = wide(message);
    // SAFETY: Both fixed application strings remain NUL terminated and alive
    // for this synchronous call; no renderer/registry text is displayed.
    unsafe {
        MessageBoxW(
            ptr::null_mut(),
            message.as_ptr(),
            title.as_ptr(),
            MB_OK | MB_ICONEXCLAMATION,
        )
    };
}

pub(super) fn handle_startup(mode: LaunchMode) -> Option<i32> {
    // Starting the desktop client never requests administrator approval.
    let action = if mode == LaunchMode::Desktop {
        startup_action(mode, false, true)
    } else {
        current_desktop_user().and_then(|_| startup_action(mode, false, true))
    };
    let action = match action {
        Ok(action) => action,
        Err(message) => {
            show_startup_error(message);
            return Some(2);
        }
    };
    match action {
        StartupAction::Initialize => {
            None
        }
        StartupAction::Uninstall => match remove_installation_tasks() {
            Ok(()) => Some(0),
            Err(message) => {
                show_startup_error(message);
                Some(2)
            }
        },
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn task_identity_accepts_current_account_names_as_the_same_sid() {
        let handle = token(unsafe { GetCurrentProcess() }).expect("current process token");
        let sid = token_sid(&handle).expect("current process SID");
        let user = std::env::var("USERNAME").expect("current Windows username");
        let domain = std::env::var("USERDOMAIN").expect("current Windows user domain");

        assert!(same_account_sid(&BSTR::from(&sid), &sid));
        assert!(same_account_sid(&BSTR::from(&user), &sid));
        assert!(same_account_sid(
            &BSTR::from(format!("{domain}\\{user}")),
            &sid
        ));
    }

    #[test]
    fn task_identity_rejects_different_or_invalid_accounts() {
        let expected = "S-1-5-18";
        for account in [
            "S-1-5-19",
            "S-1-not-a-sid",
            "codex-quota-guard-no-such-account-5a6d00e8",
            "",
            "S-1-5-18\0other-account",
        ] {
            assert!(
                !same_account_sid(&BSTR::from(account), expected),
                "{account:?}"
            );
        }
        assert!(!same_account_sid(&BSTR::from_wide(&[0xd800]), expected));
        assert!(!same_account_sid(&BSTR::from(expected), "S-1-invalid"));
    }

    #[test]
    fn task_identity_accepts_only_product_marker_or_exact_canonical_uri() {
        let sid = "S-1-5-21-100-200-300-1001";
        for uri in [format!("{IDENTITY}/{sid}"), format!("\\{IDENTITY}.{sid}")] {
            assert!(same_registration_uri(&BSTR::from(&uri), sid), "{uri}");
        }
        for uri in [
            String::new(),
            format!("{IDENTITY}/S-1-5-21-100-200-300-1002"),
            format!("\\{IDENTITY}.S-1-5-21-100-200-300-1002"),
            format!("\\folder\\{IDENTITY}.{sid}"),
            format!("{IDENTITY}.{sid}"),
            format!("\\{IDENTITY}.{sid}-other"),
            format!("\\{IDENTITY}.{sid}\0"),
        ] {
            assert!(!same_registration_uri(&BSTR::from(&uri), sid), "{uri:?}");
        }
    }

    #[test]
    #[ignore = "read-only installed-task check; requires CQG_AUTOSTART_TEST_EXECUTABLE and CQG_AUTOSTART_TEST_ENABLED"]
    fn read_installed_task_without_mutation() {
        let executable = PathBuf::from(
            std::env::var_os("CQG_AUTOSTART_TEST_EXECUTABLE")
                .expect("explicit installed executable path"),
        );
        assert!(executable.is_absolute() && executable.is_file());
        let enabled = match std::env::var("CQG_AUTOSTART_TEST_ENABLED").as_deref() {
            Ok("true") => true,
            Ok("false") => false,
            _ => panic!("CQG_AUTOSTART_TEST_ENABLED must be true or false"),
        };
        let mut tasks = WindowsTasks::connect().expect("read-only current-user startup reader");
        // The unit-test executable is not the installation whose entry is checked.
        // Only the expected path in this in-memory reader is changed.
        tasks.executable = executable;

        assert_eq!(tasks.read(), Ok(None));
        assert_eq!(tasks.read_legacy(), Ok(Some(enabled)));
        let status = super::super::read_status(&tasks).expect("installed startup entry status");
        assert!(status.supported);
        assert_eq!(status.enabled, enabled);
    }
}
