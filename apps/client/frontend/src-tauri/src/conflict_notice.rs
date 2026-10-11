use std::{
    collections::{HashMap, VecDeque},
    sync::{Arc, Condvar, Mutex, MutexGuard},
    thread,
    time::{Duration, Instant},
};

use tauri::AppHandle;

const NOTICE_TITLE: &str = "Codex Quota Guard · 冲突提醒";
const RETRY_DELAY: Duration = Duration::from_secs(1);
const MAX_HIGH_WATER_KEYS: usize = 64;

/// A confirmed guard observation. Callers must validate the active lease and
/// freshness before passing an event to `ConflictNotices`.
#[derive(Clone, Debug, Eq, PartialEq)]
pub(crate) struct NoticeEvent {
    pub(crate) lease_id: String,
    pub(crate) epoch: String,
    pub(crate) count: u64,
    pub(crate) last_terminated_at: Option<u64>,
}

#[derive(Clone, Debug, Eq, Hash, PartialEq)]
struct NoticeKey {
    lease_id: String,
    epoch: String,
}

#[derive(Clone, Debug, Eq, PartialEq)]
struct NoticeBatch {
    confirmed_processes: u64,
    last_terminated_at: Option<u64>,
}

impl NoticeBatch {
    fn from_event(event: &NoticeEvent, delta: u64) -> Self {
        Self {
            confirmed_processes: delta,
            last_terminated_at: event.last_terminated_at,
        }
    }

    fn merge(&mut self, other: Self) {
        self.confirmed_processes = self
            .confirmed_processes
            .saturating_add(other.confirmed_processes);
        self.last_terminated_at = match (self.last_terminated_at, other.last_terminated_at) {
            (Some(current), Some(incoming)) => Some(current.max(incoming)),
            (Some(current), None) => Some(current),
            (None, Some(incoming)) => Some(incoming),
            (None, None) => None,
        };
    }
}

#[derive(Default)]
struct State {
    stopped: bool,
    worker_started: bool,
    // Set under the state lock immediately before invoking the presenter.
    // This is the dispatch commit point; stop never waits for it to clear.
    in_flight: bool,
    pending: Option<NoticeBatch>,
    high_water_counts: HashMap<NoticeKey, u64>,
    high_water_order: VecDeque<NoticeKey>,
}

trait NoticePresenter: Send + Sync + 'static {
    fn present(&self, batch: &NoticeBatch) -> Result<(), ()>;
}

struct NativeNoticePresenter;

impl NoticePresenter for NativeNoticePresenter {
    fn present(&self, batch: &NoticeBatch) -> Result<(), ()> {
        present_native_notice(batch)
    }
}

struct Inner {
    state: Mutex<State>,
    changed: Condvar,
    presenter: Arc<dyn NoticePresenter>,
    retry_delay: Duration,
}

/// Serializes conflict notices through one detached worker.
#[derive(Clone)]
pub(crate) struct ConflictNotices {
    inner: Arc<Inner>,
}

impl Default for ConflictNotices {
    fn default() -> Self {
        Self::with_presenter(Arc::new(NativeNoticePresenter), RETRY_DELAY)
    }
}

impl ConflictNotices {
    /// Enqueues a validated observation without blocking the caller on UI.
    pub(crate) fn observe(&self, _app: &AppHandle, event: NoticeEvent) {
        self.observe_event(event);
    }

    /// Discards work that has not reached the in-flight commit point. This
    /// deliberately does not join or wait for an already-open `MessageBoxW`.
    pub(crate) fn stop(&self) {
        let mut state = lock_state(&self.inner.state);
        if state.stopped {
            return;
        }
        state.stopped = true;
        state.pending = None;
        state.high_water_counts.clear();
        state.high_water_order.clear();
        self.inner.changed.notify_all();
    }

    fn with_presenter(presenter: Arc<dyn NoticePresenter>, retry_delay: Duration) -> Self {
        Self {
            inner: Arc::new(Inner {
                state: Mutex::new(State::default()),
                changed: Condvar::new(),
                presenter,
                retry_delay,
            }),
        }
    }

    fn observe_event(&self, event: NoticeEvent) {
        let should_start_worker = {
            let mut state = lock_state(&self.inner.state);
            if state.stopped {
                return;
            }

            if event.count > 0 {
                let key = NoticeKey {
                    lease_id: event.lease_id.clone(),
                    epoch: event.epoch.clone(),
                };
                let previous = touch_high_water(&mut state, key, event.count);
                if event.count > previous {
                    let incoming = NoticeBatch::from_event(&event, event.count - previous);
                    match state.pending.as_mut() {
                        Some(pending) => pending.merge(incoming),
                        None => state.pending = Some(incoming),
                    }
                    self.inner.changed.notify_one();
                }
            }

            if state.pending.is_some() && !state.worker_started {
                state.worker_started = true;
                true
            } else {
                false
            }
        };

        if should_start_worker {
            self.start_worker();
        }
    }

    fn start_worker(&self) {
        let inner = Arc::clone(&self.inner);
        let spawn_result = thread::Builder::new()
            .name("quota-guard-conflict-notice".to_string())
            .spawn(move || run_worker(inner));

        if spawn_result.is_err() {
            eprintln!(
                "Codex Quota Guard: unable to start the conflict notice worker; notification remains pending."
            );
            let mut state = lock_state(&self.inner.state);
            state.worker_started = false;
            self.inner.changed.notify_all();
        }
    }

    #[cfg(test)]
    fn wait_for_worker_stop(&self, timeout: Duration) -> bool {
        let deadline = Instant::now() + timeout;
        let mut state = lock_state(&self.inner.state);
        while state.worker_started {
            let remaining = deadline.saturating_duration_since(Instant::now());
            if remaining.is_zero() {
                return false;
            }
            let result = self.inner.changed.wait_timeout(state, remaining);
            let (next_state, wait_result) = result.unwrap_or_else(|poisoned| poisoned.into_inner());
            state = next_state;
            if wait_result.timed_out() && state.worker_started {
                return false;
            }
        }
        true
    }
}

fn run_worker(inner: Arc<Inner>) {
    let mut failure_reported = false;
    loop {
        let batch = {
            let mut state = lock_state(&inner.state);
            loop {
                if state.stopped {
                    finish_worker(&inner, &mut state);
                    return;
                }
                if let Some(batch) = state.pending.take() {
                    break batch;
                }
                state = inner
                    .changed
                    .wait(state)
                    .unwrap_or_else(|poisoned| poisoned.into_inner());
            }
        };

        // This second check is the dispatch commit point. `stop` can discard a
        // dequeued batch before this point; once in-flight is committed, stop
        // returns immediately and lets the current presenter call finish.
        {
            let mut state = lock_state(&inner.state);
            if state.stopped {
                finish_worker(&inner, &mut state);
                return;
            }
            debug_assert!(!state.in_flight);
            state.in_flight = true;
        }

        let presentation = inner.presenter.present(&batch);
        let mut state = lock_state(&inner.state);
        state.in_flight = false;
        inner.changed.notify_all();
        if state.stopped {
            finish_worker(&inner, &mut state);
            return;
        }

        match presentation {
            Ok(()) => failure_reported = false,
            Err(()) => {
                if !failure_reported {
                    eprintln!(
                        "Codex Quota Guard: the conflict notice could not be displayed; notification will be retried."
                    );
                    failure_reported = true;
                }

                match state.pending.as_mut() {
                    Some(pending) => pending.merge(batch),
                    None => state.pending = Some(batch),
                }

                state = wait_before_retry(&inner, state);
                if state.stopped {
                    finish_worker(&inner, &mut state);
                    return;
                }
            }
        }
    }
}

fn wait_before_retry<'a>(inner: &Inner, mut state: MutexGuard<'a, State>) -> MutexGuard<'a, State> {
    let deadline = Instant::now() + inner.retry_delay;
    loop {
        if state.stopped {
            return state;
        }
        let remaining = deadline.saturating_duration_since(Instant::now());
        if remaining.is_zero() {
            return state;
        }
        let result = inner.changed.wait_timeout(state, remaining);
        let (next_state, wait_result) = result.unwrap_or_else(|poisoned| poisoned.into_inner());
        state = next_state;
        if wait_result.timed_out() {
            return state;
        }
    }
}

fn finish_worker(inner: &Inner, state: &mut State) {
    state.worker_started = false;
    state.in_flight = false;
    inner.changed.notify_all();
}

fn touch_high_water(state: &mut State, key: NoticeKey, count: u64) -> u64 {
    let previous = state.high_water_counts.get(&key).copied().unwrap_or(0);
    if let Some(index) = state
        .high_water_order
        .iter()
        .position(|candidate| candidate == &key)
    {
        state.high_water_order.remove(index);
    } else if state.high_water_counts.len() >= MAX_HIGH_WATER_KEYS {
        if let Some(expired) = state.high_water_order.pop_front() {
            state.high_water_counts.remove(&expired);
        }
    }
    state
        .high_water_counts
        .insert(key.clone(), previous.max(count));
    state.high_water_order.push_back(key);
    previous
}

fn lock_state(state: &Mutex<State>) -> MutexGuard<'_, State> {
    state
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner())
}

#[cfg(windows)]
fn present_native_notice(batch: &NoticeBatch) -> Result<(), ()> {
    use windows_sys::Win32::UI::WindowsAndMessaging::{
        MessageBoxW, MB_ICONWARNING, MB_OK, MB_SETFOREGROUND, MB_TOPMOST,
    };

    let text = notice_text(batch);
    let text: Vec<u16> = text.encode_utf16().chain(std::iter::once(0)).collect();
    let title: Vec<u16> = NOTICE_TITLE
        .encode_utf16()
        .chain(std::iter::once(0))
        .collect();
    let style = MB_OK | MB_ICONWARNING | MB_SETFOREGROUND | MB_TOPMOST;

    // SAFETY: `text` and `title` are NUL-terminated UTF-16 buffers that stay
    // alive for the synchronous call. A null owner keeps this notice usable
    // while the Tauri window is hidden in the tray.
    let result = unsafe { MessageBoxW(std::ptr::null_mut(), text.as_ptr(), title.as_ptr(), style) };

    if result == 0 {
        Err(())
    } else {
        Ok(())
    }
}

fn notice_text(batch: &NoticeBatch) -> String {
    format!(
        "已强制结束 CC Switch（本次共 {} 个进程）。\r\n\r\n\
         原因：CC Switch 会影响 Codex 请求路由，可能导致用量归属到错误的账号或统计范围。\r\n\r\n\
         CC Switch 中未保存的修改可能丢失。\r\n\r\n\
         请勿在 Quota Guard 运行期间重新打开 CC Switch；需要使用它或恢复官方直连配置时，请先从系统托盘退出 Quota Guard，再检查 Codex 连接配置。",
        batch.confirmed_processes
    )
}

#[cfg(not(windows))]
fn present_native_notice(_batch: &NoticeBatch) -> Result<(), ()> {
    // The startup guard is unsupported off Windows; keep unexpected notices
    // pending and diagnosable instead of reporting them as shown.
    Err(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::{
        sync::mpsc::{self, Receiver, Sender},
        thread,
    };

    struct MockPresenter {
        calls: Sender<NoticeBatch>,
        results: Mutex<Receiver<Result<(), ()>>>,
    }

    impl NoticePresenter for MockPresenter {
        fn present(&self, batch: &NoticeBatch) -> Result<(), ()> {
            self.calls.send(batch.clone()).map_err(|_| ())?;
            self.results
                .lock()
                .unwrap_or_else(|poisoned| poisoned.into_inner())
                .recv()
                .unwrap_or(Err(()))
        }
    }

    fn fixture(
        retry_delay: Duration,
    ) -> (
        ConflictNotices,
        Receiver<NoticeBatch>,
        Sender<Result<(), ()>>,
    ) {
        let (calls_tx, calls_rx) = mpsc::channel();
        let (results_tx, results_rx) = mpsc::channel();
        let presenter = MockPresenter {
            calls: calls_tx,
            results: Mutex::new(results_rx),
        };
        (
            ConflictNotices::with_presenter(Arc::new(presenter), retry_delay),
            calls_rx,
            results_tx,
        )
    }

    fn event(
        lease_id: &str,
        epoch: &str,
        count: u64,
        last_terminated_at: Option<u64>,
    ) -> NoticeEvent {
        NoticeEvent {
            lease_id: lease_id.to_string(),
            epoch: epoch.to_string(),
            count,
            last_terminated_at,
        }
    }

    fn receive_call(calls: &Receiver<NoticeBatch>) -> NoticeBatch {
        calls
            .recv_timeout(Duration::from_secs(3))
            .expect("notice worker should call the mock presenter")
    }

    #[test]
    fn concurrent_duplicate_observations_are_presented_once() {
        let (notices, calls, results) = fixture(Duration::from_millis(10));
        let mut handles = Vec::new();
        for _ in 0..16 {
            let notices = notices.clone();
            handles.push(thread::spawn(move || {
                notices.observe_event(event("lease-a", "epoch-a", 1, Some(10)));
            }));
        }
        for handle in handles {
            handle.join().expect("observer thread should finish");
        }

        assert_eq!(
            receive_call(&calls),
            NoticeBatch {
                confirmed_processes: 1,
                last_terminated_at: Some(10),
            }
        );
        {
            let state = lock_state(&notices.inner.state);
            assert_eq!(state.high_water_counts.len(), 1);
            assert_eq!(state.pending, None);
        }
        assert!(matches!(calls.try_recv(), Err(mpsc::TryRecvError::Empty)));

        results.send(Ok(())).expect("release mock presenter");
        notices.stop();
        assert!(notices.wait_for_worker_stop(Duration::from_secs(3)));
    }

    #[test]
    fn count_increases_and_new_epochs_merge_into_one_pending_notice() {
        let (notices, calls, results) = fixture(Duration::from_millis(10));
        notices.observe_event(event("lease-a", "epoch-a", 1, Some(10)));
        assert_eq!(receive_call(&calls).confirmed_processes, 1);

        notices.observe_event(event("lease-a", "epoch-a", 2, Some(11)));
        notices.observe_event(event("lease-a", "epoch-a", 3, Some(12)));
        notices.observe_event(event("lease-a", "epoch-a", 3, Some(12)));
        notices.observe_event(event("lease-a", "epoch-b", 1, Some(19)));
        notices.observe_event(event("lease-a", "epoch-b", 2, Some(20)));

        {
            let state = lock_state(&notices.inner.state);
            assert_eq!(
                state.pending,
                Some(NoticeBatch {
                    confirmed_processes: 4,
                    last_terminated_at: Some(20),
                })
            );
        }

        results.send(Ok(())).expect("release first mock presenter");
        assert_eq!(
            receive_call(&calls),
            NoticeBatch {
                confirmed_processes: 4,
                last_terminated_at: Some(20),
            }
        );
        results.send(Ok(())).expect("release second mock presenter");
        notices.stop();
        assert!(notices.wait_for_worker_stop(Duration::from_secs(3)));
    }

    #[test]
    fn high_water_cache_is_bounded_while_pending_process_counts_are_merged() {
        let (notices, calls, results) = fixture(Duration::from_millis(10));
        notices.observe_event(event("lease-a", "epoch-0", 1, Some(10)));
        assert_eq!(receive_call(&calls).confirmed_processes, 1);

        for generation in 1..=65 {
            notices.observe_event(event(
                "lease-a",
                &format!("epoch-{generation}"),
                1,
                Some(10 + generation),
            ));
        }
        notices.observe_event(event("lease-a", "epoch-65", 1, Some(75)));

        {
            let state = lock_state(&notices.inner.state);
            assert_eq!(state.high_water_counts.len(), MAX_HIGH_WATER_KEYS);
            assert_eq!(state.high_water_order.len(), MAX_HIGH_WATER_KEYS);
            assert_eq!(
                state.pending,
                Some(NoticeBatch {
                    confirmed_processes: 65,
                    last_terminated_at: Some(75),
                })
            );
        }

        results.send(Ok(())).expect("release first mock presenter");
        assert_eq!(
            receive_call(&calls),
            NoticeBatch {
                confirmed_processes: 65,
                last_terminated_at: Some(75),
            }
        );
        results.send(Ok(())).expect("release merged mock presenter");
        notices.stop();
        assert!(notices.wait_for_worker_stop(Duration::from_secs(3)));
    }

    #[test]
    fn notice_text_explains_manual_recovery_without_promising_auto_restore() {
        let text = notice_text(&NoticeBatch {
            confirmed_processes: 2,
            last_terminated_at: Some(10),
        });

        assert!(text.contains("已强制结束 CC Switch（本次共 2 个进程）。"));
        assert!(text.contains("CC Switch 中未保存的修改可能丢失。"));
        assert!(text.contains("请勿在 Quota Guard 运行期间重新打开 CC Switch"));
        assert!(text.contains("先从系统托盘退出 Quota Guard，再检查 Codex 连接配置。"));
        assert!(!text.contains("即可恢复直连"));
        assert!(!text.contains("官方客户端"));
    }

    #[test]
    fn failed_presentation_keeps_the_notice_for_retry() {
        let (notices, calls, results) = fixture(Duration::from_millis(20));
        notices.observe_event(event("lease-a", "epoch-a", 1, Some(10)));
        let first = receive_call(&calls);
        notices.observe_event(event("lease-a", "epoch-a", 1, Some(10)));
        results.send(Err(())).expect("fail first presentation");

        assert_eq!(receive_call(&calls), first);
        results.send(Ok(())).expect("succeed on retry");
        notices.stop();
        assert!(notices.wait_for_worker_stop(Duration::from_secs(3)));
    }

    #[test]
    fn stop_discards_pending_notice_without_waiting_for_open_dialog() {
        let (notices, calls, results) = fixture(Duration::from_millis(10));
        notices.observe_event(event("lease-a", "epoch-a", 1, Some(10)));
        assert_eq!(receive_call(&calls).confirmed_processes, 1);
        notices.observe_event(event("lease-a", "epoch-a", 2, Some(11)));

        notices.stop();
        assert!(lock_state(&notices.inner.state).in_flight);
        results.send(Ok(())).expect("release open mock presenter");
        assert!(notices.wait_for_worker_stop(Duration::from_secs(3)));
        assert!(matches!(calls.try_recv(), Err(mpsc::TryRecvError::Empty)));
        assert_eq!(lock_state(&notices.inner.state).pending, None);

        notices.observe_event(event("lease-a", "epoch-a", 3, Some(12)));
        assert!(matches!(calls.try_recv(), Err(mpsc::TryRecvError::Empty)));
    }
}
