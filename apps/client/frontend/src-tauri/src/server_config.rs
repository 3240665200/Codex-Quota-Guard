use std::{env, fs, path::PathBuf};

use reqwest::Url;
use serde::{Deserialize, Serialize};
use tauri::{AppHandle, Manager};

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

pub(crate) fn save(app: &AppHandle, config: ServerConfig) -> Result<ServerConfig, &'static str> {
    let config = config.validated()?;
    let path = config_path(app)?;
    let directory = path.parent().ok_or("无法读取客户端设置目录。")?;
    fs::create_dir_all(directory).map_err(|_| "无法保存服务器设置，请检查目录权限。")?;
    let bytes = serde_json::to_vec(&config).map_err(|_| "无法保存服务器设置，请稍后重试。")?;
    fs::write(path, bytes).map_err(|_| "无法保存服务器设置，请检查目录权限。")?;
    Ok(config)
}

#[cfg(test)]
mod tests {
    use super::*;

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
}
