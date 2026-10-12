#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

mod autostart;
mod backend;
mod conflict_notice;
mod meter_service;
mod server_config;
mod startup_gate;
mod tray;
mod window_layout;
mod window_state;

use backend::{ApiRoute, BackendState};
use reqwest::Method;
use serde_json::Value;
use server_config::ServerConfig;
use tauri::{Manager, RunEvent, State};

type CommandResult<T> = Result<T, String>;

#[tauri::command]
async fn autostart_status() -> CommandResult<autostart::AutostartStatus> {
    tauri::async_runtime::spawn_blocking(autostart::status)
        .await
        .map_err(|_| "无法读取开机自启动状态，请稍后重试。".to_owned())?
        .map_err(str::to_owned)
}

#[tauri::command]
async fn autostart_set(enabled: bool) -> CommandResult<autostart::AutostartStatus> {
    tauri::async_runtime::spawn_blocking(move || autostart::set(enabled))
        .await
        .map_err(|_| "无法设置开机自启动，请稍后重试。".to_owned())?
        .map_err(str::to_owned)
}

#[tauri::command]
async fn startup_gate_check(app: tauri::AppHandle) -> startup_gate::GateResult {
    tauri::async_runtime::spawn_blocking(move || {
        app.state::<startup_gate::StartupGate>().check(&app)
    })
    .await
    .unwrap_or(startup_gate::GateResult::failed())
}

#[tauri::command]
async fn startup_guard_status(app: tauri::AppHandle) -> startup_gate::GateResult {
    tauri::async_runtime::spawn_blocking(move || {
        app.state::<startup_gate::StartupGate>().status(&app)
    })
    .await
    .unwrap_or(startup_gate::GateResult::failed())
}

#[tauri::command]
fn meter_setup_error(app: tauri::AppHandle) -> Option<String> {
    meter_service::setup_error(&app)
}

#[tauri::command]
async fn meter_service_version(
    app: tauri::AppHandle,
) -> Result<meter_service::MeterVersionStatus, String> {
    tauri::async_runtime::spawn_blocking(move || meter_service::version_status(&app))
        .await
        .map_err(|_| "统计程序版本查询失败".to_string())
}

#[tauri::command]
async fn meter_service_ensure(app: tauri::AppHandle) -> meter_service::MeterStartOutcome {
    tauri::async_runtime::spawn_blocking(move || tray::ensure_meter_serialized(&app))
        .await
        .unwrap_or(meter_service::MeterStartOutcome::Failed)
}

#[tauri::command]
async fn meter_service_retry(app: tauri::AppHandle) -> meter_service::MeterStartOutcome {
    tauri::async_runtime::spawn_blocking(move || tray::ensure_meter_serialized(&app))
        .await
        .unwrap_or(meter_service::MeterStartOutcome::Failed)
}

fn command_error(error: backend::BackendError) -> String {
    // BackendError has exclusively user-safe messages. Do not pass through
    // errors from the child process, HTTP library, filesystem, or Java.
    error.to_string()
}

fn record_window_size(
    app_handle: &tauri::AppHandle,
    window: &tauri::WebviewWindow,
    physical_size: tauri::PhysicalSize<u32>,
    scale_factor: Option<f64>,
    force: bool,
) {
    let is_normal = window
        .is_minimized()
        .ok()
        .zip(window.is_maximized().ok())
        .zip(window.is_fullscreen().ok())
        .is_some_and(|((minimized, maximized), fullscreen)| {
            window_state::is_normal_window(minimized, maximized, fullscreen)
        });
    if !is_normal {
        return;
    }

    let scale_factor = scale_factor.or_else(|| window.scale_factor().ok());
    let Some(size) =
        scale_factor.and_then(|scale| window_layout::logical_size_from_tauri(physical_size, scale))
    else {
        return;
    };
    if let Some(state) = app_handle.try_state::<window_state::WindowState>() {
        state.record_size(size, true, force);
    }
}

fn record_current_window_size(
    app_handle: &tauri::AppHandle,
    window: &tauri::WebviewWindow,
    force: bool,
) {
    let (Ok(size), Ok(scale_factor)) = (window.inner_size(), window.scale_factor()) else {
        return;
    };
    record_window_size(app_handle, window, size, Some(scale_factor), force);
}

#[tauri::command]
fn server_config_get(app: tauri::AppHandle) -> ServerConfig {
    server_config::load(&app)
}

#[tauri::command]
fn server_config_save(app: tauri::AppHandle, config: ServerConfig) -> CommandResult<ServerConfig> {
    server_config::save(&app, config).map_err(str::to_owned)
}

#[tauri::command]
async fn status(state: State<'_, BackendState>) -> CommandResult<Value> {
    state
        .json(Method::GET, ApiRoute::Status)
        .await
        .map_err(command_error)
}

#[tauri::command]
async fn quota_overview(state: State<'_, BackendState>) -> CommandResult<Value> {
    state
        .json(Method::GET, ApiRoute::QuotaOverview)
        .await
        .map_err(command_error)
}

#[tauri::command]
async fn usage_status(state: State<'_, BackendState>) -> CommandResult<Value> {
    state
        .json(Method::GET, ApiRoute::UsageStatus)
        .await
        .map_err(command_error)
}

#[tauri::command]
async fn usage_events(
    page: u32,
    size: u32,
    state: State<'_, BackendState>,
) -> CommandResult<Value> {
    state.usage_events(page, size).await.map_err(command_error)
}

#[tauri::command]
async fn usage_analytics(days: u32, state: State<'_, BackendState>) -> CommandResult<Value> {
    state.usage_analytics(days).await.map_err(command_error)
}

#[tauri::command]
async fn meter_runtime_logs(state: State<'_, BackendState>) -> CommandResult<Value> {
    state
        .json(Method::GET, ApiRoute::MeterRuntimeLogs)
        .await
        .map_err(command_error)
}

#[tauri::command]
async fn usage_scan(state: State<'_, BackendState>) -> CommandResult<Value> {
    state
        .json(Method::POST, ApiRoute::UsageScan)
        .await
        .map_err(command_error)
}

#[tauri::command]
async fn usage_pair(state: State<'_, BackendState>) -> CommandResult<Value> {
    state
        .json(Method::POST, ApiRoute::UsagePair)
        .await
        .map_err(command_error)
}

#[tauri::command]
async fn codex_rate_limits(state: State<'_, BackendState>) -> CommandResult<Value> {
    state
        .json(Method::GET, ApiRoute::CodexRateLimits)
        .await
        .map_err(command_error)
}

#[tauri::command]
async fn system_login(
    email: String,
    password: String,
    state: State<'_, BackendState>,
) -> CommandResult<Value> {
    state
        .system_login(email, password)
        .await
        .map_err(command_error)
}

#[tauri::command]
async fn login_assistance_info(state: State<'_, BackendState>) -> CommandResult<Value> {
    state
        .json(Method::GET, ApiRoute::LoginAssistanceInfo)
        .await
        .map_err(command_error)
}

#[tauri::command]
async fn login_assistance_start(state: State<'_, BackendState>) -> CommandResult<Value> {
    state
        .json(Method::POST, ApiRoute::LoginAssistanceStart)
        .await
        .map_err(command_error)
}

#[tauri::command]
async fn login_assistance_status(
    listener_id: String,
    state: State<'_, BackendState>,
) -> CommandResult<Value> {
    let route = backend::login_assistance_route(&listener_id).map_err(command_error)?;
    state.json(Method::GET, route).await.map_err(command_error)
}

#[tauri::command]
async fn login_assistance_cancel(
    listener_id: String,
    state: State<'_, BackendState>,
) -> CommandResult<Value> {
    let route = backend::login_assistance_route(&listener_id).map_err(command_error)?;
    state
        .json(Method::DELETE, route)
        .await
        .map_err(command_error)
}

#[tauri::command]
async fn system_current(state: State<'_, BackendState>) -> CommandResult<Value> {
    state
        .json(Method::GET, ApiRoute::SystemCurrent)
        .await
        .map_err(command_error)
}

#[tauri::command]
async fn system_logout(state: State<'_, BackendState>) -> CommandResult<()> {
    state
        .empty(Method::POST, ApiRoute::SystemLogout)
        .await
        .map_err(command_error)
}

fn main() {
    if let Some(exit_code) = autostart::handle_launch_mode() {
        std::process::exit(exit_code);
    }
    let builder = tauri::Builder::default()
        .setup(|app| {
            app.manage(conflict_notice::ConflictNotices::default());
            let window_state = window_state::WindowState::from_app(app.handle());
            let saved_size = window_state.saved_size();
            app.manage(window_state);

            if let Some(main_window) = app.get_webview_window("main") {
                let applied_layout =
                    window_layout::apply_initial_layout(&main_window, saved_size);
                let initial_size = match applied_layout {
                    Ok(size) => size.or_else(|| window_layout::current_logical_size(&main_window)),
                    Err(_) => {
                        eprintln!("Unable to apply the initial window layout.");
                        window_layout::current_logical_size(&main_window)
                    }
                };
                if let Some(initial_size) = initial_size {
                    app.state::<window_state::WindowState>()
                        .set_initial_size(initial_size);
                }
            }
            app.manage(tray::TrayState::default());
            app.manage(startup_gate::StartupGate::default());
            let guard_app = app.handle().clone();
            std::thread::spawn(move || loop {
                std::thread::sleep(std::time::Duration::from_secs(3));
                let Some(gate) = guard_app.try_state::<startup_gate::StartupGate>() else {
                    break;
                };
                if !gate.should_monitor() {
                    break;
                }
                // Continue consuming termination notices even if a guard error
                // stopped Java or the renderer is hidden in the tray.
                let _ = gate.status(&guard_app);
            });
            let tray_available = tray::install(app).is_ok();
            app.state::<tray::TrayState>()
                .set_available(tray_available);
            if !tray_available {
                eprintln!("Unable to initialize the desktop tray; closing the main window will exit the client.");
            }

            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            autostart_status,
            autostart_set,
            startup_gate_check,
            startup_guard_status,
            meter_service_ensure,
            meter_service_retry,
            meter_setup_error,
            meter_service_version,
            status,
            quota_overview,
            usage_status,
            usage_events,
            usage_analytics,
            meter_runtime_logs,
            usage_scan,
            usage_pair,
            codex_rate_limits,
            system_login,
            system_current,
            system_logout,
            login_assistance_info,
            login_assistance_start,
            login_assistance_status,
            login_assistance_cancel,
            server_config_get,
            server_config_save
        ]);

    let app = match builder.build(tauri::generate_context!()) {
        Ok(app) => app,
        Err(_) => {
            eprintln!("Unable to start Codex Quota Guard desktop shell.");
            return;
        }
    };

    app.run(|app_handle, event| match event {
        RunEvent::WindowEvent { label, event, .. } if label == "main" => {
            let Some(window) = app_handle.get_webview_window("main") else {
                return;
            };
            match event {
                tauri::WindowEvent::Resized(size) => {
                    record_window_size(app_handle, &window, size, None, false);
                }
                tauri::WindowEvent::ScaleFactorChanged {
                    scale_factor,
                    new_inner_size,
                    ..
                } => {
                    record_window_size(
                        app_handle,
                        &window,
                        new_inner_size,
                        Some(scale_factor),
                        false,
                    );
                }
                tauri::WindowEvent::CloseRequested { api, .. } => {
                    record_current_window_size(app_handle, &window, true);
                    let tray_available = app_handle
                        .try_state::<tray::TrayState>()
                        .is_some_and(|state| state.is_available());
                    let mut ops = tray::TauriCloseRequestOps {
                        app: app_handle.clone(),
                        api: &api,
                    };
                    tray::handle_close_request(tray_available, &mut ops);
                }
                _ => {}
            }
        }
        RunEvent::ExitRequested { .. } => {
            // Withdraw enforcement before notification shutdown or preference
            // flushes, so a slow disk cannot extend silent enforcement.
            if let Some(gate) = app_handle.try_state::<startup_gate::StartupGate>() {
                gate.stop();
            }
            if let Some(notices) = app_handle.try_state::<conflict_notice::ConflictNotices>() {
                notices.stop();
            }
            if let Some(window) = app_handle.get_webview_window("main") {
                record_current_window_size(app_handle, &window, true);
            }
            if let Some(state) = app_handle.try_state::<window_state::WindowState>() {
                state.flush();
            }
            let mut ops = tray::TauriExitEventOps {
                app: app_handle.clone(),
            };
            tray::handle_exit_event(tray::AppExitEvent::ExitRequested, &mut ops);
        }
        RunEvent::Exit => {
            if let Some(gate) = app_handle.try_state::<startup_gate::StartupGate>() {
                gate.stop();
            }
            if let Some(notices) = app_handle.try_state::<conflict_notice::ConflictNotices>() {
                notices.stop();
            }
            if let Some(window) = app_handle.get_webview_window("main") {
                record_current_window_size(app_handle, &window, true);
            }
            if let Some(state) = app_handle.try_state::<window_state::WindowState>() {
                state.flush();
            }
            let mut ops = tray::TauriExitEventOps {
                app: app_handle.clone(),
            };
            tray::handle_exit_event(tray::AppExitEvent::Exit, &mut ops);
        }
        _ => {}
    });
}
