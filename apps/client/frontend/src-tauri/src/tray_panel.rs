use serde::Deserialize;
use serde_json::Value;
use std::sync::Mutex;
use std::time::{Duration, Instant};
use tauri::{
    AppHandle, Emitter, Manager, PhysicalPosition, PhysicalSize, WebviewUrl, WebviewWindow,
    WebviewWindowBuilder,
};

const LABEL: &str = "tray-panel";

#[derive(Default)]
pub(crate) struct PanelState {
    snapshot: Mutex<Value>,
    last_refresh: Mutex<Option<Instant>>,
}

#[derive(Clone, Copy, Debug, Deserialize)]
#[serde(rename_all = "kebab-case")]
pub(crate) enum PanelAction {
    Show,
    Hide,
    Quota,
    Ledger,
    Diagnostics,
    Settings,
    LoginAssistance,
    Refresh,
    Close,
    Quit,
}

fn request_refresh(app: &AppHandle) {
    let state = app.state::<PanelState>();
    let Ok(mut last) = state.last_refresh.lock() else {
        return;
    };
    if last.is_some_and(|at| at.elapsed() < Duration::from_secs(2)) {
        return;
    }
    *last = Some(Instant::now());
    let _ = app.emit_to("main", "tray-request", "refresh");
}

pub(crate) fn create(app: &AppHandle) -> tauri::Result<()> {
    let window =
        WebviewWindowBuilder::new(app, LABEL, WebviewUrl::App("index.html?view=tray".into()))
            .title("Quota Guard 用量速览")
            .inner_size(400.0, 640.0)
            .decorations(false)
            .transparent(true)
            .shadow(false)
            .resizable(false)
            .maximizable(false)
            .minimizable(false)
            .always_on_top(true)
            .skip_taskbar(true)
            .visible(false)
            .focused(false)
            .build()?;
    let handle = window.clone();
    window.on_window_event(move |event| match event {
        tauri::WindowEvent::Focused(false) => {
            let _ = handle.hide();
        }
        tauri::WindowEvent::CloseRequested { api, .. } => {
            api.prevent_close();
            let _ = handle.hide();
        }
        _ => {}
    });
    Ok(())
}

/// Work-area coordinates are physical pixels, including negative positions on secondary monitors.
fn frame(work: (i32, i32, u32, u32), anchor: (f64, f64), scale: f64) -> (i32, i32, u32, u32) {
    let margin = (8.0 * scale).round() as i32;
    let width =
        ((400.0 * scale).round() as u32).min(work.2.saturating_sub((2 * margin) as u32).max(1));
    let height =
        ((640.0 * scale).round() as u32).min(work.3.saturating_sub((2 * margin) as u32).max(1));
    let left = work.0 + margin;
    let top = work.1 + margin;
    let right = (work.0 + work.2 as i32 - margin - width as i32).max(left);
    let bottom = (work.1 + work.3 as i32 - margin - height as i32).max(top);
    let x = (anchor.0.round() as i32 - width as i32 / 2).clamp(left, right);
    let below = anchor.1.round() as i32 + margin;
    let y = if below + height as i32 <= work.1 + work.3 as i32 - margin {
        below
    } else {
        anchor.1.round() as i32 - height as i32 - margin
    }
    .clamp(top, bottom);
    (x, y, width, height)
}

pub(crate) fn show(app: &AppHandle, position: PhysicalPosition<f64>) -> bool {
    let Some(window) = app.get_webview_window(LABEL) else {
        return false;
    };
    let Ok(monitors) = app.available_monitors() else {
        return false;
    };
    let monitor = monitors
        .iter()
        .find(|monitor| {
            let p = monitor.position();
            let s = monitor.size();
            position.x >= p.x as f64
                && position.y >= p.y as f64
                && position.x < p.x as f64 + s.width as f64
                && position.y < p.y as f64 + s.height as f64
        })
        .or_else(|| monitors.first());
    let Some(monitor) = monitor else {
        return false;
    };
    let work = monitor.work_area();
    let (x, y, width, height) = frame(
        (
            work.position.x,
            work.position.y,
            work.size.width,
            work.size.height,
        ),
        (position.x, position.y),
        monitor.scale_factor(),
    );
    if window.set_position(PhysicalPosition::new(x, y)).is_err()
        || window.set_size(PhysicalSize::new(width, height)).is_err()
    {
        return false;
    }
    request_refresh(app);
    window.show().is_ok() && window.set_focus().is_ok()
}

pub(crate) fn clear_snapshot(app: &AppHandle) {
    if let Some(state) = app.try_state::<PanelState>() {
        if let Ok(mut snapshot) = state.snapshot.lock() {
            *snapshot = Value::Null;
        }
        let _ = app.emit_to(LABEL, "tray-snapshot", Value::Null);
    }
}

#[tauri::command]
pub(crate) fn tray_snapshot_update(
    app: AppHandle,
    window: WebviewWindow,
    snapshot: Value,
) -> Result<(), String> {
    if window.label() != "main"
        || serde_json::to_vec(&snapshot).map_or(true, |data| data.len() > 16_384)
    {
        return Err("无法更新托盘数据。".into());
    }
    let state = app.state::<PanelState>();
    *state.snapshot.lock().map_err(|_| "托盘状态不可用。")? = snapshot.clone();
    app.emit_to(LABEL, "tray-snapshot", snapshot)
        .map_err(|_| "无法更新托盘数据。".into())
}

#[tauri::command]
pub(crate) fn tray_panel_snapshot(app: AppHandle, window: WebviewWindow) -> Result<Value, String> {
    if window.label() != LABEL {
        return Err("无法读取托盘数据。".into());
    }
    app.state::<PanelState>()
        .snapshot
        .lock()
        .map(|value| value.clone())
        .map_err(|_| "托盘状态不可用。".into())
}

#[tauri::command]
pub(crate) fn tray_panel_show(app: AppHandle, window: WebviewWindow) -> Result<(), String> {
    if window.label() != "main" {
        return Err("无法打开用量速览。".into());
    }
    let cursor = app.cursor_position().map_err(|_| "无法定位用量速览。")?;
    if show(&app, cursor) {
        Ok(())
    } else {
        Err("无法打开用量速览。".into())
    }
}

#[tauri::command]
pub(crate) fn tray_panel_action(
    app: AppHandle,
    window: WebviewWindow,
    action: PanelAction,
) -> Result<(), String> {
    if window.label() != LABEL {
        return Err("无效的托盘操作。".into());
    }
    let panel = app.get_webview_window(LABEL);
    match action {
        PanelAction::Refresh => request_refresh(&app),
        PanelAction::Quit => app.exit(0),
        PanelAction::Close => {
            if let Some(panel) = panel {
                let _ = panel.hide();
            }
        }
        PanelAction::Hide => {
            if let Some(main) = app.get_webview_window("main") {
                let _ = main.hide();
            }
            if let Some(panel) = panel {
                let _ = panel.hide();
            }
        }
        _ => {
            if let Some(panel) = panel {
                let _ = panel.hide();
            }
            let target = match action {
                PanelAction::Quota => "quota",
                PanelAction::Ledger => "ledger",
                PanelAction::Diagnostics => "diagnostics",
                PanelAction::Settings => "settings",
                PanelAction::LoginAssistance => "login-assistance",
                _ => "show",
            };
            app.emit_to("main", "tray-request", target)
                .map_err(|_| "无法打开主窗口。")?;
            if !crate::tray::show_main_window(&app) {
                return Err("无法打开主窗口。".into());
            }
        }
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn popup_stays_inside_work_area_on_scaled_and_negative_monitors() {
        for scale in [1.0, 1.25, 1.5, 2.0, 3.0] {
            for left in [0, -1920] {
                for anchor in [(left as f64, 0.0), (left as f64 + 1910.0, 1038.0)] {
                    let (x, y, width, height) = frame((left, 0, 1920, 1040), anchor, scale);
                    assert!(x >= left && y >= 0);
                    assert!(x + width as i32 <= left + 1920 && y + height as i32 <= 1040);
                }
            }
        }
    }
    #[test]
    fn unknown_actions_are_rejected() {
        assert!(serde_json::from_str::<PanelAction>("\"stop-service\"").is_err());
        assert!(serde_json::from_str::<PanelAction>("\"diagnostics\"").is_ok());
    }
}
