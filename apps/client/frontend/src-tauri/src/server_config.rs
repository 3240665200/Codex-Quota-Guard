use std::{
    env,
    fs::{self, OpenOptions},
    io::Write,
    path::{Path, PathBuf},
};

use reqwest::Url;
use serde::{Deserialize, Serialize};
use tauri::{AppHandle, Manager};
use uuid::Uuid;

use crate::meter_service::{self, MeterStartOutcome};

const DEFAULT_SERVER_URL: &str = "http://127.0.0.1:8080";
const CONFIG_FILE: &str = "server-config.json";

#[derive(Clone, Debug, Deserialize, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub(crate) struct ServerConfig {
    pub(crate) url: String,
    // Accept the retired setting when reading existing installations.
    #[serde(default, rename = "allowInsecureLanHttp", skip_serializing)]
    _legacy_allow_insecure_lan_http: bool,
}

impl Default for ServerConfig {
    fn default() -> Self {
        Self {
            url: DEFAULT_SERVER_URL.to_owned(),
            _legacy_allow_insecure_lan_http: false,
        }
    }
}

impl ServerConfig {
    fn validated(mut self) -> Result<Self, &'static str> {
        let mut raw = Url::parse(self.url.trim())
            .map_err(|_| "请输入完整的服务器地址，例如 http://192.168.0.102:8080。")?;
        let host = raw.host_str().ok_or("服务器地址缺少主机名。")?;
        if !raw.username().is_empty()
            || raw.password().is_some()
            || raw.query().is_some()
            || raw.fragment().is_some()
            || raw.path() != "/"
        {
            return Err("服务器地址只能填写协议、主机和端口。");
        }

        if !matches!(raw.scheme(), "http" | "https") {
            return Err("服务器地址须以 http:// 或 https:// 开头。");
        }
        if host == "0.0.0.0" {
            raw.set_host(Some("127.0.0.1"))
                .map_err(|_| "服务器地址缺少主机名。")?;
        }
        self.url = raw.origin().ascii_serialization();
        Ok(self)
    }
}

fn config_path(app: &AppHandle) -> Result<PathBuf, &'static str> {
    app.path()
        .app_config_dir()
        .map(|directory| directory.join(CONFIG_FILE))
        .map_err(|_| "无法读取客户端设置目录。")
}

pub(crate) fn load(app: &AppHandle) -> ServerConfig {
    if let Ok(path) = config_path(app) {
        if let Ok(bytes) = fs::read(path) {
            if bytes.len() <= 4096 {
                if let Ok(config) = serde_json::from_slice::<ServerConfig>(&bytes) {
                    if let Ok(valid) = config.validated() {
                        return valid;
                    }
                }
            }
        }
    }

    if let Ok(url) = env::var("QUOTA_GUARD_CONTROL_PLANE_URL") {
        if let Ok(valid) = (ServerConfig {
            url,
            ..ServerConfig::default()
        })
        .validated()
        {
            return valid;
        }
    }
    ServerConfig::default()
}

pub(crate) fn save(app: &AppHandle, config: ServerConfig) -> Result<ServerConfig, String> {
    save_with_meter(
        &config_path(app).map_err(str::to_owned)?,
        &load(app),
        config,
        |url| match meter_service::ensure_running_or_install_for_server(app, url) {
            MeterStartOutcome::Running
            | MeterStartOutcome::Started
            | MeterStartOutcome::Installed => Ok(()),
            MeterStartOutcome::NeedsAdmin => {
                Err("同步统计程序地址需要管理员授权，取消授权后地址不会保存。".to_owned())
            }
            MeterStartOutcome::NotInstalled => {
                Err("安装包中的统计程序不完整，请重新安装客户端后重试。".to_owned())
            }
            MeterStartOutcome::InstallFailed => {
                Err("统计程序地址同步失败，请检查 Windows 服务后重试。".to_owned())
            }
            MeterStartOutcome::Failed => Err("无法确认统计程序地址已同步，请稍后重试。".to_owned()),
            #[cfg(not(windows))]
            MeterStartOutcome::Unsupported => Err("当前平台暂不支持同步统计程序地址。".to_owned()),
        },
    )
}

// The caller holds the same lock used by tray and startup service checks until
// both settings agree, including any rollback after a failed desktop commit.
fn save_with_meter(
    path: &Path,
    previous: &ServerConfig,
    config: ServerConfig,
    mut sync_meter: impl FnMut(&str) -> Result<(), String>,
) -> Result<ServerConfig, String> {
    let config = config.validated().map_err(str::to_owned)?;
    let pending = PendingConfig::prepare(path, &config).map_err(str::to_owned)?;
    sync_meter(&config.url).map_err(|error| format!("服务器地址未保存。{error}"))?;
    if pending.commit().is_err() {
        if config.url != previous.url {
            return match sync_meter(&previous.url) {
                Ok(()) => Err("无法保存客户端设置，统计程序已恢复原服务器地址。请检查目录权限后重试。".to_owned()),
                Err(_) => Err("无法保存客户端设置，统计程序地址也未能恢复。请重新保存原服务器地址，确认同步成功后再使用。".to_owned()),
            };
        }
        return Err("无法保存服务器设置，请检查目录权限后重试。".to_owned());
    }
    Ok(config)
}

struct PendingConfig {
    path: PathBuf,
    destination: PathBuf,
}

impl PendingConfig {
    fn prepare(path: &Path, config: &ServerConfig) -> Result<Self, &'static str> {
        let directory = path.parent().ok_or("无法读取客户端设置目录。")?;
        fs::create_dir_all(directory).map_err(|_| "无法保存服务器设置，请检查目录权限。")?;
        let pending_path = directory.join(format!(".server-config-{}.tmp", Uuid::new_v4()));
        let bytes = serde_json::to_vec(config).map_err(|_| "无法保存服务器设置，请稍后重试。")?;
        let mut file = OpenOptions::new()
            .write(true)
            .create_new(true)
            .open(&pending_path)
            .map_err(|_| "无法保存服务器设置，请检查目录权限。")?;
        let pending = Self {
            path: pending_path,
            destination: path.to_owned(),
        };
        let written = file.write_all(&bytes).and_then(|()| file.sync_all());
        drop(file);
        written.map_err(|_| "无法保存服务器设置，请检查目录权限。")?;
        Ok(pending)
    }

    fn commit(&self) -> std::io::Result<()> {
        fs::rename(&self.path, &self.destination)
    }
}

impl Drop for PendingConfig {
    fn drop(&mut self) {
        let _ = fs::remove_file(&self.path);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    struct TestConfig {
        directory: PathBuf,
        path: PathBuf,
    }

    impl TestConfig {
        fn new(config: &ServerConfig) -> Self {
            let directory =
                env::temp_dir().join(format!("quota-guard-server-config-test-{}", Uuid::new_v4()));
            fs::create_dir(&directory).unwrap();
            let path = directory.join(CONFIG_FILE);
            fs::write(&path, serde_json::to_vec(config).unwrap()).unwrap();
            Self { directory, path }
        }

        fn stored_url(&self) -> String {
            serde_json::from_slice::<ServerConfig>(&fs::read(&self.path).unwrap())
                .unwrap()
                .url
        }

        fn assert_no_pending_files(&self) {
            assert_eq!(fs::read_dir(&self.directory).unwrap().count(), 1);
        }

        fn remove_pending_file(&self) {
            let staged = fs::read_dir(&self.directory)
                .unwrap()
                .map(|entry| entry.unwrap().path())
                .find(|path| path.extension().is_some_and(|extension| extension == "tmp"))
                .unwrap();
            fs::remove_file(staged).unwrap();
        }
    }

    impl Drop for TestConfig {
        fn drop(&mut self) {
            let _ = fs::remove_dir_all(&self.directory);
        }
    }

    fn candidate(url: &str) -> ServerConfig {
        ServerConfig {
            url: url.into(),
            ..ServerConfig::default()
        }
    }

    #[test]
    fn accepts_https_and_local_development_http() {
        assert_eq!(
            candidate("https://control.example.com:8443")
                .validated()
                .unwrap()
                .url,
            "https://control.example.com:8443"
        );
        assert_eq!(
            candidate("http://127.0.0.1:8080").validated().unwrap().url,
            "http://127.0.0.1:8080"
        );
    }

    #[test]
    fn accepts_http_origins_without_a_confirmation_flag() {
        for url in [
            "http://192.168.0.102:8080",
            "http://149.88.90.196:8080",
            "http://control.example.com:8080",
        ] {
            assert_eq!(candidate(url).validated().unwrap().url, url);
        }
        assert_eq!(
            candidate("http://0.0.0.0:8080").validated().unwrap().url,
            "http://127.0.0.1:8080"
        );
    }

    #[test]
    fn reads_legacy_settings_but_saves_only_the_url() {
        let config: ServerConfig = serde_json::from_str(
            r#"{"url":"http://control.example.com:8080","allowInsecureLanHttp":false}"#,
        )
        .unwrap();
        assert_eq!(
            serde_json::to_value(config.validated().unwrap()).unwrap(),
            serde_json::json!({"url":"http://control.example.com:8080"})
        );
    }

    #[test]
    fn rejects_non_http_schemes_and_embedded_credentials() {
        for url in [
            "https://user:secret@control.example.com",
            "https://control.example.com/api",
            "https://control.example.com/?token=secret",
            "file:///C:/secret",
        ] {
            assert!(candidate(url).validated().is_err(), "{url}");
        }
    }

    #[test]
    fn commits_normalized_address_only_after_meter_sync_succeeds() {
        let previous = candidate("http://127.0.0.1:8080");
        let fixture = TestConfig::new(&previous);
        let mut requested = Vec::new();
        let saved = save_with_meter(
            &fixture.path,
            &previous,
            candidate(" https://next.example.com:443/ "),
            |url| {
                assert_eq!(fixture.stored_url(), previous.url);
                requested.push(url.to_owned());
                Ok(())
            },
        )
        .unwrap();

        assert_eq!(requested, ["https://next.example.com"]);
        assert_eq!(fixture.stored_url(), saved.url);
        fixture.assert_no_pending_files();
    }

    #[test]
    fn cancelled_or_failed_meter_sync_keeps_the_previous_desktop_address() {
        for failure in ["需要管理员授权", "统计程序地址同步失败"] {
            let previous = candidate("http://127.0.0.1:8080");
            let fixture = TestConfig::new(&previous);
            let mut requested = Vec::new();
            let result = save_with_meter(
                &fixture.path,
                &previous,
                candidate("https://next.example.com"),
                |url| {
                    requested.push(url.to_owned());
                    Err(failure.to_owned())
                },
            );

            assert!(result.unwrap_err().contains("未保存"));
            assert_eq!(requested, ["https://next.example.com"]);
            assert_eq!(fixture.stored_url(), previous.url);
            fixture.assert_no_pending_files();
        }
    }

    #[test]
    fn invalid_or_unwritable_settings_never_reconfigure_the_meter() {
        let previous = candidate("http://127.0.0.1:8080");
        let fixture = TestConfig::new(&previous);
        let unexpected_sync = |_: &str| -> Result<(), String> {
            panic!("meter must not change before settings can be prepared")
        };
        assert!(save_with_meter(
            &fixture.path,
            &previous,
            candidate("file:///invalid"),
            unexpected_sync,
        )
        .is_err());
        assert!(save_with_meter(
            &fixture.path.join(CONFIG_FILE),
            &previous,
            candidate("https://next.example.com"),
            unexpected_sync,
        )
        .is_err());
        assert_eq!(fixture.stored_url(), previous.url);
        fixture.assert_no_pending_files();
    }

    #[test]
    fn failed_desktop_commit_restores_the_previous_meter_address() {
        let previous = candidate("http://127.0.0.1:8080");
        let fixture = TestConfig::new(&previous);
        let next = "https://next.example.com";
        let mut requested = Vec::new();
        let result = save_with_meter(&fixture.path, &previous, candidate(next), |url| {
            requested.push(url.to_owned());
            if url == next {
                // Simulate the staged file becoming unavailable after sync.
                fixture.remove_pending_file();
            }
            Ok(())
        });

        assert!(result.unwrap_err().contains("已恢复原服务器地址"));
        assert_eq!(requested, [next, previous.url.as_str()]);
        assert_eq!(fixture.stored_url(), previous.url);
        fixture.assert_no_pending_files();
    }

    #[test]
    fn failed_rollback_is_reported_without_committing_the_new_address() {
        let previous = candidate("http://127.0.0.1:8080");
        let fixture = TestConfig::new(&previous);
        let next = "https://next.example.com";
        let result = save_with_meter(&fixture.path, &previous, candidate(next), |url| {
            if url == next {
                fixture.remove_pending_file();
                Ok(())
            } else {
                Err("rollback failed".to_owned())
            }
        });

        assert!(result.unwrap_err().contains("统计程序地址也未能恢复"));
        assert_eq!(fixture.stored_url(), previous.url);
        fixture.assert_no_pending_files();
    }

    #[test]
    fn saving_the_current_address_still_checks_and_repairs_the_meter() {
        let previous = candidate("http://127.0.0.1:8080");
        let fixture = TestConfig::new(&previous);
        let mut requested = Vec::new();
        save_with_meter(&fixture.path, &previous, previous.clone(), |url| {
            requested.push(url.to_owned());
            Ok(())
        })
        .unwrap();

        assert_eq!(requested, [previous.url.as_str()]);
        assert_eq!(fixture.stored_url(), previous.url);
        fixture.assert_no_pending_files();
    }
}
