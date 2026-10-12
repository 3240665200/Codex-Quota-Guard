//! Autostart is the Windows task's actual registration, not a renderer preference.
use serde::Serialize;
use std::sync::Mutex;

#[cfg(windows)]
#[path = "autostart/windows.rs"]
mod windows;

const READ_ERROR: &str = "无法读取开机自启动状态，请检查 Windows 任务计划程序。";
const WRITE_ERROR: &str = "无法设置开机自启动，请以管理员身份运行客户端后重试。";
const VERIFY_ERROR: &str = "Windows 未保存开机自启动设置，请重试。";
const CONTEXT_ERROR: &str =
    "开机自启动仅支持当前登录的管理员用户；请勿使用其他账户的管理员凭据设置。";
static OPERATION: Mutex<()> = Mutex::new(());

#[derive(Clone, Copy, Debug, Eq, PartialEq, Serialize)]
pub(crate) struct AutostartStatus {
    pub supported: bool,
    pub enabled: bool,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum LaunchMode {
    Desktop,
    Uninstall,
    Logon,
    ElevatedLogon,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum StartupAction {
    Desktop,
    Initialize,
    Elevate,
    Uninstall,
}

pub(crate) fn startup_action(
    mode: LaunchMode,
    elevated: bool,
    same_user: bool,
) -> Result<StartupAction, &'static str> {
    if !same_user {
        return Err(CONTEXT_ERROR);
    }
    match (mode, elevated) {
        (LaunchMode::Desktop, false) => Ok(StartupAction::Desktop),
        (LaunchMode::Desktop | LaunchMode::Logon | LaunchMode::ElevatedLogon, true) => {
            Ok(StartupAction::Initialize)
        }
        (LaunchMode::Logon, false) => Ok(StartupAction::Elevate),
        (LaunchMode::ElevatedLogon, false) => Err(WRITE_ERROR),
        (LaunchMode::Uninstall, _) => Ok(StartupAction::Uninstall),
    }
}

pub(crate) fn parse_launch_mode(args: &[String]) -> Result<LaunchMode, &'static str> {
    match args {
        [] => Ok(LaunchMode::Desktop),
        [argument] if argument == "--autostart-uninstall" => Ok(LaunchMode::Uninstall),
        [argument] if argument == "--autostart-launch" => Ok(LaunchMode::Logon),
        [argument] if argument == "--autostart-elevated" => Ok(LaunchMode::ElevatedLogon),
        _ => Err("无效的客户端启动参数。"),
    }
}

pub(crate) trait TaskStore {
    fn read(&self) -> Result<Option<bool>, &'static str>;
    fn write(&mut self, enabled: bool) -> Result<(), &'static str>;
    fn remove(&mut self) -> Result<(), &'static str>;
    fn install_preference(&self) -> Result<Option<bool>, &'static str>;
    fn remember(&mut self, enabled: bool) -> Result<(), &'static str>;
    fn read_legacy(&self) -> Result<Option<bool>, &'static str>;
}

pub(crate) fn read_status(tasks: &impl TaskStore) -> Result<AutostartStatus, &'static str> {
    Ok(AutostartStatus {
        supported: true,
        enabled: tasks.read()?.unwrap_or(false),
    })
}

pub(crate) fn set_enabled(
    tasks: &mut impl TaskStore,
    enabled: bool,
) -> Result<AutostartStatus, &'static str> {
    tasks.write(enabled)?;
    // A disabled task is retained so install/upgrade preserves the user's OFF choice.
    if tasks.read()? != Some(enabled) {
        return Err(VERIFY_ERROR);
    }
    tasks.remember(enabled)?;
    Ok(AutostartStatus {
        supported: true,
        enabled,
    })
}

pub(crate) fn initialize_default(
    tasks: &mut impl TaskStore,
) -> Result<AutostartStatus, &'static str> {
    if let Some(enabled) = tasks.read()? {
        // Subsequent launches observe even changes made in Task Scheduler;
        // they never register again or turn an externally disabled task on.
        tasks.remember(enabled)?;
        return Ok(AutostartStatus {
            supported: true,
            enabled,
        });
    }
    let enabled = match tasks.read_legacy()? {
        Some(enabled) => enabled,
        None => tasks.install_preference()?.unwrap_or(true),
    };
    set_enabled(tasks, enabled)
}

pub(crate) fn uninstall(tasks: &mut impl TaskStore, updating: bool) -> Result<(), &'static str> {
    if !updating {
        // Capture even a choice made directly in Windows Task Scheduler before
        // the old installer removes the task during an interactive upgrade.
        if let Some(enabled) = tasks.read()?.or(tasks.read_legacy()?) {
            tasks.remember(enabled)?;
        }
        tasks.remove()?;
        if tasks.read()?.is_some() || tasks.read_legacy()?.is_some() {
            return Err(VERIFY_ERROR);
        }
    }
    Ok(())
}

pub(crate) fn same_logon_user(process_sid: &str, desktop_sid: &str) -> bool {
    process_sid.starts_with("S-1-") && process_sid == desktop_sid
}

pub(crate) fn status() -> Result<AutostartStatus, &'static str> {
    let _operation = OPERATION.lock().map_err(|_| READ_ERROR)?;
    #[cfg(windows)]
    {
        let tasks = windows::WindowsTasks::connect()?;
        read_status(&tasks)
    }
    #[cfg(not(windows))]
    {
        Ok(AutostartStatus {
            supported: false,
            enabled: false,
        })
    }
}

pub(crate) fn set(enabled: bool) -> Result<AutostartStatus, &'static str> {
    let _operation = OPERATION.lock().map_err(|_| WRITE_ERROR)?;
    #[cfg(windows)]
    {
        let mut tasks = windows::WindowsTasks::connect()?;
        set_enabled(&mut tasks, enabled)
    }
    #[cfg(not(windows))]
    {
        let _ = enabled;
        Err("当前系统暂不支持开机自启动设置。")
    }
}

// A launcher/uninstaller or rejected elevation must exit before constructing
// Tauri, the guard lease, Java, or the meter service.
pub(crate) fn handle_launch_mode() -> Option<i32> {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let mode = match parse_launch_mode(&args) {
        Ok(mode) => mode,
        Err(message) => {
            eprintln!("{message}");
            return Some(1);
        }
    };
    #[cfg(windows)]
    {
        windows::handle_startup(mode)
    }
    #[cfg(not(windows))]
    {
        if mode == LaunchMode::Desktop {
            None
        } else {
            Some(2)
        }
    }
}
