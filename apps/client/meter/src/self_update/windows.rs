use super::*;
use crate::conflict_evidence::{system_account, validate_service_file, ServiceDirectoryPins};
use std::{
    ffi::{OsStr, OsString},
    mem::size_of,
    os::windows::{
        ffi::{OsStrExt, OsStringExt},
        fs::OpenOptionsExt,
        process::CommandExt,
    },
    process::{Command, Stdio},
    ptr,
};
use windows_sys::Win32::{
    Foundation::LocalFree,
    System::{
        Services::{
            CloseServiceHandle, OpenSCManagerW, OpenServiceW, QueryServiceConfigW,
            QUERY_SERVICE_CONFIGW, SC_MANAGER_CONNECT, SERVICE_QUERY_CONFIG,
        },
        SystemInformation::GetSystemDirectoryW,
    },
    UI::Shell::CommandLineToArgvW,
};

const SERVICE: &str = "CodexQuotaGuardMeter";
const WORKER: &str = include_str!("apply-update.ps1");
fn wide(value: &OsStr) -> Vec<u16> {
    value.encode_wide().chain(Some(0)).collect()
}
fn pinned(path: &Path) -> Result<ServiceDirectoryPins> {
    ServiceDirectoryPins::open(path).map_err(|_| fail("METER_UPDATE_PATH_UNSAFE"))
}
fn same(left: &Path, right: &Path) -> bool {
    match (fs::canonicalize(left), fs::canonicalize(right)) {
        (Ok(a), Ok(b)) => a
            .to_string_lossy()
            .eq_ignore_ascii_case(&b.to_string_lossy()),
        _ => false,
    }
}
fn string(ptr: *const u16) -> Result<OsString> {
    if ptr.is_null() {
        return Err(fail("METER_UPDATE_SERVICE_INVALID"));
    }
    let mut len = 0;
    unsafe {
        while len < 32768 && *ptr.add(len) != 0 {
            len += 1;
        }
    }
    if len == 32768 {
        return Err(fail("METER_UPDATE_SERVICE_INVALID"));
    }
    Ok(OsString::from_wide(unsafe {
        std::slice::from_raw_parts(ptr, len)
    }))
}
fn service_paths() -> Result<(PathBuf, PathBuf)> {
    let manager = unsafe { OpenSCManagerW(ptr::null(), ptr::null(), SC_MANAGER_CONNECT) };
    if manager.is_null() {
        return Err(fail("METER_UPDATE_SERVICE_INVALID"));
    }
    let name = wide(OsStr::new(SERVICE));
    let service = unsafe { OpenServiceW(manager, name.as_ptr(), SERVICE_QUERY_CONFIG) };
    unsafe { CloseServiceHandle(manager) };
    if service.is_null() {
        return Err(fail("METER_UPDATE_SERVICE_INVALID"));
    }
    let result = (|| {
        let mut buffer = vec![0usize; 8192 / size_of::<usize>()];
        let mut needed = 0;
        if unsafe { QueryServiceConfigW(service, buffer.as_mut_ptr().cast(), 8192, &mut needed) }
            == 0
        {
            return Err(fail("METER_UPDATE_SERVICE_INVALID"));
        }
        let config = unsafe { &*buffer.as_ptr().cast::<QUERY_SERVICE_CONFIGW>() };
        let account = string(config.lpServiceStartName)?
            .to_string_lossy()
            .to_string();
        if !account.eq_ignore_ascii_case("LocalSystem")
            && !account.eq_ignore_ascii_case("NT AUTHORITY\\SYSTEM")
        {
            return Err(fail("METER_UPDATE_ACCOUNT_INVALID"));
        }
        let mut count = 0;
        let args = unsafe { CommandLineToArgvW(config.lpBinaryPathName, &mut count) };
        if args.is_null() {
            return Err(fail("METER_UPDATE_SERVICE_INVALID"));
        }
        let parsed = (|| {
            if count != 4 {
                return Err(fail("METER_UPDATE_SERVICE_INVALID"));
            }
            let args = unsafe { std::slice::from_raw_parts(args, count as usize) };
            if string(args[1])? != OsStr::new("service")
                || string(args[2])? != OsStr::new("--config")
            {
                return Err(fail("METER_UPDATE_SERVICE_INVALID"));
            }
            Ok((
                PathBuf::from(string(args[0])?),
                PathBuf::from(string(args[3])?),
            ))
        })();
        unsafe { LocalFree(args.cast()) };
        parsed
    })();
    unsafe { CloseServiceHandle(service) };
    result
}
fn installation(config: &Path) -> Result<(PathBuf, ServiceDirectoryPins)> {
    if !system_account() {
        return Err(fail("METER_UPDATE_SYSTEM_REQUIRED"));
    }
    crate::server_state::check_path(config)?;
    let root = config
        .parent()
        .ok_or_else(|| fail("METER_UPDATE_PATH_UNSAFE"))?
        .to_owned();
    let pins = pinned(&root)?;
    let (binary, registered) = service_paths()?;
    if !same(&root.join("quota-guard-meter.exe"), &binary)
        || !same(config, &registered)
        || config.file_name() != Some(OsStr::new("meter-config.json"))
    {
        return Err(fail("METER_UPDATE_SERVICE_INVALID"));
    }
    crate::server_state::check_path(&binary)?;
    validate_service_file(&binary)
        .and_then(|()| validate_service_file(config))
        .map_err(|_| fail("METER_UPDATE_PATH_UNSAFE"))?;
    Ok((root, pins))
}
fn powershell() -> Result<PathBuf> {
    let mut buffer = vec![0u16; 32768];
    let len = unsafe { GetSystemDirectoryW(buffer.as_mut_ptr(), buffer.len() as u32) };
    if len == 0 || len as usize >= buffer.len() {
        return Err(fail("METER_UPDATE_WORKER_UNAVAILABLE"));
    }
    Ok(PathBuf::from(OsString::from_wide(&buffer[..len as usize]))
        .join("WindowsPowerShell/v1.0/powershell.exe"))
}
fn launch(directory: &Path, recovery: bool) -> Result<()> {
    let executable = powershell()?;
    let modules = executable
        .parent()
        .ok_or_else(|| fail("METER_UPDATE_WORKER_UNAVAILABLE"))?
        .join("Modules");
    let mut command = Command::new(executable);
    command
        .args([
            "-NoLogo",
            "-NoProfile",
            "-NonInteractive",
            "-ExecutionPolicy",
            "Bypass",
            "-File",
        ])
        .arg(directory.join("apply-update.ps1"))
        .arg("-PlanDirectory")
        .arg(directory)
        .env_remove("QUOTA_GUARD_CODEX_CAPTURE")
        .env("PSModulePath", modules)
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .creation_flags(0x0800_0000 | 0x0000_0200);
    if recovery {
        command.arg("-Recover");
    }
    command
        .spawn()
        .map_err(|_| fail("METER_UPDATE_WORKER_UNAVAILABLE"))?;
    Ok(())
}
fn setup_lock(root: &Path) -> Result<File> {
    OpenOptions::new()
        .read(true)
        .write(true)
        .create(true)
        .truncate(false)
        .share_mode(0)
        .open(root.join(".meter-setup.lock"))
        .map_err(|_| fail("METER_UPDATE_BUSY"))
}

pub(super) fn verify_work_directory(directory: &Path) -> Result<()> {
    let id = directory
        .file_name()
        .and_then(OsStr::to_str)
        .ok_or_else(|| fail("METER_UPDATE_PATH_UNSAFE"))?;
    uuid::Uuid::parse_str(id).map_err(|_| fail("METER_UPDATE_PATH_UNSAFE"))?;
    let updates = directory
        .parent()
        .ok_or_else(|| fail("METER_UPDATE_PATH_UNSAFE"))?;
    if updates.file_name() != Some(OsStr::new(".meter-updates")) {
        return Err(fail("METER_UPDATE_PATH_UNSAFE"));
    }
    let root = updates
        .parent()
        .ok_or_else(|| fail("METER_UPDATE_PATH_UNSAFE"))?;
    let config = root.join("meter-config.json");
    let (_root, _pins) = installation(&config)?;
    let _work = pinned(directory)?;
    let plan: UpdatePlan = serde_json::from_slice(&bounded(&directory.join("plan.json"), 49152)?)
        .map_err(|_| fail("METER_UPDATE_PLAN_INVALID"))?;
    if !newer(&plan.version, env!("CARGO_PKG_VERSION"))?
        || plan.previous_version != env!("CARGO_PKG_VERSION")
        || plan.created_at > now() + 60
    {
        return Err(fail("METER_UPDATE_PLAN_INVALID"));
    }
    let target = hash_file(&root.join("quota-guard-meter.exe"))?;
    if target != plan.previous_sha256 && target != plan.sha256 {
        return Err(fail("METER_UPDATE_STALE_PLAN"));
    }
    if hash_file(&config)? != plan.config_sha256
        || hash_file(&directory.join("verifier.exe"))? != plan.previous_sha256
    {
        return Err(fail("METER_UPDATE_STALE_PLAN"));
    }
    let current = configuration(&config)?;
    if crate::normalized_server_origin(&current.server_url).as_deref() != Some(plan.origin.as_str())
    {
        return Err(fail("METER_UPDATE_STALE_PLAN"));
    }
    verify_package(&directory.join("meter-new.exe"), &plan)
}

pub(super) fn recover_if_needed(config: &Path) -> Result<()> {
    let (root, _pins) = installation(config)?;
    let _lock = match setup_lock(&root) {
        Ok(lock) => lock,
        Err(_) => return Ok(()),
    };
    let updates = root.join(".meter-updates");
    let pending = updates.join("pending.json");
    if !pending.exists() {
        return Ok(());
    }
    let value: serde_json::Value = serde_json::from_slice(&bounded(&pending, 4096)?)
        .map_err(|_| fail("METER_UPDATE_PLAN_INVALID"))?;
    let id = value
        .get("directory")
        .and_then(|v| v.as_str())
        .ok_or_else(|| fail("METER_UPDATE_PLAN_INVALID"))?;
    uuid::Uuid::parse_str(id).map_err(|_| fail("METER_UPDATE_PLAN_INVALID"))?;
    let directory = updates.join(id);
    let _work = pinned(&directory)?;
    if let Ok(bytes) = bounded(&updates.join("last-result.json"), 4096) {
        if let Ok(done) = serde_json::from_slice::<serde_json::Value>(&bytes) {
            if done.get("directory").and_then(|v| v.as_str()) == Some(id)
                && matches!(
                    done.get("stage").and_then(|v| v.as_str()),
                    Some("APPLIED" | "ROLLED_BACK" | "FAILED")
                )
            {
                let _ = fs::remove_file(pending);
                return Ok(());
            }
        }
    }
    if !directory.join("previous.exe").is_file() {
        let _ = fs::remove_file(pending);
        return Ok(());
    }
    drop(_lock);
    launch(&directory, true)
}

pub(super) fn heartbeat(config: &Path) -> Result<()> {
    let (root, _pins) = installation(config)?;
    let updates = root.join(".meter-updates");
    fs::create_dir_all(&updates).map_err(|_| fail("METER_UPDATE_WRITE_FAILED"))?;
    let _work = pinned(&updates)?;
    write_json(
        &updates.join("running.json"),
        &serde_json::json!({"version":env!("CARGO_PKG_VERSION"),"atEpochSeconds":now(),"pid":std::process::id()}),
    )
}
pub(super) fn record_error(config: &Path, code: &str) {
    if let Ok((root, _pins)) = installation(config) {
        let updates = root.join(".meter-updates");
        if updates.is_dir() {
            let _ = write_json(
                &updates.join("check-status.json"),
                &serde_json::json!({"code":code,"atEpochSeconds":now()}),
            );
        }
    }
}

pub(super) fn check_and_stage(config_path: &Path, stop: &AtomicBool) -> Result<()> {
    let (root, _pins) = installation(config_path)?;
    if setup_lock(&root).is_err() {
        return Ok(());
    }
    let updates = root.join(".meter-updates");
    fs::create_dir_all(&updates).map_err(|_| fail("METER_UPDATE_WRITE_FAILED"))?;
    let _updates = pinned(&updates)?;
    cleanup_old_transactions(&updates);
    if updates.join("pending.json").exists() {
        recover_if_needed(config_path)?;
        if updates.join("pending.json").exists() {
            return Ok(());
        }
    }
    let config = configuration(config_path)?;
    let origin = crate::normalized_server_origin(&config.server_url)
        .ok_or_else(|| fail("METER_SERVER_URL_INVALID"))?;
    let Some(offered) = manifest(&origin)? else {
        return Ok(());
    };
    if let Ok(bytes) = bounded(&updates.join("last-result.json"), 4096) {
        if let Ok(value) = serde_json::from_slice::<serde_json::Value>(&bytes) {
            if value.get("version").and_then(|v| v.as_str()) == Some(offered.version.as_str())
                && value.get("stage").and_then(|v| v.as_str()) != Some("APPLIED")
                && now().saturating_sub(
                    value
                        .get("atEpochSeconds")
                        .and_then(|v| v.as_u64())
                        .unwrap_or(0),
                ) < 3600
            {
                return Ok(());
            }
        }
    }
    let id = uuid::Uuid::new_v4().to_string();
    let directory = updates.join(&id);
    fs::create_dir(&directory).map_err(|_| fail("METER_UPDATE_WRITE_FAILED"))?;
    let _work = pinned(&directory)?;
    let result = (|| {
        let plan = UpdatePlan {
            version: offered.version.clone(),
            previous_version: env!("CARGO_PKG_VERSION").into(),
            previous_sha256: hash_file(&root.join("quota-guard-meter.exe"))?,
            sha256: offered.sha256.to_ascii_lowercase(),
            size_bytes: offered.size_bytes,
            signature: offered.signature.clone(),
            origin: origin.clone(),
            config_sha256: hash_file(config_path)?,
            created_at: now(),
        };
        let client = reqwest::blocking::Client::builder()
            .no_proxy()
            .redirect(reqwest::redirect::Policy::none())
            .connect_timeout(Duration::from_secs(10))
            .timeout(Duration::from_secs(180))
            .build()
            .map_err(|_| fail("METER_UPDATE_NETWORK"))?;
        let mut response = client
            .get(asset_url(&origin, &offered.url)?)
            .send()
            .map_err(|_| fail("METER_UPDATE_NETWORK"))?;
        if response.status() != reqwest::StatusCode::OK
            || response
                .content_length()
                .is_some_and(|size| size != plan.size_bytes)
        {
            return Err(fail("METER_UPDATE_DOWNLOAD_INVALID"));
        }
        let staged = directory.join("meter-new.exe");
        let mut output = OpenOptions::new()
            .create_new(true)
            .write(true)
            .open(&staged)
            .map_err(|_| fail("METER_UPDATE_WRITE_FAILED"))?;
        let mut received = 0;
        let mut buffer = [0u8; 65536];
        loop {
            if stop.load(Ordering::Relaxed) {
                return Err(fail("METER_UPDATE_CANCELLED"));
            }
            let count = response
                .read(&mut buffer)
                .map_err(|_| fail("METER_UPDATE_NETWORK"))?;
            if count == 0 {
                break;
            }
            received += count as u64;
            if received > plan.size_bytes {
                return Err(fail("METER_UPDATE_TOO_LARGE"));
            }
            output
                .write_all(&buffer[..count])
                .map_err(|_| fail("METER_UPDATE_WRITE_FAILED"))?;
        }
        output
            .sync_all()
            .map_err(|_| fail("METER_UPDATE_WRITE_FAILED"))?;
        drop(output);
        verify_package(&staged, &plan)?;
        if stop.load(Ordering::Relaxed) || hash_file(config_path)? != plan.config_sha256 {
            return Err(fail("METER_UPDATE_STALE_PLAN"));
        }
        let latest = manifest(&origin)?.ok_or_else(|| fail("METER_UPDATE_WITHDRAWN"))?;
        if latest.version != plan.version || !latest.sha256.eq_ignore_ascii_case(&plan.sha256) {
            return Err(fail("METER_UPDATE_WITHDRAWN"));
        }
        fs::copy(
            root.join("quota-guard-meter.exe"),
            directory.join("verifier.exe"),
        )
        .map_err(|_| fail("METER_UPDATE_WRITE_FAILED"))?;
        fs::write(directory.join("apply-update.ps1"), WORKER.as_bytes())
            .map_err(|_| fail("METER_UPDATE_WRITE_FAILED"))?;
        write_json(&directory.join("plan.json"), &plan)?;
        write_json(
            &updates.join("pending.json"),
            &serde_json::json!({"directory":id,"atEpochSeconds":now()}),
        )?;
        launch(&directory, false)
    })();
    drop(_work);
    if result.is_err() && !updates.join("pending.json").exists() {
        for name in [
            "meter-new.exe",
            "verifier.exe",
            "apply-update.ps1",
            "plan.json",
        ] {
            let _ = fs::remove_file(directory.join(name));
        }
        let _ = fs::remove_dir(&directory);
    }
    result
}

fn cleanup_old_transactions(root: &Path) {
    let pending = if root.join("pending.json").exists() {
        let Ok(bytes) = bounded(&root.join("pending.json"), 4096) else {
            return;
        };
        let Ok(value) = serde_json::from_slice::<serde_json::Value>(&bytes) else {
            return;
        };
        value
            .get("directory")
            .and_then(|v| v.as_str())
            .map(str::to_owned)
    } else {
        None
    };
    let Ok(entries) = fs::read_dir(root) else {
        return;
    };
    let mut directories = entries
        .filter_map(|entry| entry.ok())
        .filter_map(|entry| {
            let name = entry.file_name();
            uuid::Uuid::parse_str(name.to_str()?).ok()?;
            let path = entry.path();
            if crate::server_state::check_path(&path).is_err() {
                return None;
            }
            let metadata = entry.metadata().ok()?;
            if !metadata.is_dir() {
                return None;
            }
            let modified = metadata
                .modified()
                .ok()?
                .duration_since(UNIX_EPOCH)
                .ok()?
                .as_secs();
            Some((modified, path))
        })
        .collect::<Vec<_>>();
    directories.sort_by(|a, b| b.0.cmp(&a.0));
    for (modified, directory) in directories.into_iter().skip(2) {
        if directory.file_name().and_then(OsStr::to_str) == pending.as_deref() {
            continue;
        }
        if now().saturating_sub(modified) < 3600 {
            continue;
        }
        // Only known files in generated UUID directories; never traverse user data or links.
        if directory.parent() != Some(root) {
            continue;
        }
        let Ok(pins) = pinned(&directory) else {
            continue;
        };
        for name in [
            "meter-new.exe",
            "verifier.exe",
            "apply-update.ps1",
            "plan.json",
            "previous.exe",
            "meter-new.exe.replacement",
            "restore.exe",
        ] {
            let path = directory.join(name);
            if crate::server_state::check_path(&path).is_ok() {
                let _ = fs::remove_file(path);
            }
        }
        drop(pins);
        let _ = fs::remove_dir(directory);
    }
}
