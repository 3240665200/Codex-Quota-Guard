use super::{acl_is_protected, same_logon_user, TaskStore, WriteGrant, READ_ERROR, WRITE_ERROR};
use std::{
    ffi::OsString,
    fs,
    mem::{offset_of, size_of},
    os::windows::{
        ffi::{OsStrExt, OsStringExt},
        fs::MetadataExt,
    },
    path::{Path, PathBuf},
    ptr,
};
use windows::{
    core::{Interface, BSTR},
    Win32::{
        Foundation::{VARIANT_FALSE, VARIANT_TRUE},
        System::{
            Com::{
                CoCreateInstance, CoInitializeEx, CoUninitialize, CLSCTX_INPROC_SERVER,
                COINIT_MULTITHREADED,
            },
            TaskScheduler::{
                IExecAction, ILogonTrigger, IRegisteredTask, ITaskFolder, ITaskService,
                TaskScheduler, TASK_ACTION_EXEC, TASK_CREATE_OR_UPDATE,
                TASK_DONT_ADD_PRINCIPAL_ACE, TASK_ENUM_HIDDEN, TASK_INSTANCES_IGNORE_NEW,
                TASK_LOGON_INTERACTIVE_TOKEN, TASK_RUNLEVEL_HIGHEST, TASK_TRIGGER_LOGON,
            },
            Variant::VARIANT,
        },
    },
};
use windows_sys::Win32::{
    Foundation::{CloseHandle, LocalFree, HANDLE},
    Security::{
        Authorization::{ConvertSidToStringSidW, GetNamedSecurityInfoW, SE_FILE_OBJECT},
        GetAce, GetTokenInformation, IsValidSid, TokenElevation, TokenUser, ACCESS_ALLOWED_ACE,
        ACE_HEADER, ACL, DACL_SECURITY_INFORMATION, INHERIT_ONLY_ACE, OWNER_SECURITY_INFORMATION,
        TOKEN_ELEVATION, TOKEN_QUERY, TOKEN_USER,
    },
    System::{
        Com::CoTaskMemFree,
        Registry::{
            RegCloseKey, RegCreateKeyExW, RegGetValueW, RegSetValueExW, HKEY_LOCAL_MACHINE,
            KEY_SET_VALUE, REG_DWORD, RRF_RT_REG_DWORD,
        },
        Threading::{
            GetCurrentProcess, OpenProcess, OpenProcessToken, PROCESS_QUERY_LIMITED_INFORMATION,
        },
    },
    UI::{
        Shell::{FOLDERID_ProgramFiles, FOLDERID_ProgramFilesX86, SHGetKnownFolderPath},
        WindowsAndMessaging::{GetShellWindow, GetWindowThreadProcessId},
    },
};

const IDENTITY: &str = "com.codexquotaguard.desktop.autostart";
const ARGUMENT: &str = "--autostart-launch";
const CONTEXT_ERROR: &str =
    "开机自启动仅支持当前登录的管理员用户；请勿使用其他账户的管理员凭据设置。";
const PATH_ERROR: &str = "管理员自启动需要受保护的 Program Files 安装目录；请重新安装到默认目录。";
const OWNERSHIP_ERROR: &str = "检测到其他安装版本或不匹配的自启动任务，请先卸载原版本。";
const PREFERENCE_ERROR: &str = "无法保存开机自启动安装偏好，请检查 Windows 用户设置权限。";
const PREFERENCE_KEY: &str = "Software\\Codex Quota Guard\\Desktop";
const REPARSE_POINT: u32 = 0x400;
// Write data/append/EA/attributes, delete-child/delete, WRITE_DAC/WRITE_OWNER,
// GENERIC_WRITE/GENERIC_ALL. Generic read/execute alone does not grant writes.
const WRITE_MASK: u32 =
    0x2 | 0x4 | 0x10 | 0x40 | 0x100 | 0x10000 | 0x40000 | 0x80000 | 0x40000000 | 0x10000000;

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
        // SAFETY: All scheduler operations and COM objects stay on this thread.
        unsafe { CoInitializeEx(None, COINIT_MULTITHREADED) }
            .ok()
            .map_err(|_| READ_ERROR)?;
        Ok(Self)
    }
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

    fn task(&self) -> Result<Option<IRegisteredTask>, &'static str> {
        // SAFETY: The folder is connected to the local scheduler; name is fixed
        // except for a SID obtained from a Windows token, never renderer input.
        match unsafe { self.folder.GetTask(&self.name()) } {
            Ok(task) => {
                self.validate_identity(&task)?;
                Ok(Some(task))
            }
            Err(error) if error.code().0 as u32 == 0x80070002 => Ok(None),
            Err(_) => Err(READ_ERROR),
        }
    }

    fn validate_identity(&self, task: &IRegisteredTask) -> Result<(), &'static str> {
        self.validate_identity_for_sid(task, &self.sid)
    }

    fn validate_identity_for_sid(
        &self,
        task: &IRegisteredTask,
        sid: &str,
    ) -> Result<(), &'static str> {
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
                let action: IExecAction = actions.get_Item(1)?.cast()?;
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
                let trigger: ILogonTrigger = triggers.get_Item(1)?.cast()?;
                let mut trigger_user = BSTR::default();
                trigger.UserId(&mut trigger_user)?;
                Ok(task.Path()? == format!("\\{IDENTITY}.{sid}")
                    && marker == format!("{IDENTITY}/{sid}")
                    && user == sid
                    && trigger_user == sid
                    && logon == TASK_LOGON_INTERACTIVE_TOKEN
                    && level == TASK_RUNLEVEL_HIGHEST
                    && same_path_text(&executable.to_string(), &self.executable)
                    && self
                        .executable
                        .parent()
                        .is_some_and(|parent| same_path_text(&directory.to_string(), parent))
                    && arguments == ARGUMENT)
            }
        })();
        match result {
            Ok(true) => Ok(()),
            Ok(false) | Err(_) => Err(OWNERSHIP_ERROR),
        }
    }
}

impl TaskStore for WindowsTasks {
    fn read(&self) -> Result<Option<bool>, &'static str> {
        self.task()?.map(|task| task_enabled(&task)).transpose()
    }

    fn write(&mut self, enabled: bool) -> Result<(), &'static str> {
        if !is_elevated()? {
            return Err(WRITE_ERROR);
        }
        let existing = self.task()?;
        // Disabling an owned task remains possible even if its installation's
        // permissions were subsequently made unsafe. Do not execute or refresh it.
        if !enabled {
            if let Some(task) = existing {
                return unsafe { task.SetEnabled(VARIANT_FALSE) }.map_err(|_| WRITE_ERROR);
            }
        }
        validate_installation(&self.executable)?;
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
                principal.SetRunLevel(TASK_RUNLEVEL_HIGHEST)?;
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
                // Only elevated Administrators/SYSTEM can modify the action.
                // The interactive user may read/run, but cannot replace the task.
                let sddl = format!(
                    "O:BAG:BAD:P(A;;GA;;;SY)(A;;GA;;;BA)(A;;GRGX;;;{})",
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
        self.remove_owned_installation_tasks()
    }

    fn install_preference(&self) -> Result<Option<bool>, &'static str> {
        let key = wide(PREFERENCE_KEY);
        let value = wide(&self.sid);
        let mut enabled = 0u32;
        let mut size = size_of::<u32>() as u32;
        // SAFETY: Reads one fixed product DWORD named by the current token SID;
        // HKLM is administrator-controlled. No executable paths
        // or command arguments are accepted or recovered from the registry.
        let code = unsafe {
            RegGetValueW(
                HKEY_LOCAL_MACHINE,
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

    fn remember(&mut self, enabled: bool) -> Result<(), &'static str> {
        remember_sid(&self.sid, enabled)
    }
}

pub(super) fn remove_installation_tasks() -> Result<(), &'static str> {
    if !is_elevated()? {
        return Err(WRITE_ERROR);
    }
    let sid = token_sid(&token(unsafe { GetCurrentProcess() })?)?;
    // Uninstall may be authorized by another administrator. It never registers
    // for that account; it removes only fully verified tasks for this installation.
    let mut tasks = WindowsTasks::connect_for_sid(sid)?;
    // The current SID can have a task belonging to a different installation.
    // Cleanup operates on exact owned tasks for this directory, never on that
    // unrelated task's status. Each deletion is verified below.
    tasks.remove()
}

impl WindowsTasks {
    fn remove_owned_installation_tasks(&self) -> Result<(), &'static str> {
        if !is_elevated()? {
            return Err(WRITE_ERROR);
        }
        let mut owned = Vec::new();
        unsafe {
            let collection = self
                .folder
                .GetTasks(TASK_ENUM_HIDDEN.0)
                .map_err(|_| READ_ERROR)?;
            let count = collection.Count().map_err(|_| READ_ERROR)?;
            for index in 1..=count {
                let task = collection
                    .get_Item(&VARIANT::from(index))
                    .map_err(|_| READ_ERROR)?;
                let name = task.Path().map_err(|_| READ_ERROR)?.to_string();
                let Some(sid) = name.strip_prefix(&format!("\\{IDENTITY}.")) else {
                    continue;
                };
                if !sid.starts_with("S-1-")
                    || sid.len() > 256
                    || !sid
                        .bytes()
                        .all(|byte| byte.is_ascii_digit() || byte == b'-' || byte == b'S')
                {
                    continue;
                }
                if self.validate_identity_for_sid(&task, sid).is_ok() {
                    let sid = sid.to_owned();
                    owned.push((name, sid, task_enabled(&task)?));
                }
            }
            // Enumerate before deleting; indices change as tasks are removed.
            // Snapshot every verified user's actual switch before deleting any
            // task. No other user's hive is loaded or modified.
            for (_, sid, enabled) in &owned {
                remember_sid(sid, *enabled)?;
            }
            for (name, _, _) in owned {
                let task = self
                    .folder
                    .GetTask(&BSTR::from(&name))
                    .map_err(|_| READ_ERROR)?;
                let sid = name
                    .strip_prefix(&format!("\\{IDENTITY}."))
                    .ok_or(OWNERSHIP_ERROR)?;
                self.validate_identity_for_sid(&task, sid)?;
                self.folder
                    .DeleteTask(&BSTR::from(&name), 0)
                    .map_err(|_| WRITE_ERROR)?;
                match self.folder.GetTask(&BSTR::from(&name)) {
                    Err(error) if error.code().0 as u32 == 0x80070002 => {}
                    _ => return Err(WRITE_ERROR),
                }
            }
        }
        Ok(())
    }
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

fn remember_sid(sid: &str, enabled: bool) -> Result<(), &'static str> {
    let mut key = ptr::null_mut();
    let path = wide(PREFERENCE_KEY);
    // SAFETY: Only an elevated setter/installer calls this fixed product HKLM
    // key. sid is the current token SID or a fully verified owned task's SID.
    let code = unsafe {
        RegCreateKeyExW(
            HKEY_LOCAL_MACHINE,
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

fn program_files(folder: &windows_sys::core::GUID) -> Result<PathBuf, &'static str> {
    let mut output = ptr::null_mut();
    // SAFETY: Windows returns a CoTaskMemAlloc-owned known-folder path.
    if unsafe { SHGetKnownFolderPath(folder, 0, ptr::null_mut(), &mut output) } < 0 {
        return Err(PATH_ERROR);
    }
    let path = wide_string(output).map(PathBuf::from);
    unsafe { CoTaskMemFree(output.cast()) };
    path
}

fn validate_installation(executable: &Path) -> Result<(), &'static str> {
    let directory = executable.parent().ok_or(PATH_ERROR)?;
    let roots = [
        program_files(&FOLDERID_ProgramFiles)?,
        program_files(&FOLDERID_ProgramFilesX86)?,
    ];
    let root = roots
        .iter()
        .find(|root| directory != root.as_path() && directory.starts_with(root))
        .ok_or(PATH_ERROR)?;
    // Check every containing directory through the trusted known-folder root.
    for ancestor in directory.ancestors() {
        validate_object(ancestor)?;
        if ancestor == root {
            break;
        }
    }
    // The elevated desktop executes bundled Java, JAR, and meter resources too.
    // Check the whole bounded installation tree, including existing explicit ACLs.
    let mut pending = vec![directory.to_owned()];
    let mut inspected = 0;
    while let Some(path) = pending.pop() {
        inspected += 1;
        if inspected > 20000 {
            return Err(PATH_ERROR);
        }
        let metadata = validate_object(&path)?;
        if metadata.is_dir() {
            for entry in fs::read_dir(&path).map_err(|_| PATH_ERROR)? {
                pending.push(entry.map_err(|_| PATH_ERROR)?.path());
            }
        }
    }
    Ok(())
}

fn validate_object(path: &Path) -> Result<fs::Metadata, &'static str> {
    let metadata = fs::symlink_metadata(path).map_err(|_| PATH_ERROR)?;
    if metadata.file_attributes() & REPARSE_POINT != 0 {
        return Err(PATH_ERROR);
    }
    let wide: Vec<u16> = path.as_os_str().encode_wide().chain(Some(0)).collect();
    let mut owner = ptr::null_mut();
    let mut dacl: *mut ACL = ptr::null_mut();
    let mut descriptor = ptr::null_mut();
    // SAFETY: The API allocates descriptor and returns owner/DACL inside it.
    let code = unsafe {
        GetNamedSecurityInfoW(
            wide.as_ptr(),
            SE_FILE_OBJECT,
            OWNER_SECURITY_INFORMATION | DACL_SECURITY_INFORMATION,
            &mut owner,
            ptr::null_mut(),
            &mut dacl,
            ptr::null_mut(),
            &mut descriptor,
        )
    };
    if code != 0 {
        return Err(PATH_ERROR);
    }
    let result = (|| {
        if dacl.is_null() {
            return Err(PATH_ERROR);
        }
        let owner = sid_text(owner)?;
        let mut grants = Vec::new();
        // SAFETY: dacl and all ACEs are part of the valid security descriptor.
        let count = unsafe { (*dacl).AceCount };
        for index in 0..u32::from(count) {
            let mut ace = ptr::null_mut();
            if unsafe { GetAce(dacl, index, &mut ace) } == 0 || ace.is_null() {
                return Err(PATH_ERROR);
            }
            let header = unsafe { &*ace.cast::<ACE_HEADER>() };
            if u32::from(header.AceFlags) & INHERIT_ONLY_ACE != 0 {
                continue;
            }
            match header.AceType {
                0 => {
                    // ACCESS_ALLOWED_ACE is followed by a variable-size SID.
                    if usize::from(header.AceSize) < size_of::<ACCESS_ALLOWED_ACE>() {
                        return Err(PATH_ERROR);
                    }
                    let allowed = unsafe { &*ace.cast::<ACCESS_ALLOWED_ACE>() };
                    let sid = unsafe {
                        ace.cast::<u8>()
                            .add(offset_of!(ACCESS_ALLOWED_ACE, SidStart))
                            .cast()
                    };
                    grants.push(WriteGrant {
                        sid: sid_text(sid)?,
                        writable: allowed.Mask & WRITE_MASK != 0,
                    });
                }
                1 => {}                      // A deny ACE can only reduce access.
                _ => return Err(PATH_ERROR), // Unknown/callback/object ACEs fail closed.
            }
        }
        if !acl_is_protected(&owner, &grants) {
            return Err(PATH_ERROR);
        }
        Ok(metadata)
    })();
    // SAFETY: GetNamedSecurityInfoW allocated the descriptor using LocalAlloc.
    unsafe { LocalFree(descriptor) };
    result
}
