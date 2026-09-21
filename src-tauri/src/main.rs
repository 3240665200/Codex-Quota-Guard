#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

mod backend;

use backend::{ApiRoute, BackendState};
use reqwest::Method;
use serde_json::Value;
use tauri::{Manager, RunEvent, State};

type CommandResult<T> = Result<T, String>;

fn command_error(error: backend::BackendError) -> String {
    // BackendError has exclusively user-safe messages. Do not pass through
    // errors from the child process, HTTP library, filesystem, or Java.
    error.to_string()
}

#[tauri::command]
async fn status(state: State<'_, BackendState>) -> CommandResult<Value> {
    state
        .json(Method::GET, ApiRoute::Status)
        .await
        .map_err(command_error)
}

#[tauri::command]
async fn room_overview(state: State<'_, BackendState>) -> CommandResult<Value> {
    state
        .json(Method::GET, ApiRoute::RoomOverview)
        .await
        .map_err(command_error)
}

#[tauri::command]
async fn account_overview(state: State<'_, BackendState>) -> CommandResult<Value> {
    state
        .json(Method::GET, ApiRoute::AccountOverview)
        .await
        .map_err(command_error)
}

#[tauri::command]
async fn start_device_login(state: State<'_, BackendState>) -> CommandResult<Value> {
    state
        .json(Method::POST, ApiRoute::StartDeviceLogin)
        .await
        .map_err(command_error)
}

#[tauri::command]
async fn logout(state: State<'_, BackendState>) -> CommandResult<()> {
    state
        .empty(Method::POST, ApiRoute::Logout)
        .await
        .map_err(command_error)
}

fn main() {
    let builder = tauri::Builder::default()
        .plugin(tauri_plugin_opener::init())
        .setup(|app| {
            let state = BackendState::launch(&app.handle())?;
            app.manage(state);
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            status,
            room_overview,
            account_overview,
            start_device_login,
            logout
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
