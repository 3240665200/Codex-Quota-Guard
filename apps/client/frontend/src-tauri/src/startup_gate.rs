use crate::{
    backend::BackendState,
    codex_connection::{self, RestoreOutcome},
    conflict_notice::{ConflictNotices, NoticeEvent, RestoreNotice},
    meter_service, tray,
};
use serde::{Deserialize, Serialize};
use std::{
    collections::HashMap,
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
    CodexRestoreFailed,
    BackendUnavailable,
    #[cfg(not(windows))]
    Unsupported,
}

#[derive(Clone, Copy, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct GateResult {
    pub state: GateState,
    pub cc_switch_terminated: bool,
    pub codex_connection_restored: bool,
    pub codex_restore_error: Option<&'static str>,
}

impl GateResult {
    pub(crate) fn failed() -> Self {
        Self::new(GateState::GuardError)
    }

    fn new(state: GateState) -> Self {
        Self {
            state,
            cc_switch_terminated: false,
            codex_connection_restored: false,
            codex_restore_error: None,
        }
    }

    fn ready(terminated: bool) -> Self {
        Self {
            state: GateState::Ready,
            cc_switch_terminated: terminated,
            codex_connection_restored: false,
            codex_restore_error: None,
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

#[derive(Default)]
struct RestoreTracker {
    lease_id: String,
    attempts: HashMap<String, (u64, Result<RestoreOutcome, &'static str>)>,
    latest: Option<Result<RestoreOutcome, &'static str>>,
    latest_event: Option<NoticeEvent>,
}

impl RestoreTracker {
    fn restore(
        &mut self,
        lease_id: &str,
        event: Option<&NoticeEvent>,
        retry_failed: bool,
        restore: impl FnOnce() -> Result<RestoreOutcome, &'static str>,
    ) -> Result<RestoreOutcome, &'static str> {
        if self.lease_id != lease_id {
            self.lease_id = lease_id.to_owned();
            self.attempts.clear();
            self.latest = None;
            self.latest_event = None;
        }
        let effective_event = event.cloned().or_else(|| self.latest_event.clone());
        let (epoch, count) = effective_event
            .as_ref()
            .map_or(("initial", 0), |event| (event.epoch.as_str(), event.count));
        // A legacy service may clear its event metadata after TERMINATED.
        // Absence of that record is not another termination.
        let previous = if event.is_none() {
            self.latest
        } else {
            self.attempts
                .get(epoch)
                .filter(|(previous_count, _)| *previous_count >= count)
                .map(|(_, result)| *result)
        };
        if let Some(previous) = previous {
            if previous.is_ok() || !retry_failed {
                return previous;
            }
        }
        let result = restore();
        self.attempts.insert(epoch.to_owned(), (count, result));
        self.latest = Some(result);
        self.latest_event = effective_event;
        result
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
    restore: Mutex<RestoreTracker>,
}

impl Default for StartupGate {
    fn default() -> Self {
        Self {
            operation: Mutex::new(()),
            lease: Mutex::new(None),
            ready: Arc::new(AtomicBool::new(false)),
            closed: AtomicBool::new(false),
            restore: Mutex::new(RestoreTracker::default()),
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

    fn inspect_guard(&self, app: &AppHandle, lease_id: &str, retry_failed: bool) -> GateResult {
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
        let now = unix_seconds();
        let Some(status) = validated_guard_status(&bytes, lease_id, now) else {
            return GateResult::new(GateState::Checking);
        };
        let mut result = classify_guard_status(&bytes, lease_id, now);
        let mut notice = termination_notice(&status, lease_id);
        // All callers hold operation. Keep the lease stable while restoring,
        // and check shutdown both before touching files and before publishing.
        let Ok(held) = self.lease.lock() else {
            return GateResult::new(GateState::GuardError);
        };
        if self.closed.load(Ordering::Acquire)
            || held.as_ref().map_or(true, |lease| {
                lease.id != lease_id || !lease.healthy.load(Ordering::Acquire)
            })
        {
            return GateResult::new(GateState::GuardError);
        }
        if result.state == GateState::Ready {
            let Ok(mut tracker) = self.restore.lock() else {
                return GateResult::new(GateState::GuardError);
            };
            let restoration = tracker.restore(
                lease_id,
                notice.as_ref(),
                retry_failed,
                codex_connection::restore,
            );
            if notice.is_none() {
                // Legacy CLEAR records lose their termination fields. Keep
                // the original event key for notice deduplication and retries.
                notice = tracker.latest_event.clone();
            }
            let recovery = match restoration {
                Ok(RestoreOutcome::Restored) => {
                    result.codex_connection_restored = true;
                    RestoreNotice::Restored
                }
                Ok(RestoreOutcome::Unchanged) => RestoreNotice::Unchanged,
                Err(error) => {
                    result.state = GateState::CodexRestoreFailed;
                    result.codex_restore_error = Some(error);
                    RestoreNotice::Failed(error)
                }
            };
            notice
                .get_or_insert_with(|| NoticeEvent {
                    lease_id: lease_id.to_owned(),
                    epoch: "initial-restore".to_owned(),
                    count: 0,
                    last_terminated_at: None,
                    recovery: RestoreNotice::Pending,
                })
                .recovery = recovery;
        }
        if self.closed.load(Ordering::Acquire)
            || held
                .as_ref()
                .map_or(true, |lease| !lease.healthy.load(Ordering::Acquire))
        {
            return GateResult::new(GateState::GuardError);
        }
        if let (Some(notice), Some(notices)) = (notice, app.try_state::<ConflictNotices>()) {
            notices.observe(app, notice);
        }
        result
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
                let result = self.inspect_guard(app, &lease_id, true);
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
            let current_guard = self.inspect_guard(app, &lease_id, false);
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
            GateResult {
                cc_switch_terminated: guard.cc_switch_terminated
                    || current_guard.cc_switch_terminated,
                codex_connection_restored: guard.codex_connection_restored
                    || current_guard.codex_connection_restored,
                ..current_guard
            }
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
        let result = self.inspect_guard(app, &lease_id, false);
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
    let Some(status) = validated_guard_status(bytes, lease_id, now) else {
        return GateResult::new(GateState::Checking);
    };
    let mut result = match status.get("ccSwitchGuard").and_then(|v| v.as_str()) {
        Some("CLEAR") => GateResult::ready(false),
        Some("TERMINATED") if termination_notice(&status, lease_id).is_some() => {
            GateResult::ready(true)
        }
        Some("TERMINATED") => GateResult::new(GateState::GuardError),
        Some("ERROR") => GateResult::new(GateState::GuardError),
        _ => GateResult::new(GateState::Checking),
    };
    result.cc_switch_terminated |= termination_notice(&status, lease_id).is_some();
    result
}

fn validated_guard_status(bytes: &[u8], lease_id: &str, now: u64) -> Option<serde_json::Value> {
    if bytes.len() > 4096 {
        return None;
    }
    let status: serde_json::Value = serde_json::from_slice(bytes).ok()?;
    if status.get("ccSwitchGuardLeaseId").and_then(|v| v.as_str()) != Some(lease_id) {
        return None;
    }
    let checked_at = status
        .get("ccSwitchGuardCheckedAt")
        .and_then(|v| v.as_u64())?;
    if checked_at == 0
        || checked_at > now.saturating_add(2)
        || now.saturating_sub(checked_at) > GUARD_FRESH_SECONDS
    {
        return None;
    }
    Some(status)
}

fn termination_notice(status: &serde_json::Value, lease_id: &str) -> Option<NoticeEvent> {
    let state = status.get("ccSwitchGuard")?.as_str()?;
    if !matches!(state, "CLEAR" | "TERMINATED" | "ERROR" | "INACTIVE") {
        return None;
    }
    let checked_at = status.get("ccSwitchGuardCheckedAt")?.as_u64()?;
    // Best-effort compatibility for a legacy service during an upgrade. New
    // services retain their cumulative record after TERMINATED becomes CLEAR.
    if status.get("ccSwitchTerminationEpoch").is_none()
        && status.get("ccSwitchTerminationCount").is_none()
        && status.get("ccSwitchLastTerminatedAt").is_none()
    {
        return (state == "TERMINATED").then(|| NoticeEvent {
            lease_id: lease_id.to_owned(),
            // The upgraded meter uses the same deterministic key when it
            // imports this legacy record, so the upgrade does not alert twice.
            epoch: Uuid::from_u128(u128::from(checked_at)).to_string(),
            count: 1,
            last_terminated_at: Some(checked_at),
            recovery: RestoreNotice::Pending,
        });
    }
    let epoch = Uuid::parse_str(status.get("ccSwitchTerminationEpoch")?.as_str()?).ok()?;
    let count = status.get("ccSwitchTerminationCount")?.as_u64()?;
    let terminated_at = status.get("ccSwitchLastTerminatedAt")?.as_u64()?;
    if epoch.is_nil() || count == 0 || terminated_at == 0 || terminated_at > checked_at {
        return None;
    }
    Some(NoticeEvent {
        lease_id: lease_id.to_owned(),
        epoch: epoch.to_string(),
        count,
        last_terminated_at: Some(terminated_at),
        recovery: RestoreNotice::Pending,
    })
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

    fn restore_event(epoch: &str, count: u64) -> NoticeEvent {
        NoticeEvent {
            lease_id: "lease-a".to_owned(),
            epoch: epoch.to_owned(),
            count,
            last_terminated_at: Some(90),
            recovery: RestoreNotice::Pending,
        }
    }

    #[test]
    fn compatible_cc_switch_is_never_restored_without_a_confirmed_conflict() {
        let mut tracker = RestoreTracker::default();
        assert_eq!(
            tracker.restore("lease-a", None, false, || {
                panic!("ordinary startup must not modify Codex or CC Switch settings")
            }),
            Ok(RestoreOutcome::Unchanged)
        );
    }

    #[test]
    fn restores_initial_residue_once_per_lease_even_without_a_termination() {
        let mut tracker = RestoreTracker::default();
        assert_eq!(
            tracker.restore("lease-a", None, false, || Ok(RestoreOutcome::Restored)),
            Ok(RestoreOutcome::Restored)
        );
        assert_eq!(
            tracker.restore("lease-a", None, true, || panic!("already restored")),
            Ok(RestoreOutcome::Restored)
        );
        assert_eq!(
            tracker.restore("lease-b", None, false, || Ok(RestoreOutcome::Unchanged)),
            Ok(RestoreOutcome::Unchanged)
        );
    }

    #[test]
    fn new_terminations_restore_but_repeated_or_regressed_counts_do_not() {
        let mut tracker = RestoreTracker::default();
        let first = restore_event("epoch-a", 2);
        tracker
            .restore("lease-a", Some(&first), false, || {
                Ok(RestoreOutcome::Restored)
            })
            .unwrap();
        tracker
            .restore("lease-a", Some(&first), true, || panic!("duplicate event"))
            .unwrap();
        tracker
            .restore("lease-a", Some(&restore_event("epoch-a", 1)), false, || {
                panic!("regressed event")
            })
            .unwrap();
        tracker
            .restore("lease-a", Some(&restore_event("epoch-a", 3)), false, || {
                Ok(RestoreOutcome::Unchanged)
            })
            .unwrap();
        tracker
            .restore("lease-a", Some(&restore_event("epoch-b", 1)), false, || {
                Ok(RestoreOutcome::Restored)
            })
            .unwrap();
        tracker
            .restore("lease-a", Some(&first), false, || {
                panic!("previous epoch already covered")
            })
            .unwrap();
        tracker
            .restore("lease-a", None, false, || panic!("legacy event cleared"))
            .unwrap();
        assert_eq!(tracker.latest_event.as_ref().unwrap().epoch, "epoch-b");
    }

    #[test]
    fn failed_restore_is_sticky_until_explicit_retry_or_a_new_termination() {
        let mut tracker = RestoreTracker::default();
        let first = restore_event("epoch-a", 1);
        assert_eq!(
            tracker.restore("lease-a", Some(&first), false, || Err("无法写入配置。")),
            Err("无法写入配置。")
        );
        assert_eq!(
            tracker.restore("lease-a", Some(&first), false, || panic!(
                "monitor must not retry unchanged failure"
            )),
            Err("无法写入配置。")
        );
        tracker
            .restore("lease-a", Some(&first), true, || {
                Ok(RestoreOutcome::Restored)
            })
            .unwrap();
        assert_eq!(
            tracker.restore(
                "lease-a",
                Some(&restore_event("epoch-a", 2)),
                false,
                || Err("无法读取配置。")
            ),
            Err("无法读取配置。")
        );
        tracker
            .restore("lease-a", Some(&restore_event("epoch-a", 3)), false, || {
                Ok(RestoreOutcome::Restored)
            })
            .unwrap();
    }

    #[test]
    fn retry_after_legacy_clear_keeps_the_original_event_key() {
        let mut tracker = RestoreTracker::default();
        let first = restore_event("legacy-epoch", 1);
        assert!(tracker
            .restore("lease-a", Some(&first), false, || Err("无法写入配置。"))
            .is_err());
        tracker
            .restore("lease-a", None, true, || Ok(RestoreOutcome::Restored))
            .unwrap();
        assert_eq!(tracker.latest_event.as_ref(), Some(&first));
        tracker
            .restore("lease-a", Some(&first), false, || {
                panic!("retry must update original event result")
            })
            .unwrap();
    }

    #[test]
    fn restore_failure_has_a_distinct_serialized_gate_state_and_safe_detail() {
        let result = GateResult {
            state: GateState::CodexRestoreFailed,
            codex_restore_error: Some("无法读取配置。"),
            ..GateResult::ready(true)
        };
        let serialized = serde_json::to_value(result).unwrap();
        assert_eq!(serialized["state"], "CODEX_RESTORE_FAILED");
        assert_eq!(serialized["codexRestoreError"], "无法读取配置。");
        assert_eq!(serialized["codexConnectionRestored"], false);
    }

    #[test]
    fn malformed_termination_metadata_cannot_skip_a_new_restore() {
        let mut status = notification_status("TERMINATED", 1);
        status["ccSwitchTerminationEpoch"] = serde_json::json!("invalid");
        let bytes = serde_json::to_vec(&status).unwrap();
        assert_eq!(
            classify_guard_status(&bytes, "lease-current", 100).state,
            GateState::GuardError
        );
    }

    fn notification_status(state: &str, count: u64) -> serde_json::Value {
        serde_json::json!({
            "ccSwitchGuardLeaseId": "lease-current",
            "ccSwitchGuardCheckedAt": 100,
            "ccSwitchGuard": state,
            "ccSwitchTerminationEpoch": "12345678-1234-4234-8234-123456789abc",
            "ccSwitchTerminationCount": count,
            "ccSwitchLastTerminatedAt": 90,
        })
    }

    #[test]
    fn completed_terminations_remain_notifiable_after_clear_and_guard_failure() {
        for state in ["CLEAR", "ERROR", "INACTIVE", "TERMINATED"] {
            let bytes = serde_json::to_vec(&notification_status(state, 2)).unwrap();
            let status = validated_guard_status(&bytes, "lease-current", 100).unwrap();
            let notice = termination_notice(&status, "lease-current").unwrap();
            assert_eq!(notice.count, 2);
            assert_eq!(notice.last_terminated_at, Some(90));
            assert_eq!(notice.lease_id, "lease-current");
            assert!(classify_guard_status(&bytes, "lease-current", 100).cc_switch_terminated);
        }
        let bytes = serde_json::to_vec(&notification_status("ERROR", 1)).unwrap();
        assert_eq!(
            classify_guard_status(&bytes, "lease-current", 100).state,
            GateState::GuardError
        );
    }

    #[test]
    fn notices_require_the_current_fresh_lease_and_valid_termination_metadata() {
        let bytes = serde_json::to_vec(&notification_status("CLEAR", 1)).unwrap();
        assert!(validated_guard_status(&bytes, "other-lease", 100).is_none());
        assert!(validated_guard_status(&bytes, "lease-current", 131).is_none());
        assert!(validated_guard_status(&bytes, "lease-current", 97).is_none());
        assert!(validated_guard_status(&vec![b' '; 4097], "lease-current", 100).is_none());
        for (field, invalid) in [
            ("ccSwitchTerminationEpoch", serde_json::json!("invalid")),
            (
                "ccSwitchTerminationEpoch",
                serde_json::json!(Uuid::nil().to_string()),
            ),
            ("ccSwitchTerminationCount", serde_json::json!(0)),
            ("ccSwitchTerminationCount", serde_json::json!(-1)),
            ("ccSwitchLastTerminatedAt", serde_json::json!(101)),
            ("ccSwitchLastTerminatedAt", serde_json::json!(null)),
            ("ccSwitchGuard", serde_json::json!("UNRECOGNIZED")),
        ] {
            let mut status = notification_status("CLEAR", 1);
            status[field] = invalid;
            assert!(
                termination_notice(&status, "lease-current").is_none(),
                "invalid {field}"
            );
        }
    }

    #[test]
    fn legacy_termination_records_are_notified_only_when_they_report_a_termination() {
        let mut status =
            serde_json::json!({"ccSwitchGuard":"TERMINATED","ccSwitchGuardCheckedAt":100});
        let notice = termination_notice(&status, "legacy-lease").unwrap();
        assert_eq!(notice.count, 1);
        assert_eq!(notice.epoch, Uuid::from_u128(100).to_string());
        status["ccSwitchGuard"] = serde_json::json!("CLEAR");
        assert!(termination_notice(&status, "legacy-lease").is_none());
    }

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
