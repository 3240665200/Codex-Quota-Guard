use crate::{backend::BackendState, meter_service, tray};
use serde::{Deserialize, Serialize};
use std::{
    fs::{self, OpenOptions},
    io::Write,
    path::{Path, PathBuf},
    sync::{
        atomic::{AtomicBool, Ordering},
        Arc, Mutex,
    },
    thread::{self, JoinHandle},
    time::{Duration, Instant, SystemTime, UNIX_EPOCH},
};
use tauri::{AppHandle, Manager};
use uuid::Uuid;

const GUARD_WAIT: Duration = Duration::from_secs(18);
const GUARD_FRESH_SECONDS: u64 = 30;
const LEASE_FILE: &str = "ccswitch-guard.json";
const STATUS_FILE: &str = "meter-status.json";
const GUARD_STATUS_FILE: &str = "ccswitch-guard-status.json";

#[derive(Clone, Copy, Debug, Eq, PartialEq, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub(crate) enum GateState {
    Ready,
    Checking,
    AdminRequired,
    MeterUnavailable,
    GuardError,
    BackendUnavailable,
    #[cfg(not(windows))]
    Unsupported,
}

#[derive(Clone, Copy, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct GateResult {
    pub state: GateState,
    pub cc_switch_terminated: bool,
}

impl GateResult {
    pub(crate) fn failed() -> Self {
        Self::new(GateState::GuardError)
    }

    fn new(state: GateState) -> Self {
        Self {
            state,
            cc_switch_terminated: false,
        }
    }

    fn ready(terminated: bool) -> Self {
        Self {
            state: GateState::Ready,
            cc_switch_terminated: terminated,
        }
    }

    fn settle(self) -> Self {
        if self.state == GateState::Checking {
            Self::new(GateState::GuardError)
        } else {
            self
        }
    }
}

#[derive(Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct GuardLeaseRecord {
    pid: u32,
    created_at: u64,
    lease_id: String,
}

struct GuardLease {
    id: String,
    path: PathBuf,
    alive: Arc<AtomicBool>,
    healthy: Arc<AtomicBool>,
    writer: Option<JoinHandle<()>>,
}

impl GuardLease {
    fn stop(mut self) {
        self.alive.store(false, Ordering::Release);
        if let Some(writer) = self.writer.take() {
            writer.thread().unpark();
            let _ = writer.join();
        }
        if read_lease(&self.path).is_some_and(|record| record.lease_id == self.id) {
            let _ = fs::remove_file(&self.path);
        }
    }
}

pub(crate) struct StartupGate {
    operation: Mutex<()>,
    lease: Mutex<Option<GuardLease>>,
    ready: Arc<AtomicBool>,
    closed: AtomicBool,
}

impl Default for StartupGate {
    fn default() -> Self {
        Self {
            operation: Mutex::new(()),
            lease: Mutex::new(None),
            ready: Arc::new(AtomicBool::new(false)),
            closed: AtomicBool::new(false),
        }
    }
}

impl StartupGate {
    pub(crate) fn ready_flag(&self) -> Arc<AtomicBool> {
        Arc::clone(&self.ready)
    }

    pub(crate) fn should_monitor(&self) -> bool {
        !self.closed.load(Ordering::Acquire)
    }

    pub(crate) fn is_ready(&self) -> bool {
        self.ready.load(Ordering::Acquire)
    }

    pub(crate) fn stop(&self) {
        self.closed.store(true, Ordering::Release);
        self.ready.store(false, Ordering::Release);
        let mut lease = self
            .lease
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        // A concurrent check may have committed readiness while stop waited
        // for the lease lock. The second reset makes shutdown the final state.
        self.ready.store(false, Ordering::Release);
        if let Some(lease) = lease.take() {
            lease.stop();
        }
    }

    fn ensure_lease(&self, app: &AppHandle) -> Option<String> {
        let mut held = self.lease.lock().ok()?;
        if self.closed.load(Ordering::Acquire) {
            return None;
        }
        if let Some(lease) = held.as_ref() {
            if lease.healthy.load(Ordering::Acquire) {
                return Some(lease.id.clone());
            }
        }
        if let Some(lease) = held.take() {
            lease.stop();
        }
        let status_path = status_path(app)?;
        let path = status_path.parent()?.join(LEASE_FILE);
        let created_at = process_created_at()?;
        let record = GuardLeaseRecord {
            pid: std::process::id(),
            created_at,
            lease_id: Uuid::new_v4().to_string(),
        };
        if let Some(existing) = read_lease(&path) {
            if existing.lease_id != record.lease_id && process_is_same(&existing) {
                return None;
            }
        }
        write_lease(&path, &record).ok()?;
        let alive = Arc::new(AtomicBool::new(true));
        let healthy = Arc::new(AtomicBool::new(true));
        let writer_alive = Arc::clone(&alive);
        let writer_healthy = Arc::clone(&healthy);
        let writer_path = path.clone();
        let writer_record = GuardLeaseRecord {
            pid: record.pid,
            created_at: record.created_at,
            lease_id: record.lease_id.clone(),
        };
        let writer = thread::spawn(move || {
            while writer_alive.load(Ordering::Acquire) {
                thread::park_timeout(Duration::from_secs(1));
                if !writer_alive.load(Ordering::Acquire) {
                    break;
                }
                if read_lease(&writer_path)
                    .map_or(true, |current| current.lease_id != writer_record.lease_id)
                    || write_lease(&writer_path, &writer_record).is_err()
                {
                    writer_healthy.store(false, Ordering::Release);
                    break;
                }
            }
        });
        let id = record.lease_id.clone();
        *held = Some(GuardLease {
            id: record.lease_id,
            path,
            alive,
            healthy,
            writer: Some(writer),
        });
        Some(id)
    }

    fn current_lease_id(&self) -> Option<String> {
        self.lease.lock().ok()?.as_ref().and_then(|lease| {
            lease
                .healthy
                .load(Ordering::Acquire)
                .then(|| lease.id.clone())
        })
    }

    fn inspect_guard(&self, app: &AppHandle, lease_id: &str) -> GateResult {
        let Some(path) = guard_status_path(app) else {
            return GateResult::new(GateState::GuardError);
        };
        let Ok(metadata) = fs::symlink_metadata(&path) else {
            return GateResult::new(GateState::Checking);
        };
        if !metadata.is_file() || metadata.len() > 4096 {
            return GateResult::new(GateState::GuardError);
        }
        let Ok(bytes) = fs::read(&path) else {
            return GateResult::new(GateState::Checking);
        };
        classify_guard_status(&bytes, lease_id, unix_seconds())
    }

    pub(crate) fn check(&self, app: &AppHandle) -> GateResult {
        let Ok(_operation) = self.operation.lock() else {
            return self.block(app, GateResult::new(GateState::GuardError));
        };
        if self.closed.load(Ordering::Acquire) {
            return self.block(app, GateResult::new(GateState::GuardError));
        }
        self.ready.store(false, Ordering::Release);
        if let Some(backend) = app.try_state::<BackendState>() {
            backend.stop();
        }
        #[cfg(not(windows))]
        return self.block(app, GateResult::new(GateState::Unsupported));
        #[cfg(windows)]
        {
            if !is_elevated_admin() {
                return self.block(app, GateResult::new(GateState::AdminRequired));
            }
            let Some(lease_id) = self.ensure_lease(app) else {
                return self.block(app, GateResult::new(GateState::GuardError));
            };
            if self.closed.load(Ordering::Acquire) {
                return self.block(app, GateResult::new(GateState::GuardError));
            }
            // The meter can answer faster than its next tick. This result only
            // becomes usable after it echoes this exact lease ID.
            if !matches!(
                tray::ensure_meter_serialized(app),
                meter_service::MeterStartOutcome::Running
                    | meter_service::MeterStartOutcome::Started
                    | meter_service::MeterStartOutcome::Installed
            ) {
                return self.block(app, GateResult::new(GateState::MeterUnavailable));
            }
            let deadline = Instant::now() + GUARD_WAIT;
            let guard = loop {
                if self.closed.load(Ordering::Acquire)
                    || self.current_lease_id().as_deref() != Some(lease_id.as_str())
                {
                    break GateResult::new(GateState::GuardError);
                }
                let result = self.inspect_guard(app, &lease_id);
                if result.state != GateState::Checking {
                    break result;
                }
                if Instant::now() >= deadline {
                    break GateResult::new(GateState::GuardError);
                }
                thread::sleep(Duration::from_millis(200));
            };
            if guard.state != GateState::Ready {
                return self.block(app, guard);
            }
            if self.closed.load(Ordering::Acquire)
                || self.current_lease_id().as_deref() != Some(lease_id.as_str())
            {
                return self.block(app, GateResult::new(GateState::GuardError));
            }
            if let Some(backend) = app.try_state::<BackendState>() {
                if backend.restart(app).is_err() {
                    return self.block(app, GateResult::new(GateState::BackendUnavailable));
                }
            } else {
                let Ok(backend) = BackendState::launch(app, self.ready_flag()) else {
                    return self.block(app, GateResult::new(GateState::BackendUnavailable));
                };
                if !app.manage(backend) {
                    return self.block(app, GateResult::new(GateState::BackendUnavailable));
                }
            }
            if self.closed.load(Ordering::Acquire)
                || self.current_lease_id().as_deref() != Some(lease_id.as_str())
            {
                return self.block(app, GateResult::new(GateState::GuardError));
            }
            let current_guard = self.inspect_guard(app, &lease_id);
            if current_guard.state != GateState::Ready {
                return self.block(app, current_guard.settle());
            }
            let Ok(held) = self.lease.lock() else {
                return self.block(app, GateResult::new(GateState::GuardError));
            };
            if self.closed.load(Ordering::Acquire)
                || held.as_ref().map_or(true, |lease| {
                    lease.id != lease_id || !lease.healthy.load(Ordering::Acquire)
                })
            {
                drop(held);
                return self.block(app, GateResult::new(GateState::GuardError));
            }
            self.ready.store(true, Ordering::Release);
            GateResult::ready(guard.cc_switch_terminated || current_guard.cc_switch_terminated)
        }
    }

    pub(crate) fn status(&self, app: &AppHandle) -> GateResult {
        let Ok(_operation) = self.operation.try_lock() else {
            return GateResult::new(GateState::Checking);
        };
        if self.closed.load(Ordering::Acquire) {
            return self.block(app, GateResult::new(GateState::GuardError));
        }
        let Some(lease_id) = self.current_lease_id() else {
            return self.block(app, GateResult::new(GateState::GuardError));
        };
        let result = self.inspect_guard(app, &lease_id);
        if result.state != GateState::Ready {
            return self.block(app, result.settle());
        }
        if !self.ready.load(Ordering::Acquire) {
            // We hold the operation lock, so no startup check is in flight.
            // A previous monitor failure stopped Java and requires an explicit retry.
            return GateResult::new(GateState::GuardError);
        }
        if self.closed.load(Ordering::Acquire) {
            return self.block(app, GateResult::new(GateState::GuardError));
        }
        result
    }

    fn block(&self, app: &AppHandle, result: GateResult) -> GateResult {
        self.ready.store(false, Ordering::Release);
        if let Some(backend) = app.try_state::<BackendState>() {
            backend.stop();
        }
        result
    }
}

fn status_path(app: &AppHandle) -> Option<PathBuf> {
    Some(app.path().app_data_dir().ok()?.join(STATUS_FILE))
}

fn guard_status_path(app: &AppHandle) -> Option<PathBuf> {
    Some(app.path().app_data_dir().ok()?.join(GUARD_STATUS_FILE))
}

fn unix_seconds() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_or(0, |elapsed| elapsed.as_secs())
}

fn classify_guard_status(bytes: &[u8], lease_id: &str, now: u64) -> GateResult {
    let Ok(status) = serde_json::from_slice::<serde_json::Value>(bytes) else {
        return GateResult::new(GateState::Checking);
    };
    if status.get("ccSwitchGuardLeaseId").and_then(|v| v.as_str()) != Some(lease_id) {
        return GateResult::new(GateState::Checking);
    }
    let Some(checked_at) = status
        .get("ccSwitchGuardCheckedAt")
        .and_then(|v| v.as_u64())
    else {
        return GateResult::new(GateState::Checking);
    };
    if checked_at > now + 2 || now.saturating_sub(checked_at) > GUARD_FRESH_SECONDS {
        return GateResult::new(GateState::Checking);
    }
    match status.get("ccSwitchGuard").and_then(|v| v.as_str()) {
        Some("CLEAR") => GateResult::ready(false),
        Some("TERMINATED") => GateResult::ready(true),
        Some("ERROR") => GateResult::new(GateState::GuardError),
        _ => GateResult::new(GateState::Checking),
    }
}

fn read_lease(path: &Path) -> Option<GuardLeaseRecord> {
    let metadata = fs::symlink_metadata(path).ok()?;
    if !metadata.is_file() || metadata.len() > 1024 {
        return None;
    }
    serde_json::from_slice(&fs::read(path).ok()?).ok()
}

fn write_lease(path: &Path, record: &GuardLeaseRecord) -> std::io::Result<()> {
    let parent = path
        .parent()
        .ok_or_else(|| std::io::Error::other("missing lease parent"))?;
    fs::create_dir_all(parent)?;
    let temporary = parent.join(format!(".ccswitch-guard-{}.tmp", Uuid::new_v4()));
    let result = (|| {
        let mut output = OpenOptions::new()
            .create_new(true)
            .write(true)
            .open(&temporary)?;
        serde_json::to_writer(&mut output, record)?;
        output.flush()?;
        fs::rename(&temporary, path)
    })();
    if result.is_err() {
        let _ = fs::remove_file(&temporary);
    }
    result
}

#[cfg(windows)]
fn process_created_at() -> Option<u64> {
    use windows_sys::Win32::{
        Foundation::FILETIME,
        System::Threading::{GetCurrentProcess, GetProcessTimes},
    };
    let mut created = FILETIME::default();
    let mut exited = FILETIME::default();
    let mut kernel = FILETIME::default();
    let mut user = FILETIME::default();
    if unsafe {
        GetProcessTimes(
            GetCurrentProcess(),
            &mut created,
            &mut exited,
            &mut kernel,
            &mut user,
        )
    } == 0
    {
        return None;
    }
    Some((u64::from(created.dwHighDateTime) << 32) | u64::from(created.dwLowDateTime))
}

#[cfg(not(windows))]
fn process_created_at() -> Option<u64> {
    None
}

#[cfg(windows)]
fn process_is_same(record: &GuardLeaseRecord) -> bool {
    use windows_sys::Win32::{
        Foundation::{CloseHandle, FILETIME},
        System::Threading::{GetProcessTimes, OpenProcess, PROCESS_QUERY_LIMITED_INFORMATION},
    };
    let process = unsafe { OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, 0, record.pid) };
    if process.is_null() {
        return false;
    }
    let mut created = FILETIME::default();
    let mut exited = FILETIME::default();
    let mut kernel = FILETIME::default();
    let mut user = FILETIME::default();
    let success =
        unsafe { GetProcessTimes(process, &mut created, &mut exited, &mut kernel, &mut user) } != 0;
    unsafe { CloseHandle(process) };
    success
        && ((u64::from(created.dwHighDateTime) << 32) | u64::from(created.dwLowDateTime))
            == record.created_at
}

#[cfg(not(windows))]
fn process_is_same(_record: &GuardLeaseRecord) -> bool {
    false
}

#[cfg(windows)]
fn is_elevated_admin() -> bool {
    use std::{mem::size_of, ptr};
    use windows_sys::Win32::{
        Foundation::CloseHandle,
        Security::{GetTokenInformation, TokenElevation, TOKEN_ELEVATION, TOKEN_QUERY},
        System::{
            Services::{CloseServiceHandle, OpenSCManagerW, SC_MANAGER_CREATE_SERVICE},
            Threading::{GetCurrentProcess, OpenProcessToken},
        },
    };
    let mut token = ptr::null_mut();
    if unsafe { OpenProcessToken(GetCurrentProcess(), TOKEN_QUERY, &mut token) } == 0 {
        return false;
    }
    let mut elevation = TOKEN_ELEVATION::default();
    let mut returned = 0u32;
    let elevated = unsafe {
        GetTokenInformation(
            token,
            TokenElevation,
            (&mut elevation as *mut TOKEN_ELEVATION).cast(),
            size_of::<TOKEN_ELEVATION>() as u32,
            &mut returned,
        )
    } != 0
        && elevation.TokenIsElevated != 0;
    unsafe { CloseHandle(token) };
    if !elevated {
        return false;
    }
    let manager = unsafe { OpenSCManagerW(ptr::null(), ptr::null(), SC_MANAGER_CREATE_SERVICE) };
    if manager.is_null() {
        return false;
    }
    unsafe { CloseServiceHandle(manager) };
    true
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn guard_requires_current_lease_and_fresh_positive_confirmation() {
        let status = |lease: &str, state: &str, checked_at: u64| {
            serde_json::json!({
                "ccSwitchGuardLeaseId": lease,
                "ccSwitchGuard": state,
                "ccSwitchGuardCheckedAt": checked_at,
            })
            .to_string()
        };
        assert_eq!(
            classify_guard_status(status("old", "CLEAR", 100).as_bytes(), "new", 100).state,
            GateState::Checking
        );
        assert_eq!(
            classify_guard_status(status("new", "CLEAR", 89).as_bytes(), "new", 100).state,
            GateState::Ready
        );
        assert_eq!(
            classify_guard_status(status("new", "CLEAR", 70).as_bytes(), "new", 100).state,
            GateState::Ready
        );
        assert_eq!(
            classify_guard_status(status("new", "CLEAR", 69).as_bytes(), "new", 100).state,
            GateState::Checking
        );
        assert_eq!(
            classify_guard_status(status("new", "INACTIVE", 100).as_bytes(), "new", 100).state,
            GateState::Checking
        );
        assert_eq!(
            classify_guard_status(status("new", "ERROR", 100).as_bytes(), "new", 100).state,
            GateState::GuardError
        );
        assert!(
            classify_guard_status(status("new", "CLEAR", 100).as_bytes(), "new", 100).state
                == GateState::Ready
        );
        assert!(
            classify_guard_status(status("new", "TERMINATED", 100).as_bytes(), "new", 100)
                .cc_switch_terminated
        );
        assert_eq!(
            classify_guard_status(status("old", "CLEAR", 100).as_bytes(), "new", 100)
                .settle()
                .state,
            GateState::GuardError
        );
    }

    #[test]
    fn atomic_lease_refresh_replaces_the_previous_record() {
        let directory = std::env::temp_dir().join(format!("cqg-guard-test-{}", Uuid::new_v4()));
        let path = directory.join(LEASE_FILE);
        let first = GuardLeaseRecord {
            pid: 1,
            created_at: 10,
            lease_id: Uuid::new_v4().to_string(),
        };
        write_lease(&path, &first).unwrap();
        let second = GuardLeaseRecord {
            pid: 2,
            created_at: 20,
            lease_id: Uuid::new_v4().to_string(),
        };
        write_lease(&path, &second).unwrap();
        assert_eq!(read_lease(&path).unwrap().lease_id, second.lease_id);
        fs::remove_file(&path).unwrap();
        fs::remove_dir(&directory).unwrap();
    }
}
