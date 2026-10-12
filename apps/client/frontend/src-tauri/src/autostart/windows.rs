use super::{
    initialize_default, same_logon_user, startup_action, uninstall, LaunchMode, StartupAction,
    TaskStore, CONTEXT_ERROR, READ_ERROR, WRITE_ERROR,
};
use std::{
    ffi::OsString,
    mem::size_of,
    os::windows::ffi::{OsStrExt, OsStringExt},
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
                COINIT_APARTMENTTHREADED, COINIT_MULTITHREADED,
            },
            TaskScheduler::{
                IExecAction, ILogonTrigger, IRegisteredTask, ITaskFolder, ITaskService,
                TaskScheduler, TASK_ACTION_EXEC, TASK_CREATE_OR_UPDATE,
                TASK_DONT_ADD_PRINCIPAL_ACE, TASK_INSTANCES_IGNORE_NEW,
                TASK_LOGON_INTERACTIVE_TOKEN, TASK_RUNLEVEL_HIGHEST, TASK_RUNLEVEL_LUA,
                TASK_TRIGGER_LOGON,
            },
            Variant::VARIANT,
        },
    },
};
use windows_sys::Win32::{
    Foundation::{
        CloseHandle, GetLastError, LocalFree, ERROR_CANCELLED, ERROR_INSUFFICIENT_BUFFER, HANDLE,
    },
    Security::{
        Authorization::{ConvertSidToStringSidW, ConvertStringSidToSidW},
        EqualSid, GetLengthSid, GetTokenInformation, IsValidSid, LookupAccountNameW,
        SidTypeUnknown, TokenElevation, TokenUser, PSID, SECURITY_MAX_SID_SIZE, TOKEN_ELEVATION,
        TOKEN_QUERY, TOKEN_USER,
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
        Shell::{ShellExecuteExW, SEE_MASK_FLAG_NO_UI, SEE_MASK_NOASYNC, SHELLEXECUTEINFOW},
        WindowsAndMessaging::{
            GetShellWindow, GetWindowThreadProcessId, MessageBoxW, MB_ICONEXCLAMATION, MB_OK,
            SW_SHOWNORMAL,
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

#[derive(Clone, Copy, Eq, PartialEq)]
enum TaskKind {
    Limited,
    LegacyHighest,
}

struct OwnedTask {
    registered: IRegisteredTask,
    kind: TaskKind,
}
impl Drop for ComApartment {
    fn drop(&mut self) {
        // SAFETY: Only constructed after a successful CoInitializeEx.
        unsafe { CoUninitialize() };
    }
}

pub(super) struct WindowsTasks {
    service: ITaskService,
    folder: ITaskFolder,
    sid: String,
    executable: PathBuf,
    // COM interfaces are dropped before the apartment is uninitialized.
    _apartment: ComApartment,
}

impl WindowsTasks {
    pub(super) fn connect() -> Result<Self, &'static str> {
        let sid = current_desktop_user()?;
        Self::connect_for_sid(sid)
    }

    fn connect_for_sid(sid: String) -> Result<Self, &'static str> {
        let executable = std::env::current_exe().map_err(|_| PATH_ERROR)?;
        let apartment = ComApartment::new()?;
        // SAFETY: COM is initialized above and all argument values are owned.
        let service: ITaskService =
            unsafe { CoCreateInstance(&TaskScheduler, None, CLSCTX_INPROC_SERVER) }
                .map_err(|_| READ_ERROR)?;
        let empty = VARIANT::default();
        unsafe { service.Connect(&empty, &empty, &empty, &empty) }.map_err(|_| READ_ERROR)?;
        let folder = unsafe { service.GetFolder(&BSTR::from("\\")) }.map_err(|_| READ_ERROR)?;
        Ok(Self {
            service,
            folder,
            sid,
            executable,
            _apartment: apartment,
        })
    }

    fn name(&self) -> BSTR {
        BSTR::from(format!("{IDENTITY}.{}", self.sid))
    }

    fn marker(&self) -> String {
        format!("{IDENTITY}/{}", self.sid)
    }

    fn task(&self) -> Result<Option<OwnedTask>, &'static str> {
        // SAFETY: The folder is connected to the local scheduler; name is fixed
        // except for a SID obtained from a Windows token, never renderer input.
        match unsafe { self.folder.GetTask(&self.name()) } {
            Ok(task) => {
                let kind = self.validate_identity(&task)?;
                Ok(Some(OwnedTask {
                    registered: task,
                    kind,
                }))
            }
            Err(error) if error.code().0 as u32 == 0x80070002 => Ok(None),
            Err(_) => Err(READ_ERROR),
        }
    }

    fn validate_identity(&self, task: &IRegisteredTask) -> Result<TaskKind, &'static str> {
        let sid = self.sid.as_str();
        let result = (|| -> windows::core::Result<Option<TaskKind>> {
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
                    return Ok(None);
                }
                let action = actions.get_Item(1)?;
                let mut action_type = TASK_ACTION_EXEC;
                action.Type(&mut action_type)?;
                if action_type != TASK_ACTION_EXEC {
                    return Ok(None);
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
                    return Ok(None);
                }
                let trigger = triggers.get_Item(1)?;
                let mut trigger_type = TASK_TRIGGER_LOGON;
                trigger.Type(&mut trigger_type)?;
                if trigger_type != TASK_TRIGGER_LOGON {
                    return Ok(None);
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
                    return Ok(None);
                }
                Ok(if level == TASK_RUNLEVEL_LUA {
                    Some(TaskKind::Limited)
                } else if level == TASK_RUNLEVEL_HIGHEST {
                    Some(TaskKind::LegacyHighest)
                } else {
                    None
                })
            }
        })();
        match result {
            Ok(Some(kind)) => Ok(kind),
            Ok(None) => Err(OWNERSHIP_ERROR),
            Err(_) => Err(READ_ERROR),
        }
    }
}

impl TaskStore for WindowsTasks {
    fn read(&self) -> Result<Option<bool>, &'static str> {
        self.task()?
            .filter(|task| task.kind == TaskKind::Limited)
            .map(|task| task_enabled(&task.registered))
            .transpose()
    }

    fn read_legacy(&self) -> Result<Option<bool>, &'static str> {
        self.task()?
            .filter(|task| task.kind == TaskKind::LegacyHighest)
            .map(|task| task_enabled(&task.registered))
            .transpose()
    }

    fn write(&mut self, enabled: bool) -> Result<(), &'static str> {
        if !is_elevated()? {
            return Err(WRITE_ERROR);
        }
        // Validate an existing entry before an atomic update. This also migrates
        // a verified legacy Highest task to LUA, including its OFF choice.
        self.task()?;
        let directory = self.executable.parent().ok_or(PATH_ERROR)?;
        // SAFETY: Definition properties are populated only with fixed values,
        // the current token SID, and inspected installation paths. No task is run.
        let result = unsafe {
            (|| -> windows::core::Result<()> {
                let definition = self.service.NewTask(0)?;
                let information = definition.RegistrationInfo()?;
                information.SetURI(&BSTR::from(self.marker()))?;
                information.SetAuthor(&BSTR::from("Codex Quota Guard"))?;
                information.SetDescription(&BSTR::from(
                    "当前 Windows 用户登录时启动 Codex Quota Guard；可在客户端设置中关闭。",
                ))?;
                let principal = definition.Principal()?;
                principal.SetUserId(&BSTR::from(&self.sid))?;
                principal.SetLogonType(TASK_LOGON_INTERACTIVE_TOKEN)?;
                principal.SetRunLevel(TASK_RUNLEVEL_LUA)?;
                let trigger: ILogonTrigger =
                    definition.Triggers()?.Create(TASK_TRIGGER_LOGON)?.cast()?;
                trigger.SetEnabled(VARIANT_TRUE)?;
                trigger.SetUserId(&BSTR::from(&self.sid))?;
                trigger.SetDelay(&BSTR::from("PT5S"))?;
                let action: IExecAction = definition.Actions()?.Create(TASK_ACTION_EXEC)?.cast()?;
                action.SetPath(&path_bstr(&self.executable))?;
                action.SetWorkingDirectory(&path_bstr(directory))?;
                action.SetArguments(&BSTR::from(ARGUMENT))?;
                let settings = definition.Settings()?;
                settings.SetEnabled(if enabled { VARIANT_TRUE } else { VARIANT_FALSE })?;
                settings.SetDisallowStartIfOnBatteries(VARIANT_FALSE)?;
                settings.SetStopIfGoingOnBatteries(VARIANT_FALSE)?;
                settings.SetStartWhenAvailable(VARIANT_FALSE)?;
                settings.SetExecutionTimeLimit(&BSTR::from("PT0S"))?;
                settings.SetMultipleInstances(TASK_INSTANCES_IGNORE_NEW)?;
                // The current user can read/run/delete this LUA task so the
                // currentUser uninstaller needs no administrator elevation.
                // Only Administrators/SYSTEM can modify its action.
                let sddl = format!(
                    "O:BAG:BAD:P(A;;GA;;;SY)(A;;GA;;;BA)(A;;GRGXSD;;;{})",
                    self.sid
                );
                self.folder.RegisterTaskDefinition(
                    &self.name(),
                    &definition,
                    TASK_CREATE_OR_UPDATE.0 | TASK_DONT_ADD_PRINCIPAL_ACE.0,
                    &VARIANT::from(self.sid.as_str()),
                    &VARIANT::default(),
                    TASK_LOGON_INTERACTIVE_TOKEN,
                    &VARIANT::from(sddl.as_str()),
                )?;
                Ok(())
            })()
        };
        result.map_err(|_| WRITE_ERROR)
    }

    fn remove(&mut self) -> Result<(), &'static str> {
        if self.task()?.is_some() {
            // SAFETY: task() verified this current SID's fixed product name,
            // marker, action, literal path, working directory, and logon trigger.
            unsafe { self.folder.DeleteTask(&self.name(), 0) }.map_err(|_| WRITE_ERROR)?;
        }
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

fn path_bstr(path: &Path) -> BSTR {
    BSTR::from_wide(&path.as_os_str().encode_wide().collect::<Vec<_>>())
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

fn is_elevated() -> Result<bool, &'static str> {
    let handle = token(unsafe { GetCurrentProcess() })?;
    let mut elevation = TOKEN_ELEVATION::default();
    let mut returned = 0;
    // SAFETY: The out buffer matches TOKEN_ELEVATION and the token is owned.
    if unsafe {
        GetTokenInformation(
            handle.0,
            TokenElevation,
            (&mut elevation as *mut TOKEN_ELEVATION).cast(),
            size_of::<TOKEN_ELEVATION>() as u32,
            &mut returned,
        )
    } == 0
    {
        return Err(WRITE_ERROR);
    }
    Ok(elevation.TokenIsElevated != 0)
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

fn request_logon_elevation() -> Result<(), &'static str> {
    // Shell extensions may require STA COM, and NOASYNC completes ShellExecute
    // before this short-lived unelevated launcher exits.
    let _apartment = ComApartment::initialize(COINIT_APARTMENTTHREADED)?;
    let executable = std::env::current_exe().map_err(|_| PATH_ERROR)?;
    let directory = executable.parent().ok_or(PATH_ERROR)?;
    let verb = wide("runas");
    let executable: Vec<u16> = executable
        .as_os_str()
        .encode_wide()
        .chain(Some(0))
        .collect();
    let directory: Vec<u16> = directory.as_os_str().encode_wide().chain(Some(0)).collect();
    let arguments = wide("--autostart-elevated");
    let mut info = SHELLEXECUTEINFOW {
        cbSize: size_of::<SHELLEXECUTEINFOW>() as u32,
        fMask: SEE_MASK_NOASYNC | SEE_MASK_FLAG_NO_UI,
        lpVerb: verb.as_ptr(),
        lpFile: executable.as_ptr(),
        lpParameters: arguments.as_ptr(),
        lpDirectory: directory.as_ptr(),
        nShow: SW_SHOWNORMAL,
        ..Default::default()
    };
    // SAFETY: Only this process's literal OS-provided executable path and a
    // fixed flag are supplied. Buffers stay alive through the synchronous call.
    if unsafe { ShellExecuteExW(&mut info) } == 0 {
        let error = unsafe { GetLastError() };
        if error == ERROR_CANCELLED {
            return Ok(());
        }
        return Err("无法请求客户端管理员权限，请以当前 Windows 用户的管理员身份启动客户端。");
    }
    Ok(())
}

pub(super) fn handle_startup(mode: LaunchMode) -> Option<i32> {
    // Validate the new elevated child too: UAC may accept another administrator's
    // credentials, which must never launch this user's guard, meter, or Java.
    let action = current_desktop_user()
        .and_then(|_| is_elevated())
        .and_then(|elevated| startup_action(mode, elevated, true));
    let action = match action {
        Ok(action) => action,
        Err(message) => {
            show_startup_error(message);
            return Some(2);
        }
    };
    match action {
        StartupAction::Desktop => None,
        StartupAction::Initialize => {
            // Development builds depend on a live Vite server and must not
            // become a permanent Windows logon entry automatically.
            if !cfg!(debug_assertions) {
                if let Err(message) =
                    WindowsTasks::connect().and_then(|mut tasks| initialize_default(&mut tasks))
                {
                    show_startup_error(message);
                }
            }
            None
        }
        StartupAction::Elevate => match request_logon_elevation() {
            Ok(()) => Some(0),
            Err(message) => {
                show_startup_error(message);
                Some(2)
            }
        },
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
        let mut tasks = WindowsTasks::connect().expect("read-only local task scheduler connection");
        // The unit-test executable is not the installation whose task is checked.
        // Only the expected path in this in-memory reader is changed.
        tasks.executable = executable;

        assert_eq!(tasks.read(), Ok(Some(enabled)));
        assert_eq!(tasks.read_legacy(), Ok(None));
        let status = super::super::read_status(&tasks).expect("installed task status");
        assert!(status.supported);
        assert_eq!(status.enabled, enabled);
    }
}
