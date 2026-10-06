use std::{env, fs, net::Ipv4Addr, path::PathBuf};

use reqwest::Url;
use serde::{Deserialize, Serialize};
use tauri::{AppHandle, Manager};

const DEFAULT_SERVER_URL: &str = "http://127.0.0.1:8080";
const CONFIG_FILE: &str = "server-config.json";

#[derive(Clone, Debug, Deserialize, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub(crate) struct ServerConfig {
    pub(crate) url: String,
    pub(crate) allow_insecure_lan_http: bool,
}

impl Default for ServerConfig {
    fn default() -> Self {
        Self {
            url: DEFAULT_SERVER_URL.to_owned(),
            allow_insecure_lan_http: false,
        }
    }
}

impl ServerConfig {
    fn validated(mut self) -> Result<Self, &'static str> {
        let raw = Url::parse(self.url.trim())
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

        let ipv4 = host.parse::<Ipv4Addr>().ok();
        let loopback = host.eq_ignore_ascii_case("localhost")
            || ipv4.is_some_and(|address| address.is_loopback())
            || host == "[::1]";
        let private_lan = ipv4.is_some_and(is_private_lan_ipv4);
        match raw.scheme() {
            "https" => {}
            "http" if loopback => {}
            "http" if private_lan && self.allow_insecure_lan_http => {}
            "http" if private_lan => return Err("局域网 HTTP 需要先勾选风险确认。"),
            _ => return Err("远程服务器须使用 HTTPS；临时 HTTP 仅允许私有局域网 IP。"),
        }

        self.url = raw.origin().ascii_serialization();
        if loopback || raw.scheme() == "https" {
            self.allow_insecure_lan_http = false;
        }
        Ok(self)
    }
}

fn is_private_lan_ipv4(address: Ipv4Addr) -> bool {
    let [first, second, ..] = address.octets();
    first == 10 || (first == 172 && (16..=31).contains(&second)) || (first == 192 && second == 168)
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
            allow_insecure_lan_http: env::var("QUOTA_GUARD_ALLOW_INSECURE_LAN_HTTP")
                .is_ok_and(|value| value.eq_ignore_ascii_case("true")),
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

    fn candidate(url: &str, allow_insecure_lan_http: bool) -> ServerConfig {
        ServerConfig {
            url: url.into(),
            allow_insecure_lan_http,
        }
    }

    #[test]
    fn accepts_https_and_local_development_http() {
        assert_eq!(
            candidate("https://control.example.com:8443", false)
                .validated()
                .unwrap()
                .url,
            "https://control.example.com:8443"
        );
        assert_eq!(
            candidate("http://127.0.0.1:8080", false)
                .validated()
                .unwrap()
                .url,
            "http://127.0.0.1:8080"
        );
    }

    #[test]
    fn requires_explicit_confirmation_for_private_lan_http() {
        assert!(candidate("http://192.168.0.102:8080", false)
            .validated()
            .is_err());
        assert_eq!(
            candidate("http://192.168.0.102:8080", true)
                .validated()
                .unwrap()
                .url,
            "http://192.168.0.102:8080"
        );
        assert!(candidate("http://10.0.0.5:8080", true).validated().is_ok());
        assert!(candidate("http://172.31.2.5:8080", true)
            .validated()
            .is_ok());
    }

    #[test]
    fn never_allows_public_http_or_embedded_credentials() {
        for url in [
            "http://149.88.90.196:8080",
            "http://attacker.example:8080",
            "https://user:secret@control.example.com",
            "https://control.example.com/api",
            "https://control.example.com/?token=secret",
            "file:///C:/secret",
        ] {
            assert!(candidate(url, true).validated().is_err(), "{url}");
        }
    }
}
