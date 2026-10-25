//! Isolated packaged-renderer check. Never register the application's commands or setup.

use std::{
    io::{Read, Write},
    net::TcpListener,
    sync::{mpsc, Arc, Mutex},
    time::{Duration, Instant},
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
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), 5000);
    const target = __CQG_CONNECTIVITY_URL__;
    const fetchProbe = fetch(target, {
      credentials: 'omit', signal: controller.signal,
      headers: {'X-Quota-Guard-Device-ID': '00000000-0000-4000-8000-000000000000'}
    }).then(async response => __CQG_REMOTE_PROBE__ ? response.status === 401 :
      response.status === 200 && (await response.json()).probe === 'CQG_HTTP_PROBE_V1')
      .catch(() => false);
    const xhrProbe = new Promise(resolve => {
      const xhr = new XMLHttpRequest();
      xhr.open('GET', target); xhr.timeout = 5000;
      xhr.setRequestHeader('X-Quota-Guard-Device-ID', '00000000-0000-4000-8000-000000000000');
      xhr.onload = () => {
        try { resolve(__CQG_REMOTE_PROBE__ ? xhr.status === 401 :
          xhr.status === 200 && JSON.parse(xhr.responseText).probe === 'CQG_HTTP_PROBE_V1'); }
        catch { resolve(false); }
      };
      xhr.onerror = xhr.ontimeout = () => resolve(false);
      xhr.send();
    });
    Promise.all([fetchProbe, xhrProbe]).then(results => {
        clearTimeout(timeout);
        return window.__TAURI_INTERNALS__.invoke('smoke_report', {
          rootRendered, stylesheetLoaded, packagedOrigin, centralHttp: results.every(Boolean)
        });
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
    central_http: bool,
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
            central_http: false,
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
    central_http: bool,
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
        && packaged_origin
        && central_http;
    let mut report = SmokeReport::failed(if ok {
        "loaded"
    } else if trusted_origin
        && root_rendered
        && stylesheet_loaded
        && packaged_origin
        && !central_http
    {
        "http_transport_blocked"
    } else {
        "invalid_renderer_report"
    });
    report.ok = ok;
    report.root_rendered = root_rendered;
    report.stylesheet_loaded = stylesheet_loaded;
    report.packaged_origin = packaged_origin && trusted_origin;
    report.central_http = central_http;
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

/// A bounded, credential-free CORS peer proves the packaged renderer can make
/// the same cross-origin HTTP request used by the desktop's Axios transport.
struct HttpProbe {
    url: String,
    stop: mpsc::Sender<()>,
    worker: Option<std::thread::JoinHandle<()>>,
}

impl HttpProbe {
    fn start() -> std::io::Result<Self> {
        let listener = TcpListener::bind("127.0.0.1:0")?;
        listener.set_nonblocking(true)?;
        let url = format!("http://{}/cqg-smoke", listener.local_addr()?);
        let (stop, receiver) = mpsc::channel();
        let worker = std::thread::spawn(move || {
            let deadline = Instant::now() + TIMEOUT;
            while Instant::now() < deadline {
                match receiver.try_recv() {
                    Ok(()) | Err(mpsc::TryRecvError::Disconnected) => break,
                    Err(mpsc::TryRecvError::Empty) => {}
                }
                let Ok((mut stream, _)) = listener.accept() else {
                    std::thread::sleep(Duration::from_millis(10));
                    continue;
                };
                let _ = stream.set_read_timeout(Some(Duration::from_millis(200)));
                let read_deadline = Instant::now() + Duration::from_secs(1);
                let mut bytes = Vec::new();
                let mut buffer = [0u8; 1024];
                while bytes.len() < 8192 && Instant::now() < read_deadline {
                    let Ok(size) = stream.read(&mut buffer) else {
                        break;
                    };
                    if size == 0 {
                        break;
                    }
                    bytes.extend_from_slice(&buffer[..size]);
                    if bytes.windows(4).any(|part| part == b"\r\n\r\n") {
                        break;
                    }
                }
                let request = String::from_utf8_lossy(&bytes);
                let origin = request.lines().find_map(|line| {
                    let (name, value) = line.split_once(':')?;
                    name.eq_ignore_ascii_case("Origin").then_some(value.trim())
                });
                let Some(
                    origin @ ("http://tauri.localhost"
                    | "https://tauri.localhost"
                    | "tauri://localhost"),
                ) = origin
                else {
                    continue;
                };
                let (status, body) = if request.starts_with("OPTIONS /cqg-smoke ") {
                    ("204 No Content", "")
                } else if request.starts_with("GET /cqg-smoke ") {
                    ("200 OK", "{\"probe\":\"CQG_HTTP_PROBE_V1\"}")
                } else {
                    continue;
                };
                let response = format!("HTTP/1.1 {status}\r\nContent-Type: application/json\r\nContent-Length: {}\r\nAccess-Control-Allow-Origin: {origin}\r\nAccess-Control-Allow-Methods: GET, OPTIONS\r\nAccess-Control-Allow-Headers: X-Quota-Guard-Device-ID\r\nAccess-Control-Allow-Private-Network: true\r\nConnection: close\r\n\r\n{body}", body.len());
                let _ = stream.write_all(response.as_bytes());
            }
        });
        Ok(Self {
            url,
            stop,
            worker: Some(worker),
        })
    }
}

impl Drop for HttpProbe {
    fn drop(&mut self) {
        let _ = self.stop.send(());
        if let Some(worker) = self.worker.take() {
            let _ = worker.join();
        }
    }
}

pub(crate) fn run(context: tauri::Context<tauri::Wry>) -> i32 {
    run_inner(context, None)
}

pub(crate) fn run_central(context: tauri::Context<tauri::Wry>, origin: &str) -> i32 {
    let Ok(origin) = url::Url::parse(origin) else {
        return emit(&SmokeReport::failed("invalid_probe_origin"));
    };
    if !matches!(origin.scheme(), "http" | "https")
        || origin.host_str().is_none()
        || !origin.username().is_empty()
        || origin.password().is_some()
        || origin.path() != "/"
        || origin.query().is_some()
        || origin.fragment().is_some()
    {
        return emit(&SmokeReport::failed("invalid_probe_origin"));
    }
    let Ok(target) = origin.join("api/v1/users/me") else {
        return emit(&SmokeReport::failed("invalid_probe_origin"));
    };
    run_inner(context, Some(target.to_string()))
}

fn run_inner(mut context: tauri::Context<tauri::Wry>, central_url: Option<String>) -> i32 {
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

    let Ok(http_probe) = HttpProbe::start() else {
        return emit(&SmokeReport::failed("loopback_probe_unavailable"));
    };
    let remote = central_url.is_some();
    let target_json = serde_json::to_string(central_url.as_deref().unwrap_or(&http_probe.url))
        .expect("HTTP probe URL serializes as a JSON string");
    let probe_script = PROBE_SCRIPT
        .replace("__CQG_CONNECTIVITY_URL__", &target_json)
        .replace(
            "__CQG_REMOTE_PROBE__",
            if remote { "true" } else { "false" },
        );

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
                .initialization_script(&probe_script)
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
