#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

mod autostart;
mod codex_connection;
mod codex_rate_limits;
#[path = "../../../conflict_evidence.rs"]
mod conflict_evidence;
mod conflict_notice;
mod meter_service;
mod meter_status;
mod package_check;
mod package_smoke;
mod server_config;
mod session_vault;
mod startup_gate;
mod tray;
mod window_layout;
mod window_state;

use tauri::{Manager, RunEvent};

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
    .unwrap_or_else(|_| startup_gate::GateResult::failed())
}

#[tauri::command]
async fn startup_guard_status(app: tauri::AppHandle) -> startup_gate::GateResult {
    tauri::async_runtime::spawn_blocking(move || {
        app.state::<startup_gate::StartupGate>().status(&app)
    })
    .await
    .unwrap_or_else(|_| startup_gate::GateResult::failed())
}

#[tauri::command]
fn device_identity(app: tauri::AppHandle) -> meter_status::DeviceIdentity {
    meter_status::device_identity(&app)
}

#[tauri::command]
fn meter_status(app: tauri::AppHandle) -> meter_status::MeterStatus {
    meter_status::meter_status(&app)
}

#[tauri::command]
fn meter_runtime_logs(app: tauri::AppHandle) -> meter_status::MeterRuntimeLogs {
    meter_status::meter_runtime_logs(&app)
}

#[tauri::command]
fn meter_pairing_write(
    app: tauri::AppHandle,
    pairing: meter_status::MeterPairing,
) -> CommandResult<()> {
    meter_status::meter_pairing_write(&app, pairing)
}

#[tauri::command]
async fn meter_service_version(
    app: tauri::AppHandle,
) -> CommandResult<meter_service::MeterVersionStatus> {
    tauri::async_runtime::spawn_blocking(move || meter_service::version_status(&app))
        .await
        .map_err(|_| "统计程序版本查询失败。".to_owned())
}

#[tauri::command]
fn server_config_get(app: tauri::AppHandle) -> server_config::ServerConfig {
    server_config::load(&app)
}

#[tauri::command]
async fn server_config_save(
    app: tauri::AppHandle,
    config: server_config::ServerConfig,
) -> CommandResult<server_config::ServerConfigSaveResult> {
    tauri::async_runtime::spawn_blocking(move || server_config::save(&app, config))
        .await
        .map_err(|_| "无法保存服务器地址，请重试。".to_owned())?
}

#[tauri::command]
fn session_vault_load(
    app: tauri::AppHandle,
) -> CommandResult<Option<session_vault::SessionDto>> {
    session_vault::load(&app)
}

#[tauri::command]
fn session_vault_save(
    app: tauri::AppHandle,
    session: session_vault::SessionDto,
) -> CommandResult<()> {
    session_vault::save(&app, session)
}

#[tauri::command]
fn session_vault_clear(app: tauri::AppHandle) -> CommandResult<()> {
    session_vault::clear(&app)
}

#[tauri::command]
async fn codex_rate_limits(
    app: tauri::AppHandle,
) -> CommandResult<codex_rate_limits::OfficialCodexRateLimits> {
    let before = tauri::async_runtime::spawn_blocking(codex_connection::conflict_snapshot)
        .await
        .ok()
        .flatten();
    let result = tauri::async_runtime::spawn_blocking(codex_rate_limits::read)
        .await
        .map_err(|_| "无法读取 Codex 官方额度信息。".to_owned())?;
    if matches!(result, Err(codex_rate_limits::CodexRateLimitError::SessionConflict)) {
        if let Some(before) = before {
            let conflict_app = app.clone();
            let _ = tauri::async_runtime::spawn_blocking(move || {
                let gate = conflict_app.state::<startup_gate::StartupGate>();
                let ready = gate.ready_flag();
                let deadline = std::time::Instant::now() + std::time::Duration::from_secs(20);
                while !ready.load(std::sync::atomic::Ordering::Acquire)
                    && std::time::Instant::now() < deadline
                {
                    std::thread::sleep(std::time::Duration::from_millis(50));
                }
                if ready.load(std::sync::atomic::Ordering::Acquire) {
                    gate.record_official_session_conflict(&conflict_app, &before)
                } else {
                    false
                }
            })
            .await;
        }
    }
    result.map_err(|_| "无法读取 Codex 官方额度信息。".to_owned())
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

pub(crate) fn unix_seconds() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map_or(0, |elapsed| elapsed.as_secs())
}

fn main() {
    let context = tauri::generate_context!();
    if let Some(exit_code) = package_check::handle_launch_mode(&context) {
        std::process::exit(exit_code);
    }
    let arguments: Vec<_> = std::env::args_os().skip(1).collect();
    if arguments.iter().any(|argument| {
        argument == "--smoke-package" || argument.to_string_lossy().starts_with("--smoke-package=")
    }) {
        if arguments.len() != 1 || arguments[0] != "--smoke-package" {
            std::process::exit(1);
        }
        std::process::exit(package_smoke::run(context));
    }
    if let Some(exit_code) = autostart::handle_launch_mode() {
        std::process::exit(exit_code);
    }

    let _session_lock = match session_vault::acquire_process_lock() {
        Ok(lock) => lock,
        Err(()) => {
            session_vault::show_already_running_notice();
            return;
        }
    };

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
            device_identity,
            meter_status,
            meter_runtime_logs,
            meter_pairing_write,
            meter_service_version,
            server_config_get,
            server_config_save,
            session_vault_load,
            session_vault_save,
            session_vault_clear,
            codex_rate_limits
        ]);

    let app = match builder.build(context) {
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
        RunEvent::ExitRequested { .. } | RunEvent::Exit => {
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
        }
        _ => {}
    });
}
