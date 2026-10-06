#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

mod backend;
mod server_config;

use backend::{ApiRoute, BackendState};
use reqwest::Method;
use serde_json::Value;
use server_config::ServerConfig;
use tauri::{Manager, RunEvent, State};

type CommandResult<T> = Result<T, String>;

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
            let state = BackendState::launch(&app.handle())?;
            app.manage(state);
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            status,
            quota_overview,
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
            event: tauri::WindowEvent::CloseRequested { .. },
            ..
        } if label == "main" => {
            if let Some(state) = app_handle.try_state::<BackendState>() {
                state.stop();
            }
            app_handle.exit(0);
        }
        RunEvent::ExitRequested { .. } | RunEvent::Exit => {
            if let Some(state) = app_handle.try_state::<BackendState>() {
                state.stop();
            }
        }
        _ => {}
    });
}
