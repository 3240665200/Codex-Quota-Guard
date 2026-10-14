//! Isolated packaged-renderer check. Never register the application's commands or setup.

use std::{
    io::Write,
    sync::{mpsc, Arc, Mutex},
    time::Duration,
};

use serde::Serialize;
use tauri::{WebviewUrl, WebviewWindowBuilder};

const WINDOW_LABEL: &str = "package-smoke";
const TIMEOUT: Duration = Duration::from_secs(20);
const PROBE_SCRIPT: &str = r#"
(() => {
  if (window.top !== window.self) return;
  let reporting = false;
  const timer = setInterval(() => {
    if (reporting) return;
    const root = document.getElementById('root');
    const rootRendered = !!root && root.childElementCount > 0 &&
      !!root.querySelector('main') && root.textContent.trim().length > 0;
    const styles = Array.from(document.querySelectorAll('link[rel="stylesheet"]'));
    const stylesheetLoaded = styles.length > 0 && styles.every(link => {
      try { return !!link.sheet && link.sheet.cssRules.length > 0; }
      catch { return false; }
    });
    const packagedOrigin = location.protocol === 'tauri:' ||
      ((location.protocol === 'http:' || location.protocol === 'https:') &&
        location.hostname === 'tauri.localhost' && location.port === '');
    if (!rootRendered || !stylesheetLoaded || !packagedOrigin ||
        !window.__TAURI_INTERNALS__?.invoke) return;
    reporting = true;
    window.__TAURI_INTERNALS__.invoke('smoke_report', {
      rootRendered, stylesheetLoaded, packagedOrigin
    }).then(() => clearInterval(timer)).catch(() => { reporting = false; });
  }, 100);
})();
"#;

#[derive(Clone, Serialize)]
struct SmokeReport {
    ok: bool,
    version: &'static str,
    mode: &'static str,
    probe: &'static str,
    reason: &'static str,
    custom_protocol: bool,
    root_rendered: bool,
    stylesheet_loaded: bool,
    packaged_origin: bool,
}

impl SmokeReport {
    fn failed(reason: &'static str) -> Self {
        Self {
            ok: false,
            version: env!("CARGO_PKG_VERSION"),
            mode: "webview-smoke",
            probe: "CQG_WEBVIEW_PROBE_V1",
            reason,
            custom_protocol: !tauri::is_dev(),
            root_rendered: false,
            stylesheet_loaded: false,
            packaged_origin: false,
        }
    }
}

#[derive(Clone, Default)]
struct SmokeState(Arc<Mutex<Option<SmokeReport>>>);

impl SmokeState {
    fn finish(&self, app: &tauri::AppHandle, report: SmokeReport) {
        let mut result = self.0.lock().unwrap_or_else(|error| error.into_inner());
        if result.is_none() {
            let code = if report.ok { 0 } else { 1 };
            *result = Some(report);
            drop(result);
            app.exit(code);
        }
    }
}

#[tauri::command]
fn smoke_report(
    app: tauri::AppHandle,
    window: tauri::WebviewWindow,
    state: tauri::State<'_, SmokeState>,
    root_rendered: bool,
    stylesheet_loaded: bool,
    packaged_origin: bool,
) {
    let trusted_origin = window.url().is_ok_and(|url| {
        url.scheme() == "tauri"
            || (matches!(url.scheme(), "http" | "https")
                && url.host_str() == Some("tauri.localhost")
                && url.port().is_none())
    });
    let ok = window.label() == WINDOW_LABEL
        && !tauri::is_dev()
        && trusted_origin
        && root_rendered
        && stylesheet_loaded
        && packaged_origin;
    let mut report = SmokeReport::failed(if ok {
        "loaded"
    } else {
        "invalid_renderer_report"
    });
    report.ok = ok;
    report.root_rendered = root_rendered;
    report.stylesheet_loaded = stylesheet_loaded;
    report.packaged_origin = packaged_origin && trusted_origin;
    state.finish(&app, report);
}

fn emit(report: &SmokeReport) -> i32 {
    let mut stdout = std::io::stdout().lock();
    if serde_json::to_writer(&mut stdout, report).is_err()
        || stdout.write_all(b"\n").is_err()
        || stdout.flush().is_err()
    {
        return 1;
    }
    if report.ok {
        0
    } else {
        1
    }
}

pub(crate) fn run(mut context: tauri::Context<tauri::Wry>) -> i32 {
    if tauri::is_dev() {
        return emit(&SmokeReport::failed("custom_protocol_disabled"));
    }

    // A unique profile prevents reading or changing the installed application's storage.
    let profile = std::env::temp_dir().join(format!("cqg-package-smoke-{}", uuid::Uuid::new_v4()));
    if std::fs::create_dir(&profile).is_err() {
        return emit(&SmokeReport::failed("profile_creation_failed"));
    }
    context.config_mut().app.windows.clear();
    context.config_mut().identifier.push_str(".package-smoke");

    let state = SmokeState::default();
    let setup_state = state.clone();
    let window_profile = profile.clone();
    let application = tauri::Builder::default()
        .manage(state.clone())
        .invoke_handler(tauri::generate_handler![smoke_report])
        .setup(move |app| {
            if WebviewWindowBuilder::new(app, WINDOW_LABEL, WebviewUrl::App("index.html".into()))
                .visible(false)
                .skip_taskbar(true)
                .data_directory(window_profile)
                .initialization_script(PROBE_SCRIPT)
                .build()
                .is_err()
            {
                setup_state.finish(app.handle(), SmokeReport::failed("webview_creation_failed"));
            }
            Ok(())
        })
        .build(context);

    let report = match application {
        Ok(app) => {
            let handle = app.handle().clone();
            let timeout_state = state.clone();
            let (finished, receiver) = mpsc::channel::<()>();
            let watchdog = std::thread::spawn(move || {
                if matches!(
                    receiver.recv_timeout(TIMEOUT),
                    Err(mpsc::RecvTimeoutError::Timeout)
                ) {
                    timeout_state.finish(&handle, SmokeReport::failed("renderer_timeout"));
                }
            });
            app.run_return(|_, _| {});
            drop(finished);
            let _ = watchdog.join();
            state
                .0
                .lock()
                .unwrap_or_else(|error| error.into_inner())
                .clone()
                .unwrap_or_else(|| SmokeReport::failed("closed_before_report"))
        }
        Err(_) => SmokeReport::failed("application_creation_failed"),
    };

    // Only remove the unique directory successfully created above; locked WebView files
    // can remain temporarily and must not turn a successful renderer check into failure.
    if profile.is_absolute()
        && profile.parent() == Some(std::env::temp_dir().as_path())
        && profile
            .file_name()
            .is_some_and(|name| name.to_string_lossy().starts_with("cqg-package-smoke-"))
    {
        let _ = std::fs::remove_dir_all(profile);
    }
    emit(&report)
}
