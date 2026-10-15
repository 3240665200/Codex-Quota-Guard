use std::{
    env,
    net::{SocketAddr, TcpListener, TcpStream},
    path::PathBuf,
    process::{Child, Command, Stdio},
    sync::{
        atomic::{AtomicBool, Ordering},
        Arc, Mutex,
    },
    thread,
    time::{Duration, Instant},
};

use reqwest::{Client, Method, StatusCode};
use serde_json::Value;
use tauri::{path::BaseDirectory, AppHandle, Manager};
use thiserror::Error;
use uuid::Uuid;

use crate::server_config;

#[cfg(windows)]
mod windows_job;

const LOOPBACK_HOST: &str = "127.0.0.1";
const STARTUP_TIMEOUT: Duration = Duration::from_secs(30);
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
    UsageEvents { page: u32, size: u32 },
    UsageAnalytics { days: u32 },
    LoginAssistanceInfo,
    LoginAssistanceStart,
    LoginAssistanceListener { id: Uuid },
    SessionBindingCurrent,
    SessionBindingStart,
    SessionBindingAttempt { id: Uuid, action: BindingAction },
    OfficialLoginHelperJobs,
    OfficialLoginHelperStatus,
    OfficialLoginHelperStart { id: Uuid },
    OfficialLoginHelperCancel,
    MeterRuntimeLogs,
    CodexRateLimits,
    SystemLogin,
    SystemCurrent,
    SystemAccess,
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
            Self::UsageEvents { .. } => "/api/v1/usage/events",
            Self::UsageAnalytics { .. } => "/api/v1/usage/analytics",
            Self::LoginAssistanceInfo => "/api/v1/login-assistance",
            Self::LoginAssistanceStart | Self::LoginAssistanceListener { .. } => {
                "/api/v1/login-assistance/listeners"
            }
            Self::SessionBindingCurrent => "/api/v1/session-bindings",
            Self::SessionBindingStart | Self::SessionBindingAttempt { .. } => {
                "/api/v1/session-bindings/attempts"
            }
            Self::OfficialLoginHelperJobs | Self::OfficialLoginHelperStart { .. } => {
                "/api/v1/official-login-helper/jobs"
            }
            Self::OfficialLoginHelperStatus => "/api/v1/official-login-helper/status",
            Self::OfficialLoginHelperCancel => "/api/v1/official-login-helper/cancel",
            Self::MeterRuntimeLogs => "/api/v1/usage/logs",
            Self::CodexRateLimits => "/api/v1/codex/rate-limits",
            Self::SystemLogin => "/api/v1/session/login",
            Self::SystemCurrent => "/api/v1/session/current",
            Self::SystemAccess => "/api/v1/session/access",
            Self::SystemLogout => "/api/v1/session/logout",
            Self::Shutdown => "/api/v1/system/shutdown",
        }
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum BindingAction {
    Status,
    Prepare,
    Detect,
    Confirm,
    Cancel,
}

impl BindingAction {
    fn suffix(self) -> &'static str {
        match self {
            Self::Status => "",
            Self::Prepare => "/prepare",
            Self::Detect => "/detect",
            Self::Confirm => "/confirm",
            Self::Cancel => "/cancel",
        }
    }
}

#[derive(Debug, Error)]
pub(crate) enum BackendError {
    #[error("本地服务启动失败，安装包可能不完整，请重新安装。")]
    Startup,
    #[error("本地服务暂时不可用，请稍后重试。")]
    Unavailable,
    #[error("中央后台暂不可用，请确认后台已启动且服务器地址正确。")]
    ControlPlaneUnavailable,
    #[error("本地服务返回了无效的数据。")]
    InvalidResponse,
    #[error("流水分页参数无效，请刷新后重试。")]
    InvalidPagination,
    #[error("统计范围须为 1、7 或 30 天。")]
    InvalidUsageRange,
    #[error("该账户已有用户正在监听，请稍后重试。")]
    MailBusy,
    #[error("操作过于频繁，请稍后重试。")]
    MailRateLimited,
    #[error("无法使用登录辅助，请检查团队与邮箱配置后重试。")]
    MailRejected,
    #[error("监听任务编号无效，请重新开始监听。")]
    InvalidListenerId,
    #[error("绑定任务无效或已失效，请重新读取状态。")]
    InvalidBindingTask,
    #[error("本设备绑定请求被拒绝，请联系管理员检查设备归属、官方账户与队列状态。")]
    BindingRejected,
    #[error("官方登录助手暂不可用，请检查当前管理员身份、浏览器安装和后台任务。")]
    OfficialLoginHelperRejected,
    #[error("邮箱或密码不正确，请检查后重试。")]
    InvalidCredentials,
    #[error(
        "统计程序配对失败；当前 Windows 用户可能已绑定其他系统账户，请联系管理员检查设备绑定。"
    )]
    MeterPairingRequired,
    #[error("请先登录系统账户。")]
    SystemLoginRequired,
    #[error("本机登录存储不可用，请关闭其他客户端并检查用户目录权限后重试。")]
    SessionStorageUnavailable,
    #[error("请先完成本设备官方会话绑定。")]
    SessionBindingRequired,
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
    #[cfg(windows)]
    _job: windows_job::ChildJob,
}

pub(crate) struct BackendState {
    process: Mutex<Option<RunningBackend>>,
    client: Client,
    guard_ready: Arc<AtomicBool>,
}

impl BackendState {
    pub(crate) fn launch(
        app: &AppHandle,
        guard_ready: Arc<AtomicBool>,
    ) -> Result<Self, BackendError> {
        let jar = resolve_backend_jar(app)?;
        let port = reserve_loopback_port()?;
        let token = BearerToken::generate();
        let server = server_config::load(app);
        let usage_state_root = usage_state_root(app)?;
        let app_data = app
            .path()
            .app_data_dir()
            .map_err(|_| BackendError::Startup)?;

        let mut command = Command::new(java_executable(app)?);
        crate::session_vault::configure(&mut command, &app_data);
        // A login task can be elevated. Release builds must not let a user's
        // inherited environment inject executable agents into bundled Java.
        #[cfg(not(debug_assertions))]
        command
            .env_remove("JAVA_TOOL_OPTIONS")
            .env_remove("JDK_JAVA_OPTIONS")
            .env_remove("_JAVA_OPTIONS")
            .env_remove("CLASSPATH");
        let mut child = command
            .arg("-jar")
            .arg(jar)
            .env("QUOTA_GUARD_API_TOKEN", token.value())
            .env("QUOTA_GUARD_CONTROL_PLANE_URL", server.url)
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

        #[cfg(windows)]
        let job = match windows_job::ChildJob::assign(&child) {
            Ok(job) => job,
            Err(_) => {
                let _ = child.kill();
                let _ = child.wait();
                return Err(BackendError::Startup);
            }
        };

        if !wait_until_listening(port, &mut child) {
            let _ = child.kill();
            let _ = child.wait();
            return Err(BackendError::Startup);
        }

        Ok(Self {
            process: Mutex::new(Some(RunningBackend {
                child,
                port,
                token,
                #[cfg(windows)]
                _job: job,
            })),
            client: Client::new(),
            guard_ready,
        })
    }

    pub(crate) fn restart(&self, app: &AppHandle) -> Result<(), BackendError> {
        self.stop();
        let mut replacement = Self::launch(app, Arc::clone(&self.guard_ready))?;
        let process = replacement
            .process
            .get_mut()
            .map_err(|_| BackendError::Startup)?
            .take()
            .ok_or(BackendError::Startup)?;
        let Ok(mut slot) = self.process.lock() else {
            let mut process = process;
            let _ = process.child.kill();
            let _ = process.child.wait();
            return Err(BackendError::Startup);
        };
        *slot = Some(process);
        Ok(())
    }

    pub(crate) async fn json(
        &self,
        method: Method,
        route: ApiRoute,
    ) -> Result<Value, BackendError> {
        self.json_request(method, route, None).await
    }

    pub(crate) async fn usage_events(&self, page: u32, size: u32) -> Result<Value, BackendError> {
        validate_usage_page(page, size)?;
        self.json(Method::GET, ApiRoute::UsageEvents { page, size })
            .await
    }

    pub(crate) async fn usage_analytics(&self, days: u32) -> Result<Value, BackendError> {
        validate_usage_days(days)?;
        self.json(Method::GET, ApiRoute::UsageAnalytics { days })
            .await
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

    pub(crate) async fn official_login_helper_start(
        &self,
        job_id: &str,
        browser: &str,
    ) -> Result<Value, BackendError> {
        let route = official_login_helper_route(job_id)?;
        if !matches!(browser, "EDGE" | "CHROME") {
            return Err(BackendError::OfficialLoginHelperRejected);
        }
        self.json_request(
            Method::POST,
            route,
            Some(serde_json::json!({ "browser": browser })),
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
        let mut request = self
            .client
            .request(method, url)
            .bearer_auth(token)
            .timeout(Duration::from_secs(35));
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

    pub(crate) async fn require_binding(&self) -> Result<(), BackendError> {
        let decision = self.json(Method::GET, ApiRoute::SystemAccess).await?;
        validate_binding_access(&decision)
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
        if !self.guard_ready.load(Ordering::Acquire) {
            return Err(BackendError::Unavailable);
        }
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
    if status == StatusCode::FORBIDDEN
        && matches!(
            route,
            ApiRoute::QuotaOverview
                | ApiRoute::UsageStatus
                | ApiRoute::UsageScan
                | ApiRoute::UsageEvents { .. }
                | ApiRoute::UsageAnalytics { .. }
                | ApiRoute::MeterRuntimeLogs
                | ApiRoute::CodexRateLimits
        )
    {
        return BackendError::SessionBindingRequired;
    }
    if status == StatusCode::LOCKED {
        return BackendError::SessionStorageUnavailable;
    }
    if matches!(
        route,
        ApiRoute::SessionBindingCurrent
            | ApiRoute::SessionBindingStart
            | ApiRoute::SessionBindingAttempt { .. }
            | ApiRoute::OfficialLoginHelperJobs
            | ApiRoute::OfficialLoginHelperStatus
            | ApiRoute::OfficialLoginHelperStart { .. }
            | ApiRoute::OfficialLoginHelperCancel
    ) {
        return match status {
            StatusCode::UNAUTHORIZED => BackendError::SystemLoginRequired,
            StatusCode::SERVICE_UNAVAILABLE => BackendError::ControlPlaneUnavailable,
            _ if matches!(
                route,
                ApiRoute::OfficialLoginHelperJobs
                    | ApiRoute::OfficialLoginHelperStatus
                    | ApiRoute::OfficialLoginHelperStart { .. }
                    | ApiRoute::OfficialLoginHelperCancel
            ) =>
            {
                BackendError::OfficialLoginHelperRejected
            }
            _ => BackendError::BindingRejected,
        };
    }
    if matches!(
        route,
        ApiRoute::LoginAssistanceInfo
            | ApiRoute::LoginAssistanceStart
            | ApiRoute::LoginAssistanceListener { .. }
    ) {
        // Classify by status only: an upstream body may contain email secrets.
        return match status {
            StatusCode::UNAUTHORIZED => BackendError::SystemLoginRequired,
            StatusCode::CONFLICT => BackendError::MailBusy,
            StatusCode::TOO_MANY_REQUESTS => BackendError::MailRateLimited,
            StatusCode::BAD_REQUEST | StatusCode::FORBIDDEN | StatusCode::NOT_FOUND => {
                BackendError::MailRejected
            }
            StatusCode::SERVICE_UNAVAILABLE => BackendError::ControlPlaneUnavailable,
            _ => BackendError::Unavailable,
        };
    }
    match (route, status) {
        (ApiRoute::SystemLogin, StatusCode::UNAUTHORIZED | StatusCode::BAD_REQUEST) => {
            BackendError::InvalidCredentials
        }
        (ApiRoute::SystemLogin, StatusCode::CONFLICT) => BackendError::MeterPairingRequired,
        (
            ApiRoute::SystemLogin
            | ApiRoute::SystemCurrent
            | ApiRoute::SystemAccess
            | ApiRoute::QuotaOverview
            | ApiRoute::UsageEvents { .. }
            | ApiRoute::UsageAnalytics { .. },
            StatusCode::SERVICE_UNAVAILABLE,
        ) => BackendError::ControlPlaneUnavailable,
        (
            ApiRoute::SystemCurrent
            | ApiRoute::SystemAccess
            | ApiRoute::QuotaOverview
            | ApiRoute::CodexRateLimits
            | ApiRoute::UsageEvents { .. }
            | ApiRoute::UsageAnalytics { .. }
            | ApiRoute::MeterRuntimeLogs,
            StatusCode::UNAUTHORIZED,
        ) => BackendError::SystemLoginRequired,
        _ => BackendError::Unavailable,
    }
}

fn validate_binding_access(decision: &Value) -> Result<(), BackendError> {
    if decision.get("allowed").and_then(Value::as_bool) == Some(true)
        && decision
            .get("bindingId")
            .and_then(Value::as_str)
            .is_some_and(|id| Uuid::parse_str(id).is_ok())
        && decision.get("reason") == Some(&Value::Null)
    {
        Ok(())
    } else {
        Err(BackendError::SessionBindingRequired)
    }
}

pub(crate) fn login_assistance_route(listener_id: &str) -> Result<ApiRoute, BackendError> {
    let id = Uuid::parse_str(listener_id).map_err(|_| BackendError::InvalidListenerId)?;
    Ok(ApiRoute::LoginAssistanceListener { id })
}

pub(crate) fn session_binding_route(
    attempt_id: &str,
    action: BindingAction,
) -> Result<ApiRoute, BackendError> {
    let id = Uuid::parse_str(attempt_id).map_err(|_| BackendError::InvalidBindingTask)?;
    Ok(ApiRoute::SessionBindingAttempt { id, action })
}

pub(crate) fn official_login_helper_route(job_id: &str) -> Result<ApiRoute, BackendError> {
    let id = Uuid::parse_str(job_id).map_err(|_| BackendError::InvalidBindingTask)?;
    Ok(ApiRoute::OfficialLoginHelperStart { id })
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

fn bundled_java_relative_path() -> PathBuf {
    PathBuf::from("java")
        .join("bin")
        .join(if cfg!(windows) { "javaw.exe" } else { "java" })
}

fn java_executable(app: &AppHandle) -> Result<PathBuf, BackendError> {
    #[cfg(debug_assertions)]
    if let Some(path) = env::var_os("CODEX_QUOTA_GUARD_JAVA") {
        return Ok(PathBuf::from(path));
    }

    let relative = bundled_java_relative_path();
    if let Ok(executable) = env::current_exe() {
        if let Some(directory) = executable.parent() {
            let java = directory.join(&relative);
            if java.is_file() {
                return Ok(java);
            }
        }
    }
    if let Ok(java) = app.path().resolve(&relative, BaseDirectory::Resource) {
        if java.is_file() {
            return Ok(java);
        }
    }

    #[cfg(debug_assertions)]
    return Ok(PathBuf::from("java"));

    #[cfg(not(debug_assertions))]
    Err(BackendError::Startup)
}

fn resolve_backend_jar(app: &AppHandle) -> Result<PathBuf, BackendError> {
    #[cfg(debug_assertions)]
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
    let base = format!("http://{LOOPBACK_HOST}:{port}{}", route.path());
    match route {
        ApiRoute::UsageEvents { page, size } => format!("{base}?page={page}&size={size}"),
        ApiRoute::UsageAnalytics { days } => format!("{base}?days={days}"),
        ApiRoute::LoginAssistanceListener { id } => format!("{base}/{id}"),
        ApiRoute::SessionBindingAttempt { id, action } => format!("{base}/{id}{}", action.suffix()),
        ApiRoute::OfficialLoginHelperStart { id } => format!("{base}/{id}/start"),
        _ => base,
    }
}

fn validate_usage_page(page: u32, size: u32) -> Result<(), BackendError> {
    if page > 100000 || !(1..=100).contains(&size) {
        Err(BackendError::InvalidPagination)
    } else {
        Ok(())
    }
}

fn validate_usage_days(days: u32) -> Result<(), BackendError> {
    if matches!(days, 1 | 7 | 30) {
        Ok(())
    } else {
        Err(BackendError::InvalidUsageRange)
    }
}

#[cfg(test)]
mod tests {
    #[test]
    fn session_binding_and_helper_routes_are_fixed_and_identifiers_are_validated() {
        let id = "11111111-1111-4111-8111-111111111111";
        let route = super::session_binding_route(id, super::BindingAction::Detect).unwrap();
        assert_eq!(
            super::fixed_url(43117, route),
            format!("http://127.0.0.1:43117/api/v1/session-bindings/attempts/{id}/detect")
        );
        assert!(
            super::session_binding_route("https://example.com", super::BindingAction::Confirm)
                .is_err()
        );
        assert_eq!(
            super::fixed_url(43117, super::ApiRoute::OfficialLoginHelperJobs),
            "http://127.0.0.1:43117/api/v1/official-login-helper/jobs"
        );
        assert!(super::official_login_helper_route("../secret").is_err());
    }
    use super::*;

    #[test]
    fn session_vault_failures_do_not_masquerade_as_wrong_passwords_or_server_outages() {
        for route in [
            ApiRoute::SystemLogin,
            ApiRoute::SystemCurrent,
            ApiRoute::QuotaOverview,
        ] {
            assert!(matches!(
                classify_response(route, StatusCode::LOCKED),
                BackendError::SessionStorageUnavailable
            ));
        }
    }

    #[test]
    fn login_assistance_routes_accept_only_a_uuid_and_never_external_urls() {
        let id = "b350ad52-7b5d-4a5e-bc26-73d17a8dbbaf";
        let route = login_assistance_route(id).unwrap();
        assert_eq!(
            fixed_url(43117, route),
            format!("http://127.0.0.1:43117/api/v1/login-assistance/listeners/{id}")
        );
        assert_eq!(
            fixed_url(43117, ApiRoute::LoginAssistanceInfo),
            "http://127.0.0.1:43117/api/v1/login-assistance"
        );
        assert_eq!(
            fixed_url(43117, ApiRoute::LoginAssistanceStart),
            "http://127.0.0.1:43117/api/v1/login-assistance/listeners"
        );
        for invalid in [
            "",
            "../session/login",
            "https://example.com",
            "not-a-uuid",
            "x?code=123456",
        ] {
            assert!(login_assistance_route(invalid).is_err());
        }
    }

    #[test]
    fn login_assistance_errors_are_specific_and_never_echo_remote_details() {
        let route = ApiRoute::LoginAssistanceStart;
        assert!(matches!(
            classify_response(route, StatusCode::UNAUTHORIZED),
            BackendError::SystemLoginRequired
        ));
        assert!(matches!(
            classify_response(route, StatusCode::CONFLICT),
            BackendError::MailBusy
        ));
        assert!(matches!(
            classify_response(route, StatusCode::TOO_MANY_REQUESTS),
            BackendError::MailRateLimited
        ));
        assert!(matches!(
            classify_response(route, StatusCode::FORBIDDEN),
            BackendError::MailRejected
        ));
        assert!(matches!(
            classify_response(route, StatusCode::SERVICE_UNAVAILABLE),
            BackendError::ControlPlaneUnavailable
        ));
    }

    #[test]
    fn usage_history_accepts_only_bounded_numeric_pagination() {
        assert!(validate_usage_page(0, 10).is_ok());
        assert!(validate_usage_page(100001, 10).is_err());
        assert!(validate_usage_page(0, 0).is_err());
        assert!(validate_usage_page(0, 101).is_err());
        assert_eq!(
            fixed_url(43117, ApiRoute::UsageEvents { page: 2, size: 10 }),
            "http://127.0.0.1:43117/api/v1/usage/events?page=2&size=10"
        );
        assert!(matches!(
            classify_response(
                ApiRoute::UsageEvents { page: 0, size: 10 },
                StatusCode::UNAUTHORIZED
            ),
            BackendError::SystemLoginRequired
        ));
        assert!(matches!(
            classify_response(ApiRoute::MeterRuntimeLogs, StatusCode::UNAUTHORIZED),
            BackendError::SystemLoginRequired
        ));
    }

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
    fn personal_analytics_accepts_only_fixed_ranges_and_no_user_override() {
        for days in [1, 7, 30] {
            assert!(validate_usage_days(days).is_ok());
            assert_eq!(
                fixed_url(43117, ApiRoute::UsageAnalytics { days }),
                format!("http://127.0.0.1:43117/api/v1/usage/analytics?days={days}")
            );
        }
        for days in [0, 2, 31, u32::MAX] {
            assert!(validate_usage_days(days).is_err());
        }
        assert!(matches!(
            classify_response(
                ApiRoute::UsageAnalytics { days: 7 },
                StatusCode::UNAUTHORIZED
            ),
            BackendError::SystemLoginRequired
        ));
        assert!(matches!(
            classify_response(
                ApiRoute::UsageAnalytics { days: 7 },
                StatusCode::SERVICE_UNAVAILABLE
            ),
            BackendError::ControlPlaneUnavailable
        ));
    }

    #[test]
    fn all_routes_stay_on_loopback_and_api_prefix() {
        for route in [
            ApiRoute::Status,
            ApiRoute::QuotaOverview,
            ApiRoute::CodexRateLimits,
            ApiRoute::SystemLogin,
            ApiRoute::SystemCurrent,
            ApiRoute::SystemAccess,
            ApiRoute::SystemLogout,
            ApiRoute::Shutdown,
        ] {
            let url = fixed_url(8080, route);
            assert!(url.starts_with("http://127.0.0.1:8080/api/v1/"));
            assert!(!url.contains('?'));
        }
    }

    #[test]
    fn binding_access_is_fixed_fail_closed_and_distinct_from_product_logout() {
        assert_eq!(
            fixed_url(43117, ApiRoute::SystemAccess),
            "http://127.0.0.1:43117/api/v1/session/access"
        );
        for value in [
            serde_json::json!({}),
            serde_json::json!({"allowed":true,"bindingId":null,"reason":null}),
            serde_json::json!({"allowed":false,"bindingId":null,"reason":"SESSION_BINDING_REQUIRED"}),
        ] {
            assert!(matches!(
                super::validate_binding_access(&value),
                Err(BackendError::SessionBindingRequired)
            ));
        }
        assert!(super::validate_binding_access(&serde_json::json!({"allowed":true,"bindingId":"11111111-1111-4111-8111-111111111111","reason":null})).is_ok());
        assert!(matches!(
            classify_response(ApiRoute::QuotaOverview, StatusCode::FORBIDDEN),
            BackendError::SessionBindingRequired
        ));
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
    fn bundled_runtime_uses_a_fixed_platform_launcher() {
        let path = bundled_java_relative_path();
        assert!(path.starts_with("java"));
        assert_eq!(path.parent(), Some(std::path::Path::new("java/bin")));
        assert_eq!(
            path.file_name().unwrap(),
            if cfg!(windows) { "javaw.exe" } else { "java" }
        );
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
