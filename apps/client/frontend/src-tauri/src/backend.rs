use std::{
    env,
    net::{SocketAddr, TcpListener, TcpStream},
    path::PathBuf,
    process::{Child, Command, Stdio},
    sync::Mutex,
    thread,
    time::{Duration, Instant},
};

use reqwest::{Client, Method, StatusCode};
use serde_json::Value;
use tauri::{path::BaseDirectory, AppHandle, Manager};
use thiserror::Error;
use uuid::Uuid;

use crate::server_config;

const LOOPBACK_HOST: &str = "127.0.0.1";
const STARTUP_TIMEOUT: Duration = Duration::from_secs(12);
const SHUTDOWN_TIMEOUT: Duration = Duration::from_secs(5);
const RETRY_INTERVAL: Duration = Duration::from_millis(100);

/// Only these paths can ever be requested by the WebView.  Keeping the route
/// as an enum prevents the desktop API from becoming an arbitrary URL proxy.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum ApiRoute {
    Status,
    QuotaOverview,
    UsageStatus,
    UsageScan,
    UsagePair,
    CodexRateLimits,
    SystemLogin,
    SystemCurrent,
    SystemLogout,
    Shutdown,
}

impl ApiRoute {
    const fn path(self) -> &'static str {
        match self {
            Self::Status => "/api/v1/status",
            Self::QuotaOverview => "/api/v1/quota/overview",
            Self::UsageStatus => "/api/v1/usage/status",
            Self::UsageScan => "/api/v1/usage/scan",
            Self::UsagePair => "/api/v1/usage/pair",
            Self::CodexRateLimits => "/api/v1/codex/rate-limits",
            Self::SystemLogin => "/api/v1/session/login",
            Self::SystemCurrent => "/api/v1/session/current",
            Self::SystemLogout => "/api/v1/session/logout",
            Self::Shutdown => "/api/v1/system/shutdown",
        }
    }
}

#[derive(Debug, Error)]
pub(crate) enum BackendError {
    #[error("本地服务启动失败，请确认已安装 Java 17 后重试。")]
    Startup,
    #[error("本地服务暂时不可用，请稍后重试。")]
    Unavailable,
    #[error("中央后台暂不可用，请确认后台已启动且服务器地址正确。")]
    ControlPlaneUnavailable,
    #[error("本地服务返回了无效的数据。")]
    InvalidResponse,
    #[error("邮箱或密码不正确，请检查后重试。")]
    InvalidCredentials,
    #[error(
        "计费服务配对失败；当前 Windows 用户可能已绑定其他系统账户，请联系管理员检查设备绑定。"
    )]
    MeterPairingRequired,
    #[error("请先登录系统账户。")]
    SystemLoginRequired,
}

/// The token deliberately has no Debug implementation, and is never exposed
/// through a Tauri command or included in an error message.
struct BearerToken(String);

impl BearerToken {
    fn generate() -> Self {
        Self(Uuid::new_v4().simple().to_string())
    }

    fn value(&self) -> &str {
        &self.0
    }
}

struct RunningBackend {
    child: Child,
    port: u16,
    token: BearerToken,
}

pub(crate) struct BackendState {
    process: Mutex<Option<RunningBackend>>,
    client: Client,
}

impl BackendState {
    pub(crate) fn launch(app: &AppHandle) -> Result<Self, BackendError> {
        let jar = resolve_backend_jar(app)?;
        let port = reserve_loopback_port()?;
        let token = BearerToken::generate();
        let server = server_config::load(app);
        let usage_state_root = usage_state_root(app)?;
        let app_data = app
            .path()
            .app_data_dir()
            .map_err(|_| BackendError::Startup)?;

        let mut child = Command::new(java_executable())
            .arg("-jar")
            .arg(jar)
            .env("QUOTA_GUARD_API_TOKEN", token.value())
            .env("QUOTA_GUARD_CONTROL_PLANE_URL", server.url)
            .env(
                "QUOTA_GUARD_ALLOW_INSECURE_LAN_HTTP",
                server.allow_insecure_lan_http.to_string(),
            )
            .env("QUOTA_GUARD_CODEX_HOME_ROOT", &usage_state_root)
            .env(
                "QUOTA_GUARD_METER_DEVICE_ID_FILE",
                app_data.join("meter-device-id.txt"),
            )
            .env(
                "QUOTA_GUARD_METER_PAIRING_INBOX",
                app_data.join("meter-pairing.json"),
            )
            .env(
                "QUOTA_GUARD_METER_STATUS_FILE",
                app_data.join("meter-status.json"),
            )
            .env("SERVER_ADDRESS", LOOPBACK_HOST)
            .env("SERVER_PORT", port.to_string())
            .stdin(Stdio::null())
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .spawn()
            .map_err(|_| BackendError::Startup)?;

        if !wait_until_listening(port, &mut child) {
            let _ = child.kill();
            let _ = child.wait();
            return Err(BackendError::Startup);
        }

        Ok(Self {
            process: Mutex::new(Some(RunningBackend { child, port, token })),
            client: Client::new(),
        })
    }

    pub(crate) async fn json(
        &self,
        method: Method,
        route: ApiRoute,
    ) -> Result<Value, BackendError> {
        self.json_request(method, route, None).await
    }

    /// Sends product-user credentials directly to the local Java process.
    /// The local process relays them to the fixed central origin and keeps the
    /// resulting access/refresh tokens in memory. This method never accepts
    /// a caller-provided URL, Authorization header, or token.
    pub(crate) async fn system_login(
        &self,
        email: String,
        password: String,
    ) -> Result<Value, BackendError> {
        self.json_request(
            Method::POST,
            ApiRoute::SystemLogin,
            Some(serde_json::json!({ "email": email, "password": password })),
        )
        .await
    }

    async fn json_request(
        &self,
        method: Method,
        route: ApiRoute,
        body: Option<Value>,
    ) -> Result<Value, BackendError> {
        let (url, token) = self.request_target(route)?;
        let mut request = self.client.request(method, url).bearer_auth(token);
        if let Some(body) = body {
            request = request.json(&body);
        }
        let response = request
            .send()
            .await
            .map_err(|_| BackendError::Unavailable)?;

        if !response.status().is_success() {
            return Err(classify_response(route, response.status()));
        }

        response
            .json::<Value>()
            .await
            .map_err(|_| BackendError::InvalidResponse)
    }

    pub(crate) async fn empty(&self, method: Method, route: ApiRoute) -> Result<(), BackendError> {
        let (url, token) = self.request_target(route)?;
        let response = self
            .client
            .request(method, url)
            .bearer_auth(token)
            .send()
            .await
            .map_err(|_| BackendError::Unavailable)?;

        if response.status().is_success() {
            Ok(())
        } else {
            Err(classify_response(route, response.status()))
        }
    }

    pub(crate) fn stop(&self) {
        let Some(mut process) = self
            .process
            .lock()
            .ok()
            .and_then(|mut process| process.take())
        else {
            return;
        };
        if request_graceful_shutdown(&mut process) {
            return;
        }
        let _ = process.child.kill();
        let _ = process.child.wait();
    }

    fn request_target(&self, route: ApiRoute) -> Result<(String, String), BackendError> {
        let mut process = self.process.lock().map_err(|_| BackendError::Unavailable)?;
        let process = process.as_mut().ok_or(BackendError::Unavailable)?;
        if process
            .child
            .try_wait()
            .map_err(|_| BackendError::Unavailable)?
            .is_some()
        {
            return Err(BackendError::Unavailable);
        }
        Ok((
            fixed_url(process.port, route),
            process.token.value().to_owned(),
        ))
    }
}

fn classify_response(route: ApiRoute, status: StatusCode) -> BackendError {
    match (route, status) {
        (ApiRoute::SystemLogin, StatusCode::UNAUTHORIZED | StatusCode::BAD_REQUEST) => {
            BackendError::InvalidCredentials
        }
        (ApiRoute::SystemLogin, StatusCode::CONFLICT) => BackendError::MeterPairingRequired,
        (
            ApiRoute::SystemLogin | ApiRoute::SystemCurrent | ApiRoute::QuotaOverview,
            StatusCode::SERVICE_UNAVAILABLE,
        ) => BackendError::ControlPlaneUnavailable,
        (
            ApiRoute::SystemCurrent | ApiRoute::QuotaOverview | ApiRoute::CodexRateLimits,
            StatusCode::UNAUTHORIZED,
        ) => BackendError::SystemLoginRequired,
        _ => BackendError::Unavailable,
    }
}

fn request_graceful_shutdown(process: &mut RunningBackend) -> bool {
    let Ok(client) = reqwest::blocking::Client::builder()
        .timeout(Duration::from_secs(2))
        .build()
    else {
        return false;
    };

    let Ok(response) = client
        .post(fixed_url(process.port, ApiRoute::Shutdown))
        .bearer_auth(process.token.value())
        .send()
    else {
        return false;
    };
    if !response.status().is_success() {
        return false;
    }

    let deadline = Instant::now() + SHUTDOWN_TIMEOUT;
    while Instant::now() < deadline {
        match process.child.try_wait() {
            Ok(Some(_)) => return true,
            Ok(None) => thread::sleep(RETRY_INTERVAL),
            Err(_) => return false,
        }
    }
    false
}

fn java_executable() -> String {
    env::var("CODEX_QUOTA_GUARD_JAVA").unwrap_or_else(|_| "java".to_owned())
}

fn resolve_backend_jar(app: &AppHandle) -> Result<PathBuf, BackendError> {
    if let Some(path) = env::var_os("CODEX_QUOTA_GUARD_BACKEND_JAR") {
        let path = PathBuf::from(path);
        return path.is_file().then_some(path).ok_or(BackendError::Startup);
    }

    let relative_jar = PathBuf::from("backend").join("codex-quota-guard-backend.jar");

    // Windows `tauri build --no-bundle` places mapped resources beside the
    // executable. Keep this candidate constrained to the fixed resource name.
    if let Ok(executable) = env::current_exe() {
        if let Some(directory) = executable.parent() {
            let jar = directory.join(&relative_jar);
            if jar.is_file() {
                return Ok(jar);
            }
        }
    }

    if let Ok(jar) = app.path().resolve(&relative_jar, BaseDirectory::Resource) {
        if jar.is_file() {
            return Ok(jar);
        }
    }

    #[cfg(debug_assertions)]
    {
        let jar = PathBuf::from(env!("CARGO_MANIFEST_DIR"))
            .join("resources")
            .join(&relative_jar);
        if jar.is_file() {
            return Ok(jar);
        }
    }

    Err(BackendError::Startup)
}

fn usage_state_root(app: &AppHandle) -> Result<PathBuf, BackendError> {
    app.path()
        .app_data_dir()
        .map(|directory| directory.join("usage-state"))
        .map_err(|_| BackendError::Startup)
}

fn reserve_loopback_port() -> Result<u16, BackendError> {
    let listener = TcpListener::bind((LOOPBACK_HOST, 0)).map_err(|_| BackendError::Startup)?;
    listener
        .local_addr()
        .map(|address| address.port())
        .map_err(|_| BackendError::Startup)
}

fn wait_until_listening(port: u16, child: &mut Child) -> bool {
    let deadline = Instant::now() + STARTUP_TIMEOUT;
    let address = SocketAddr::from(([127, 0, 0, 1], port));

    while Instant::now() < deadline {
        if child.try_wait().ok().flatten().is_some() {
            return false;
        }
        if TcpStream::connect_timeout(&address, RETRY_INTERVAL).is_ok() {
            return true;
        }
        thread::sleep(RETRY_INTERVAL);
    }
    false
}

fn fixed_url(port: u16, route: ApiRoute) -> String {
    format!("http://{LOOPBACK_HOST}:{port}{}", route.path())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn only_known_routes_generate_urls() {
        assert_eq!(
            fixed_url(43117, ApiRoute::Status),
            "http://127.0.0.1:43117/api/v1/status"
        );
        assert_eq!(
            fixed_url(43117, ApiRoute::SystemLogin),
            "http://127.0.0.1:43117/api/v1/session/login"
        );
        assert_eq!(
            fixed_url(43117, ApiRoute::QuotaOverview),
            "http://127.0.0.1:43117/api/v1/quota/overview"
        );
    }

    #[test]
    fn all_routes_stay_on_loopback_and_api_prefix() {
        for route in [
            ApiRoute::Status,
            ApiRoute::QuotaOverview,
            ApiRoute::CodexRateLimits,
            ApiRoute::SystemLogin,
            ApiRoute::SystemCurrent,
            ApiRoute::SystemLogout,
            ApiRoute::Shutdown,
        ] {
            let url = fixed_url(8080, route);
            assert!(url.starts_with("http://127.0.0.1:8080/api/v1/"));
            assert!(!url.contains('?'));
        }
    }

    #[test]
    fn public_errors_never_include_startup_details() {
        let error = BackendError::Startup.to_string();
        assert!(!error.contains("QUOTA_GUARD_API_TOKEN"));
        assert!(!error.contains("@"));
        assert!(!error.contains("\\\\"));
        assert!(!error.contains('/'));
    }

    #[test]
    fn local_routes_never_expose_managed_login_or_a_credential_endpoint() {
        for route in [
            ApiRoute::Status,
            ApiRoute::QuotaOverview,
            ApiRoute::CodexRateLimits,
            ApiRoute::SystemLogin,
            ApiRoute::SystemCurrent,
            ApiRoute::SystemLogout,
            ApiRoute::Shutdown,
        ] {
            let path = route.path();
            assert!(!path.contains("device"));
            assert!(!path.contains("credential"));
            assert!(!path.ends_with("/codex/login"));
        }
    }

    #[test]
    fn safe_status_mapping_never_uses_a_remote_error_body() {
        assert!(matches!(
            classify_response(ApiRoute::SystemLogin, StatusCode::UNAUTHORIZED),
            BackendError::InvalidCredentials
        ));
        assert!(matches!(
            classify_response(ApiRoute::SystemLogin, StatusCode::SERVICE_UNAVAILABLE),
            BackendError::ControlPlaneUnavailable
        ));
        assert!(matches!(
            classify_response(ApiRoute::SystemCurrent, StatusCode::UNAUTHORIZED),
            BackendError::SystemLoginRequired
        ));
        assert!(matches!(
            classify_response(ApiRoute::QuotaOverview, StatusCode::UNAUTHORIZED),
            BackendError::SystemLoginRequired
        ));
        assert!(matches!(
            classify_response(ApiRoute::SystemLogin, StatusCode::CONFLICT),
            BackendError::MeterPairingRequired
        ));
    }

    #[test]
    fn generated_tokens_are_distinct_and_not_debuggable() {
        let first = BearerToken::generate().value().to_owned();
        let second = BearerToken::generate().value().to_owned();
        assert_ne!(first, second);
        assert_eq!(first.len(), 32);
    }
}
