//! Autostart is the Windows task's actual registration, not a renderer preference.
use serde::Serialize;
use std::sync::Mutex;

#[cfg(windows)]
#[path = "autostart/windows.rs"]
mod windows;

const READ_ERROR: &str = "无法读取开机自启动状态，请检查 Windows 任务计划程序。";
const WRITE_ERROR: &str = "无法设置开机自启动，请以管理员身份运行客户端后重试。";
const VERIFY_ERROR: &str = "Windows 未保存开机自启动设置，请重试。";
static OPERATION: Mutex<()> = Mutex::new(());

#[derive(Clone, Copy, Debug, Eq, PartialEq, Serialize)]
pub(crate) struct AutostartStatus {
    pub supported: bool,
    pub enabled: bool,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum LaunchMode {
    Desktop,
    Install,
    Uninstall,
    Logon,
}

pub(crate) fn parse_launch_mode(args: &[String]) -> Result<LaunchMode, &'static str> {
    match args {
        [] => Ok(LaunchMode::Desktop),
        [argument] if argument == "--autostart-install" => Ok(LaunchMode::Install),
        [argument] if argument == "--autostart-uninstall" => Ok(LaunchMode::Uninstall),
        [argument] if argument == "--autostart-launch" => Ok(LaunchMode::Logon),
        _ => Err("无效的客户端启动参数。"),
    }
}

pub(crate) trait TaskStore {
    fn read(&self) -> Result<Option<bool>, &'static str>;
    fn write(&mut self, enabled: bool) -> Result<(), &'static str>;
    fn remove(&mut self) -> Result<(), &'static str>;
    fn install_preference(&self) -> Result<Option<bool>, &'static str>;
    fn remember(&mut self, enabled: bool) -> Result<(), &'static str>;
}

pub(crate) fn read_status(tasks: &impl TaskStore) -> Result<AutostartStatus, &'static str> {
    Ok(AutostartStatus {
        supported: true,
        enabled: tasks.read().map_err(|_| READ_ERROR)?.unwrap_or(false),
    })
}

pub(crate) fn set_enabled(
    tasks: &mut impl TaskStore,
    enabled: bool,
) -> Result<AutostartStatus, &'static str> {
    tasks.write(enabled)?;
    // A disabled task is retained so install/upgrade preserves the user's OFF choice.
    if tasks.read().map_err(|_| READ_ERROR)? != Some(enabled) {
        return Err(VERIFY_ERROR);
    }
    tasks.remember(enabled)?;
    Ok(AutostartStatus {
        supported: true,
        enabled,
    })
}

pub(crate) fn install_default(tasks: &mut impl TaskStore) -> Result<AutostartStatus, &'static str> {
    let actual = tasks.read().map_err(|_| READ_ERROR)?;
    let enabled = match actual {
        Some(enabled) => enabled,
        None => tasks.install_preference()?.unwrap_or(true),
    };
    set_enabled(tasks, enabled)
}

#[cfg(test)]
#[allow(dead_code)] // The pure policy target exercises upgrade removal without OS writes.
pub(crate) fn uninstall(tasks: &mut impl TaskStore, updating: bool) -> Result<(), &'static str> {
    if !updating {
        // Capture even a choice made directly in Windows Task Scheduler before
        // the old installer removes the task during an interactive upgrade.
        if let Some(enabled) = tasks.read().map_err(|_| READ_ERROR)? {
            tasks.remember(enabled)?;
        }
        tasks.remove()?;
        if tasks.read().map_err(|_| READ_ERROR)?.is_some() {
            return Err(VERIFY_ERROR);
        }
    }
    Ok(())
}

pub(crate) struct WriteGrant {
    pub sid: String,
    pub writable: bool,
}

fn privileged_sid(sid: &str) -> bool {
    matches!(
        sid,
        "S-1-5-18"
            | "S-1-5-32-544"
            | "S-1-5-80-956008885-3418522649-1831038044-1853292631-2271478464"
    )
}

pub(crate) fn acl_is_protected(owner: &str, grants: &[WriteGrant]) -> bool {
    privileged_sid(owner)
        && grants
            .iter()
            .all(|grant| !grant.writable || privileged_sid(&grant.sid))
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

// Some(exit_code) means this is a headless installer operation. The caller must
// exit before constructing Tauri, the guard lease, Java, or the meter service.
pub(crate) fn handle_launch_mode() -> Option<i32> {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let mode = match parse_launch_mode(&args) {
        Ok(mode) => mode,
        Err(message) => {
            eprintln!("{message}");
            return Some(1);
        }
    };
    match mode {
        LaunchMode::Desktop | LaunchMode::Logon => None,
        LaunchMode::Install | LaunchMode::Uninstall => {
            #[cfg(windows)]
            let result = match mode {
                LaunchMode::Install => windows::WindowsTasks::connect()
                    .and_then(|mut tasks| install_default(&mut tasks).map(|_| ())),
                LaunchMode::Uninstall => windows::remove_installation_tasks(),
                LaunchMode::Desktop | LaunchMode::Logon => unreachable!(),
            };
            #[cfg(not(windows))]
            let result: Result<(), &'static str> = Err("当前系统暂不支持开机自启动设置。");
            match result {
                Ok(()) => Some(0),
                Err(message) => {
                    eprintln!("{message}");
                    Some(2)
                }
            }
        }
    }
}
