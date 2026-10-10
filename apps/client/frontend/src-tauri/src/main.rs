#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

mod backend;
mod meter_service;
mod server_config;
mod tray;

use backend::{ApiRoute, BackendState};
use reqwest::Method;
use serde_json::Value;
use server_config::ServerConfig;
use std::sync::OnceLock;
use tauri::{Manager, RunEvent, State};

type CommandResult<T> = Result<T, String>;
static METER_STARTUP: OnceLock<meter_service::MeterStartOutcome> = OnceLock::new();

#[tauri::command]
fn meter_setup_error(app: tauri::AppHandle) -> Option<String> {
    meter_service::setup_error(&app)
}

#[tauri::command]
async fn meter_service_ensure(app: tauri::AppHandle) -> meter_service::MeterStartOutcome {
    tauri::async_runtime::spawn_blocking(move || {
        *METER_STARTUP.get_or_init(|| tray::ensure_meter_serialized(&app))
    })
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
async fn usage_events(page: u32, size: u32, state: State<'_, BackendState>) -> CommandResult<Value> {
    state.usage_events(page, size).await.map_err(command_error)
}

#[tauri::command]
async fn meter_runtime_logs(state: State<'_, BackendState>) -> CommandResult<Value> {
    state.json(Method::GET, ApiRoute::MeterRuntimeLogs).await.map_err(command_error)
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
    let builder = tauri::Builder::default()
        .setup(|app| {
            app.manage(tray::TrayState::default());
            let tray_available = tray::install(app).is_ok();
            app.state::<tray::TrayState>()
                .set_available(tray_available);
            if !tray_available {
                eprintln!("Unable to initialize the desktop tray; closing the main window will exit the client.");
            }

            let state = BackendState::launch(app.handle())?;
            app.manage(state);
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            meter_service_ensure,
            meter_service_retry,
            meter_setup_error,
            status,
            quota_overview,
            usage_status,
            usage_events,
            meter_runtime_logs,
            usage_scan,
            usage_pair,
            codex_rate_limits,
            system_login,
            system_current,
            system_logout,
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
        RunEvent::WindowEvent {
            label,
            event: tauri::WindowEvent::CloseRequested { api, .. },
            ..
        } if label == "main" => {
            let tray_available = app_handle
                .try_state::<tray::TrayState>()
                .is_some_and(|state| state.is_available());
            let mut ops = tray::TauriCloseRequestOps {
                app: app_handle.clone(),
                api: &api,
            };
            tray::handle_close_request(tray_available, &mut ops);
        }
        RunEvent::ExitRequested { .. } => {
            let mut ops = tray::TauriExitEventOps {
                app: app_handle.clone(),
            };
            tray::handle_exit_event(tray::AppExitEvent::ExitRequested, &mut ops);
        }
        RunEvent::Exit => {
            let mut ops = tray::TauriExitEventOps {
                app: app_handle.clone(),
            };
            tray::handle_exit_event(tray::AppExitEvent::Exit, &mut ops);
        }
        _ => {}
    });
}
