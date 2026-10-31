use semver::Version;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::{
    fs,
    io::Write,
    path::Path,
    sync::{
        atomic::{AtomicBool, Ordering},
        Arc, Mutex,
    },
    time::{Duration, Instant},
};
use tauri::{ipc::Channel, AppHandle, Manager, WebviewWindow};
use tauri_plugin_notification::NotificationExt;
use tauri_plugin_updater::UpdaterExt;
use url::Url;

const MAX_BYTES: u64 = 512 * 1024 * 1024;
static INSTALLING: AtomicBool = AtomicBool::new(false);
static NOTICE_LOCK: Mutex<()> = Mutex::new(());

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct ClientVersion {
    current_version: String,
    target: &'static str,
    arch: &'static str,
}

fn platform() -> &'static str {
    if cfg!(target_os = "macos") {
        "darwin"
    } else if cfg!(target_os = "windows") {
        "windows"
    } else {
        "linux"
    }
}
fn main_only(window: &WebviewWindow) -> Result<(), String> {
    if window.label() == "main" {
        Ok(())
    } else {
        Err("无效的客户端更新操作。".into())
    }
}
fn origin(app: &AppHandle, expected: &str) -> Result<Url, String> {
    let configured = crate::server_config::load(app).url;
    let url = Url::parse(&configured).map_err(|_| "服务器地址无效。")?;
    if url.origin().ascii_serialization() != expected {
        return Err("服务器地址已变化，请重新检查更新。".into());
    }
    Ok(url)
}
fn newer(value: &str, current: &str) -> Result<Version, String> {
    let version = Version::parse(value).map_err(|_| "更新版本号无效。")?;
    let current = Version::parse(current).map_err(|_| "客户端版本号无效。")?;
    if version <= current || !version.pre.is_empty() || !version.build.is_empty() {
        return Err("该版本不高于当前版本，请重新检查更新。".into());
    }
    Ok(version)
}

#[tauri::command]
pub(crate) fn desktop_update_info(
    app: AppHandle,
    window: WebviewWindow,
) -> Result<ClientVersion, String> {
    main_only(&window)?;
    Ok(ClientVersion {
        current_version: app.package_info().version.to_string(),
        target: platform(),
        arch: std::env::consts::ARCH,
    })
}

#[derive(Default, Deserialize, Serialize)]
struct Notices {
    last_notified_version: Option<String>,
}

// Commit before dispatching so a restart or concurrent check cannot repeat a notice.
fn mark_notification(path: &Path, version: &Version) -> Result<bool, String> {
    let previous = match fs::read(path) {
        Ok(bytes) if bytes.len() <= 1024 => {
            serde_json::from_slice::<Notices>(&bytes).map_err(|_| "更新提醒记录无法读取。")?
        }
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => Notices::default(),
        _ => return Err("更新提醒记录无法读取。".into()),
    };
    if previous
        .last_notified_version
        .as_deref()
        .and_then(|v| Version::parse(v).ok())
        .is_some_and(|v| v >= *version)
    {
        return Ok(false);
    }
    let parent = path.parent().ok_or("更新提醒目录不可用。")?;
    fs::create_dir_all(parent).map_err(|_| "更新提醒目录不可写。")?;
    let temporary = parent.join(format!(".update-notice-{}.tmp", uuid::Uuid::new_v4()));
    let result = (|| {
        let bytes = serde_json::to_vec(&Notices {
            last_notified_version: Some(version.to_string()),
        })
        .map_err(|_| "更新提醒保存失败。")?;
        let mut file = fs::OpenOptions::new()
            .write(true)
            .create_new(true)
            .open(&temporary)
            .map_err(|_| "更新提醒保存失败。")?;
        file.write_all(&bytes)
            .and_then(|()| file.sync_all())
            .map_err(|_| "更新提醒保存失败。")?;
        drop(file);
        fs::rename(&temporary, path).map_err(|_| "更新提醒保存失败。")?;
        Ok(true)
    })();
    let _ = fs::remove_file(temporary);
    result
}

#[tauri::command]
pub(crate) async fn desktop_update_notify(
    app: AppHandle,
    window: WebviewWindow,
    version: String,
    expected_origin: String,
) -> Result<bool, String> {
    main_only(&window)?;
    origin(&app, &expected_origin)?;
    let version = newer(&version, &app.package_info().version.to_string())?;
    tauri::async_runtime::spawn_blocking(move || {
        let _lock = NOTICE_LOCK.lock().map_err(|_| "更新提醒暂不可用。")?;
        let path = app
            .path()
            .app_config_dir()
            .map_err(|_| "更新提醒目录不可用。")?
            .join("update-notices.json");
        if !mark_notification(&path, &version)? {
            return Ok(false);
        }
        app.notification()
            .builder()
            .title("Codex Quota Guard 有新版本")
            .body(format!(
                "v{version} 已发布。请在客户端左侧版本入口或设置中下载更新。"
            ))
            .show()
            .map_err(|_| "系统未能显示更新通知，请在设置中查看更新。".to_owned())?;
        Ok(true)
    })
    .await
    .map_err(|_| "更新提醒暂不可用。".to_owned())?
}

#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct Progress {
    stage: &'static str,
    downloaded: u64,
    total: u64,
}
struct Installing;
impl Drop for Installing {
    fn drop(&mut self) {
        INSTALLING.store(false, Ordering::Release);
    }
}

fn asset_url(server: &Url, offered: &Url) -> Result<Url, String> {
    if !offered.username().is_empty()
        || offered.password().is_some()
        || offered.query().is_some()
        || offered.fragment().is_some()
        || !offered
            .path()
            .starts_with("/api/v1/desktop-updates/assets/")
    {
        return Err("更新包地址无效，请联系管理员重新发布。".into());
    }
    // Always use the configured server, including when it sits behind an HTTPS reverse proxy.
    server
        .join(offered.path())
        .map_err(|_| "更新包地址无效。".into())
}
fn check_bytes(bytes: &[u8], total: u64, hash: &str) -> Result<(), String> {
    if total == 0 || total > MAX_BYTES || bytes.len() as u64 != total {
        return Err("更新包大小不符，请重新下载。".into());
    }
    if hash.len() != 64
        || !hash.bytes().all(|b| b.is_ascii_hexdigit())
        || format!("{:x}", Sha256::digest(bytes)) != hash.to_lowercase()
    {
        return Err("更新包校验失败，请重新下载。".into());
    }
    Ok(())
}

#[tauri::command]
pub(crate) async fn desktop_update_install(
    app: AppHandle,
    window: WebviewWindow,
    expected_version: String,
    expected_origin: String,
    on_progress: Channel<Progress>,
) -> Result<(), String> {
    main_only(&window)?;
    let server = origin(&app, &expected_origin)?;
    newer(&expected_version, &app.package_info().version.to_string())?;
    if INSTALLING
        .compare_exchange(false, true, Ordering::AcqRel, Ordering::Acquire)
        .is_err()
    {
        return Err("已有更新正在进行。".into());
    }
    let _installing = Installing;
    let emit = |stage, downloaded, total| {
        let _ = on_progress.send(Progress {
            stage,
            downloaded,
            total,
        });
    };
    emit("checking", 0, 0);
    let endpoint = server
        .join(&format!(
            "/api/v1/desktop-updates/manifest/{}/{}/{}",
            platform(),
            std::env::consts::ARCH,
            app.package_info().version
        ))
        .map_err(|_| "更新地址无效。")?;
    let cleaned = Arc::new(AtomicBool::new(false));
    let cleanup_flag = cleaned.clone();
    let cleanup_app = app.clone();
    let updater = app
        .updater_builder()
        .endpoints(vec![endpoint])
        .map_err(|_| "无法配置更新检查。")?
        .configure_client(|client| {
            client
                .redirect(reqwest::redirect::Policy::none())
                .connect_timeout(Duration::from_secs(20))
        })
        .on_before_exit(move || {
            cleanup_flag.store(true, Ordering::Release);
            cleanup_app.cleanup_before_exit();
        })
        .timeout(Duration::from_secs(30))
        .build()
        .map_err(|_| "无法初始化更新程序。")?;
    let mut update = updater
        .check()
        .await
        .map_err(|_| "无法获取更新信息，请检查网络或稍后重试。")?
        .ok_or("该更新已撤回或当前已是最新版本，请重新检查。")?;
    if update.version != expected_version {
        return Err("后台版本信息已变化，请重新检查后更新。".into());
    }
    update.download_url = asset_url(&server, &update.download_url)?;
    let total = update
        .raw_json
        .get("sizeBytes")
        .and_then(|n| n.as_u64())
        .filter(|n| *n > 0 && *n <= MAX_BYTES)
        .ok_or("更新包大小信息无效。")?;
    let hash = update
        .raw_json
        .get("sha256")
        .and_then(|v| v.as_str())
        .ok_or("更新包缺少校验信息。")?
        .to_owned();
    let client = reqwest::Client::builder()
        .redirect(reqwest::redirect::Policy::none())
        .connect_timeout(Duration::from_secs(20))
        .timeout(Duration::from_secs(1800))
        .build()
        .map_err(|_| "无法创建更新下载。")?;
    let mut response = client
        .get(update.download_url.clone())
        .send()
        .await
        .map_err(|_| "下载连接失败，请检查网络。")?;
    if response.status() != reqwest::StatusCode::OK
        || response.content_length().is_some_and(|n| n != total)
    {
        return Err("下载请求未成功或更新包已变化，请重新检查。".into());
    }
    let mut bytes = Vec::new();
    let mut last = Instant::now();
    emit("downloading", 0, total);
    while let Some(chunk) = response.chunk().await.map_err(|_| "下载中断，请重试。")? {
        if bytes.len() as u64 + chunk.len() as u64 > total {
            return Err("下载超过预期大小，已停止更新。".into());
        }
        bytes
            .try_reserve(chunk.len())
            .map_err(|_| "内存不足，请关闭其他程序后重试。")?;
        bytes.extend_from_slice(&chunk);
        if last.elapsed() >= Duration::from_millis(100) {
            emit("downloading", bytes.len() as u64, total);
            last = Instant::now();
        }
    }
    emit("verifying", total, total);
    check_bytes(&bytes, total, &hash)?;
    update
        .verify(&bytes)
        .map_err(|_| "更新包签名或版本校验失败，已停止更新。")?;
    origin(&app, &expected_origin)?;
    if let Some(state) = app.try_state::<crate::window_state::WindowState>() {
        state.flush();
    }
    emit("installing", total, total);
    let installed = tauri::async_runtime::spawn_blocking(move || update.install(bytes)).await;
    if !matches!(installed, Ok(Ok(()))) {
        // The SDK hides windows and releases tray resources just before ShellExecute.
        // Restore an accessible main window if launching the installer then fails.
        if cleaned.load(Ordering::Acquire) {
            if let Some(tray) = app.try_state::<crate::tray::TrayState>() {
                tray.set_available(false);
            }
            crate::tray::show_main_window(&app);
            return Err("更新程序未能启动，已恢复主窗口。请重新启动客户端后重试。".into());
        }
        return Err("无法启动更新程序，请重试或手动安装。".into());
    }
    // Windows exits only after its installer starts successfully; other platforms replace then restart.
    #[cfg(not(target_os = "windows"))]
    app.restart();
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn notifications_persist_a_monotonic_high_water_mark() {
        let dir = std::env::temp_dir().join(format!("cqg-notices-{}", uuid::Uuid::new_v4()));
        let path = dir.join("notices.json");
        assert!(mark_notification(&path, &Version::parse("0.4.9").unwrap()).unwrap());
        assert!(!mark_notification(&path, &Version::parse("0.4.9").unwrap()).unwrap());
        assert!(mark_notification(&path, &Version::parse("0.4.10").unwrap()).unwrap());
        assert!(!mark_notification(&path, &Version::parse("0.4.8").unwrap()).unwrap());
        fs::remove_file(path).unwrap();
        fs::remove_dir(dir).unwrap();
    }
    #[test]
    fn corrupt_notification_state_is_not_overwritten_or_repeated() {
        let path = std::env::temp_dir().join(format!("cqg-notice-{}.json", uuid::Uuid::new_v4()));
        fs::write(&path, b"broken").unwrap();
        assert!(mark_notification(&path, &Version::new(1, 0, 0)).is_err());
        assert_eq!(fs::read(&path).unwrap(), b"broken");
        fs::remove_file(path).unwrap();
    }
    #[test]
    fn versions_never_downgrade_and_compare_numerically() {
        assert!(newer("0.4.10", "0.4.9").is_ok());
        assert!(newer("0.4.9", "0.4.10").is_err());
        assert!(newer("0.4.10", "0.4.10").is_err());
        assert!(newer("0.5.0-rc.1", "0.4.10").is_err());
    }
    #[test]
    fn assets_are_pinned_to_the_configured_origin() {
        let server = Url::parse("https://example.com").unwrap();
        let offered =
            Url::parse("http://backend:8080/api/v1/desktop-updates/assets/123/client.exe").unwrap();
        assert_eq!(
            asset_url(&server, &offered).unwrap().as_str(),
            "https://example.com/api/v1/desktop-updates/assets/123/client.exe"
        );
        for value in [
            "https://evil.example/run.exe",
            "https://example.com/api/v1/desktop-updates/assets/123/a.exe?x=1",
        ] {
            assert!(asset_url(&server, &Url::parse(value).unwrap()).is_err());
        }
    }
    #[test]
    fn size_and_checksum_must_match_before_installation() {
        let data = b"installer";
        let hash = format!("{:x}", Sha256::digest(data));
        assert!(check_bytes(data, data.len() as u64, &hash).is_ok());
        assert!(check_bytes(b"tampered", data.len() as u64, &hash).is_err());
        assert!(check_bytes(data, MAX_BYTES + 1, &hash).is_err());
        assert!(check_bytes(data, 0, &hash).is_err());
    }
}
