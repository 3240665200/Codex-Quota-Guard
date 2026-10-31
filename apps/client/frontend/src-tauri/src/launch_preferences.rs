use serde::{Deserialize, Serialize};
use std::{
    fs::{self, File, OpenOptions},
    io::{Read, Write},
    path::{Path, PathBuf},
    sync::Mutex,
};
use tauri::{AppHandle, Manager};
use uuid::Uuid;

const FILE_NAME: &str = "launch-preferences.json";
const MAX_BYTES: u64 = 1024;
const READ_ERROR: &str = "无法读取静默启动设置，请检查本机客户端设置目录。";
const WRITE_ERROR: &str = "无法保存静默启动设置，请检查本机客户端设置目录权限。";
static SAVE_LOCK: Mutex<()> = Mutex::new(());

#[derive(Clone, Copy, Debug, Default, Deserialize, Serialize, PartialEq, Eq)]
#[serde(default, rename_all = "camelCase", deny_unknown_fields)]
pub(crate) struct LaunchPreferences {
    pub(crate) silent_startup: bool,
}

fn path(app: &AppHandle) -> Result<PathBuf, String> {
    app.path()
        .app_config_dir()
        .map(|directory| directory.join(FILE_NAME))
        .map_err(|_| READ_ERROR.into())
}
pub(crate) fn load(app: &AppHandle) -> Result<LaunchPreferences, String> {
    load_from(&path(app)?)
}
pub(crate) fn save(app: &AppHandle, silent_startup: bool) -> Result<LaunchPreferences, String> {
    let _lock = SAVE_LOCK.lock().map_err(|_| WRITE_ERROR.to_owned())?;
    let preferences = LaunchPreferences { silent_startup };
    save_to(&path(app)?, preferences)?;
    Ok(preferences)
}

// The tray must exist before the main window can safely remain hidden.
pub(crate) fn should_show_main(preferences: LaunchPreferences, tray_available: bool) -> bool {
    !preferences.silent_startup || !tray_available
}
fn load_from(path: &Path) -> Result<LaunchPreferences, String> {
    let file = match File::open(path) {
        Ok(file) => file,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => {
            return Ok(LaunchPreferences::default())
        }
        Err(_) => return Err(READ_ERROR.into()),
    };
    if file.metadata().map_err(|_| READ_ERROR.to_owned())?.len() > MAX_BYTES {
        return Err(READ_ERROR.into());
    }
    let mut bytes = Vec::new();
    file.take(MAX_BYTES + 1)
        .read_to_end(&mut bytes)
        .map_err(|_| READ_ERROR.to_owned())?;
    if bytes.len() as u64 > MAX_BYTES {
        return Err(READ_ERROR.into());
    }
    serde_json::from_slice(&bytes).map_err(|_| READ_ERROR.into())
}
fn save_to(path: &Path, preferences: LaunchPreferences) -> Result<(), String> {
    let directory = path.parent().ok_or_else(|| WRITE_ERROR.to_owned())?;
    fs::create_dir_all(directory).map_err(|_| WRITE_ERROR.to_owned())?;
    let temporary = directory.join(format!(".launch-preferences-{}.tmp", Uuid::new_v4()));
    let result = (|| -> std::io::Result<()> {
        let bytes = serde_json::to_vec(&preferences).map_err(std::io::Error::other)?;
        let mut file = OpenOptions::new()
            .write(true)
            .create_new(true)
            .open(&temporary)?;
        file.write_all(&bytes)?;
        file.sync_all()?;
        drop(file);
        fs::rename(&temporary, path)
    })();
    let _ = fs::remove_file(&temporary);
    result.map_err(|_| WRITE_ERROR.into())
}

#[cfg(test)]
mod tests {
    use super::*;
    struct TestFile(PathBuf);
    impl TestFile {
        fn new() -> Self {
            let directory = std::env::temp_dir()
                .join(format!("cqg-launch-preferences-test-{}", Uuid::new_v4()));
            fs::create_dir(&directory).unwrap();
            Self(directory.join(FILE_NAME))
        }
    }
    impl Drop for TestFile {
        fn drop(&mut self) {
            let _ = fs::remove_file(&self.0);
            let _ = fs::remove_dir(&self.0);
            let _ = fs::remove_dir(self.0.parent().unwrap());
        }
    }
    #[test]
    fn normal_startup_is_the_default_and_persists_across_restarts() {
        let file = TestFile::new();
        assert_eq!(load_from(&file.0).unwrap(), LaunchPreferences::default());
        save_to(
            &file.0,
            LaunchPreferences {
                silent_startup: true,
            },
        )
        .unwrap();
        assert!(load_from(&file.0).unwrap().silent_startup);
        save_to(
            &file.0,
            LaunchPreferences {
                silent_startup: false,
            },
        )
        .unwrap();
        assert!(!load_from(&file.0).unwrap().silent_startup);
        assert_eq!(fs::read_dir(file.0.parent().unwrap()).unwrap().count(), 1);
    }
    #[test]
    fn silent_startup_cannot_hide_the_only_available_window() {
        assert!(should_show_main(LaunchPreferences::default(), true));
        assert!(!should_show_main(
            LaunchPreferences {
                silent_startup: true
            },
            true
        ));
        assert!(should_show_main(
            LaunchPreferences {
                silent_startup: true
            },
            false
        ));
    }
    #[test]
    fn unreadable_preferences_fall_back_to_visible_startup_without_overwriting_them() {
        let file = TestFile::new();
        for bytes in [
            b"invalid".as_slice(),
            b"{\"silentStartup\":\"true\"}".as_slice(),
        ] {
            fs::write(&file.0, bytes).unwrap();
            let loaded = load_from(&file.0);
            assert!(loaded.is_err());
            assert!(should_show_main(loaded.unwrap_or_default(), true));
            assert_eq!(fs::read(&file.0).unwrap(), bytes);
        }
        fs::write(&file.0, vec![b' '; 1025]).unwrap();
        assert!(load_from(&file.0).is_err());
    }
    #[test]
    fn a_failed_write_is_reported_and_does_not_leave_a_temporary_file() {
        let file = TestFile::new();
        fs::create_dir(&file.0).unwrap();
        assert!(save_to(
            &file.0,
            LaunchPreferences {
                silent_startup: true
            }
        )
        .is_err());
        assert_eq!(fs::read_dir(file.0.parent().unwrap()).unwrap().count(), 1);
    }
}
