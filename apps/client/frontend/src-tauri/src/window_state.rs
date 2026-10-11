use std::{
    fs::{self, File, OpenOptions},
    io::{Read, Write},
    path::{Path, PathBuf},
    sync::{
        atomic::{AtomicBool, Ordering},
        mpsc::{self, Receiver, Sender},
        Arc, Mutex,
    },
    thread,
    time::{Duration, Instant},
};

use serde::{Deserialize, Serialize};
use tauri::Manager;
use uuid::Uuid;

use crate::window_layout::LogicalSize;

const STATE_FILE: &str = "window-state.json";
const MAX_STATE_BYTES: u64 = 1024;
const MAX_LOGICAL_DIMENSION: u32 = 32_768;
const STARTUP_RESIZE_GUARD: Duration = Duration::from_secs(2);
const SAVE_DEBOUNCE: Duration = Duration::from_millis(350);
const FLUSH_TIMEOUT: Duration = Duration::from_secs(5);

#[derive(Debug, Deserialize, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct PersistedWindowSize {
    version: u32,
    width: u32,
    height: u32,
}

enum WriterCommand {
    Save(LogicalSize),
    Flush(Sender<()>),
}

struct InitialWindowSize {
    expected: LogicalSize,
    guard_until: Instant,
}

pub(crate) struct WindowState {
    saved_size: Option<LogicalSize>,
    writer: Option<Sender<WriterCommand>>,
    write_failed: Arc<AtomicBool>,
    last_normal_size: Mutex<Option<LogicalSize>>,
    initial_size: Mutex<Option<InitialWindowSize>>,
}

pub(crate) fn is_normal_window(
    is_minimized: bool,
    is_maximized: bool,
    is_fullscreen: bool,
) -> bool {
    !is_minimized && !is_maximized && !is_fullscreen
}

impl WindowState {
    pub(crate) fn from_app(app: &tauri::AppHandle) -> Self {
        let path = app
            .path()
            .app_config_dir()
            .ok()
            .map(|directory| directory.join(STATE_FILE));
        Self::from_path(path)
    }

    fn from_path(path: Option<PathBuf>) -> Self {
        let saved_size = path.as_deref().and_then(load_size);
        let (writer, write_failed) = path
            .and_then(start_writer)
            .unwrap_or_else(|| (None, Arc::new(AtomicBool::new(false))));
        Self {
            saved_size,
            writer,
            write_failed,
            last_normal_size: Mutex::new(None),
            initial_size: Mutex::new(None),
        }
    }

    pub(crate) fn saved_size(&self) -> Option<LogicalSize> {
        self.saved_size
    }

    pub(crate) fn set_initial_size(&self, size: LogicalSize) {
        self.set_initial_size_at(size, Instant::now());
    }

    fn set_initial_size_at(&self, size: LogicalSize, now: Instant) {
        if let Ok(mut last_size) = self.last_normal_size.lock() {
            *last_size = Some(size);
        }
        if let Ok(mut initial_size) = self.initial_size.lock() {
            *initial_size = Some(InitialWindowSize {
                expected: size,
                guard_until: now + STARTUP_RESIZE_GUARD,
            });
        }
    }

    pub(crate) fn record_size(&self, size: LogicalSize, is_normal: bool, force: bool) -> bool {
        self.record_size_at(size, is_normal, force, Instant::now())
    }

    fn record_size_at(
        &self,
        size: LogicalSize,
        is_normal: bool,
        force: bool,
        now: Instant,
    ) -> bool {
        if !is_normal || !is_valid_size(&size) {
            return false;
        }

        let Ok(mut last_normal_size) = self.last_normal_size.lock() else {
            return false;
        };

        if let Ok(mut initial_size) = self.initial_size.lock() {
            if let Some(initial) = initial_size.as_ref() {
                if size == initial.expected {
                    *initial_size = None;
                    return false;
                }
                if !force && now < initial.guard_until {
                    return false;
                }
                *initial_size = None;
            }
        }

        if *last_normal_size == Some(size) {
            if !self.write_failed.load(Ordering::Acquire) {
                return false;
            }
        } else {
            *last_normal_size = Some(size);
        }

        let sent = self
            .writer
            .as_ref()
            .is_some_and(|writer| writer.send(WriterCommand::Save(size)).is_ok());
        if !sent {
            self.write_failed.store(true, Ordering::Release);
        }
        sent
    }

    pub(crate) fn flush(&self) {
        let Some(writer) = self.writer.as_ref() else {
            return;
        };
        let (done_sender, done_receiver) = mpsc::channel();
        if writer.send(WriterCommand::Flush(done_sender)).is_err()
            || done_receiver.recv_timeout(FLUSH_TIMEOUT).is_err()
        {
            eprintln!("Timed out while saving the window size.");
        }
    }
}

fn start_writer(path: PathBuf) -> Option<(Option<Sender<WriterCommand>>, Arc<AtomicBool>)> {
    let (sender, receiver) = mpsc::channel();
    let write_failed = Arc::new(AtomicBool::new(false));
    let worker_write_failed = Arc::clone(&write_failed);
    let result = thread::Builder::new()
        .name("window-state-writer".to_owned())
        .spawn(move || write_worker(path, receiver, worker_write_failed));
    match result {
        Ok(_) => Some((Some(sender), write_failed)),
        Err(error) => {
            eprintln!("Unable to start the window-state writer: {error}");
            None
        }
    }
}

fn write_worker(path: PathBuf, receiver: Receiver<WriterCommand>, write_failed: Arc<AtomicBool>) {
    let mut pending_size = None;
    let mut save_deadline: Option<Instant> = None;
    loop {
        let command = if let Some(deadline) = save_deadline {
            let timeout = deadline.saturating_duration_since(Instant::now());
            match receiver.recv_timeout(timeout) {
                Ok(command) => Some(command),
                Err(mpsc::RecvTimeoutError::Timeout) => {
                    persist_pending_size(&path, &mut pending_size, &write_failed);
                    save_deadline = None;
                    continue;
                }
                Err(mpsc::RecvTimeoutError::Disconnected) => None,
            }
        } else {
            receiver.recv().ok()
        };

        match command {
            Some(WriterCommand::Save(size)) => {
                pending_size = Some(size);
                save_deadline = Some(Instant::now() + SAVE_DEBOUNCE);
            }
            Some(WriterCommand::Flush(done)) => {
                persist_pending_size(&path, &mut pending_size, &write_failed);
                save_deadline = None;
                let _ = done.send(());
            }
            None => {
                persist_pending_size(&path, &mut pending_size, &write_failed);
                break;
            }
        }
    }
}

fn persist_pending_size(
    path: &Path,
    pending_size: &mut Option<LogicalSize>,
    write_failed: &AtomicBool,
) {
    let Some(size) = *pending_size else {
        return;
    };
    match save_size(path, size) {
        Ok(()) => {
            *pending_size = None;
            write_failed.store(false, Ordering::Release);
        }
        Err(error) => {
            write_failed.store(true, Ordering::Release);
            eprintln!("Unable to save the window size: {error}");
        }
    }
}

fn load_size(path: &Path) -> Option<LogicalSize> {
    let file = File::open(path).ok()?;
    if file.metadata().ok()?.len() > MAX_STATE_BYTES {
        return None;
    }

    let mut bytes = Vec::new();
    file.take(MAX_STATE_BYTES + 1)
        .read_to_end(&mut bytes)
        .ok()?;
    if bytes.len() as u64 > MAX_STATE_BYTES {
        return None;
    }

    let persisted: PersistedWindowSize = serde_json::from_slice(&bytes).ok()?;
    let size = LogicalSize {
        width: persisted.width,
        height: persisted.height,
    };
    (persisted.version == 1 && is_valid_size(&size)).then_some(size)
}

fn save_size(path: &Path, size: LogicalSize) -> std::io::Result<()> {
    if !is_valid_size(&size) {
        return Err(std::io::Error::new(
            std::io::ErrorKind::InvalidInput,
            "invalid logical window size",
        ));
    }

    let parent = path
        .parent()
        .ok_or_else(|| std::io::Error::other("missing window-state parent"))?;
    fs::create_dir_all(parent)?;
    let temporary = parent.join(format!(".window-state-{}.tmp", Uuid::new_v4()));
    let persisted = PersistedWindowSize {
        version: 1,
        width: size.width,
        height: size.height,
    };
    let bytes = serde_json::to_vec(&persisted)
        .map_err(|error| std::io::Error::other(format!("serialize window size: {error}")))?;

    let result = (|| {
        let mut output = OpenOptions::new()
            .create_new(true)
            .write(true)
            .open(&temporary)?;
        output.write_all(&bytes)?;
        output.flush()?;
        output.sync_all()?;
        drop(output);
        fs::rename(&temporary, path)
    })();
    if result.is_err() {
        let _ = fs::remove_file(&temporary);
    }
    result
}

fn is_valid_size(size: &LogicalSize) -> bool {
    (1..=MAX_LOGICAL_DIMENSION).contains(&size.width)
        && (1..=MAX_LOGICAL_DIMENSION).contains(&size.height)
}

#[cfg(test)]
mod tests {
    use super::*;

    struct TestDirectory(PathBuf);

    impl TestDirectory {
        fn new() -> Self {
            let path =
                std::env::temp_dir().join(format!("pingche-window-state-{}", Uuid::new_v4()));
            fs::create_dir_all(&path).expect("temporary test directory should be created");
            Self(path)
        }

        fn file(&self) -> PathBuf {
            self.0.join(STATE_FILE)
        }
    }

    impl Drop for TestDirectory {
        fn drop(&mut self) {
            let _ = fs::remove_dir_all(&self.0);
        }
    }

    fn size(width: u32, height: u32) -> LogicalSize {
        LogicalSize { width, height }
    }

    #[test]
    fn persisted_logical_dimensions_round_trip_and_replace_an_existing_file() {
        let directory = TestDirectory::new();
        let path = directory.file();
        save_size(&path, size(1240, 860)).expect("window size should be saved");
        save_size(&path, size(1280, 900)).expect("existing state should be replaced");

        assert_eq!(load_size(&path), Some(size(1280, 900)));
    }

    #[test]
    fn malformed_oversized_or_invalid_state_falls_back_without_panicking() {
        let directory = TestDirectory::new();
        let path = directory.file();
        fs::write(&path, b"{broken").expect("test state should be written");
        assert_eq!(load_size(&path), None);

        fs::write(&path, vec![b' '; (MAX_STATE_BYTES + 1) as usize])
            .expect("oversized test state should be written");
        assert_eq!(load_size(&path), None);

        fs::write(&path, br#"{"version":1,"width":0,"height":640}"#)
            .expect("invalid dimensions should be written");
        assert_eq!(load_size(&path), None);
        assert_eq!(WindowState::from_path(Some(path)).saved_size(), None);
    }

    #[test]
    fn startup_clamp_and_initial_resize_do_not_overwrite_the_saved_logical_size() {
        let directory = TestDirectory::new();
        let path = directory.file();
        save_size(&path, size(1280, 900)).expect("saved size should be written");
        let state = WindowState::from_path(Some(path.clone()));
        let clamped_to_current_display = size(1000, 700);
        state.set_initial_size(clamped_to_current_display);

        assert!(!state.record_size(clamped_to_current_display, true, false));
        assert!(!state.record_size(clamped_to_current_display, true, true));
        state.flush();

        assert_eq!(load_size(&path), Some(size(1280, 900)));
    }

    #[test]
    fn minimized_maximized_and_fullscreen_dimensions_are_ignored() {
        let directory = TestDirectory::new();
        let path = directory.file();
        let state = WindowState::from_path(Some(path.clone()));
        state.set_initial_size(size(960, 640));

        assert!(!state.record_size(size(1920, 1080), false, true));
        assert!(!state.record_size(size(1920, 1080), false, true));
        assert!(!state.record_size(size(1920, 1080), false, true));
        state.flush();

        assert_eq!(load_size(&path), None);
    }

    #[test]
    fn excludes_each_transient_window_state() {
        assert!(!is_normal_window(true, false, false));
        assert!(!is_normal_window(false, true, false));
        assert!(!is_normal_window(false, false, true));
        assert!(is_normal_window(false, false, false));
    }

    #[test]
    fn normal_size_changes_are_written_by_the_shared_debounced_worker() {
        let directory = TestDirectory::new();
        let path = directory.file();
        let state = WindowState::from_path(Some(path.clone()));
        let start = Instant::now();
        state.set_initial_size_at(size(960, 640), start);

        assert!(state.record_size_at(size(1240, 860), true, false, start + STARTUP_RESIZE_GUARD));
        state.flush();

        assert_eq!(load_size(&path), Some(size(1240, 860)));
    }

    #[test]
    fn filesystem_failure_is_kept_for_a_later_flush_retry() {
        let directory = TestDirectory::new();
        let path = directory.0.join("is-a-directory");
        fs::create_dir_all(&path).expect("failure target should be created");

        assert!(save_size(&path, size(1240, 860)).is_err());
        let state = WindowState::from_path(Some(path.clone()));
        state.set_initial_size(size(960, 640));
        assert!(state.record_size(size(1240, 860), true, true));
        state.flush();
        assert!(path.is_dir());

        fs::remove_dir(&path).expect("failed destination directory should be removed");
        state.flush();
        assert_eq!(load_size(&path), Some(size(1240, 860)));
    }

    #[test]
    fn a_same_size_event_retries_a_failed_write_after_the_filesystem_recovers() {
        let directory = TestDirectory::new();
        let path = directory.0.join("is-a-directory");
        fs::create_dir_all(&path).expect("failure target should be created");
        let state = WindowState::from_path(Some(path.clone()));
        state.set_initial_size(size(960, 640));

        assert!(state.record_size(size(1240, 860), true, true));
        state.flush();
        fs::remove_dir(&path).expect("failed destination directory should be removed");

        assert!(state.record_size(size(1240, 860), true, false));
        state.flush();

        assert_eq!(load_size(&path), Some(size(1240, 860)));
    }
}
