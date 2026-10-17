use crate::{
    backend::{ApiRoute, BackendError, BackendState},
    meter_service,
};
use reqwest::Method;
use serde::Serialize;
use serde_json::Value;
use std::sync::{
    atomic::{AtomicBool, Ordering},
    Arc, Mutex,
};
use tauri::{
    menu::{Menu, MenuItem, PredefinedMenuItem},
    tray::{MouseButton, MouseButtonState, TrayIconBuilder, TrayIconEvent},
    App, AppHandle, CloseRequestApi, Emitter, Manager, Runtime,
};

pub(crate) const FEEDBACK_EVENT: &str = "cqg-tray-feedback";
static METER_CHECK_LOCK: Mutex<()> = Mutex::new(());

fn with_meter_check_lock<T>(lock: &Mutex<()>, check: impl FnOnce() -> T) -> Option<T> {
    let _guard = lock.lock().ok()?;
    Some(check())
}

pub(crate) fn ensure_meter_serialized(app: &AppHandle) -> meter_service::MeterStartOutcome {
    with_meter_operation(|| meter_service::ensure_running_or_install(app))
        .unwrap_or(meter_service::MeterStartOutcome::Failed)
}

pub(crate) fn with_meter_operation<T>(operation: impl FnOnce() -> T) -> Option<T> {
    with_meter_check_lock(&METER_CHECK_LOCK, operation)
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum MenuAction {
    ShowWindow,
    HideToTray,
    ScanUsage,
    CheckBillingService,
    Quit,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) struct MenuItemSpec {
    pub(crate) id: &'static str,
    pub(crate) label: &'static str,
    pub(crate) separator_before: bool,
    pub(crate) action: MenuAction,
}

pub(crate) const TRAY_MENU_ITEMS: [MenuItemSpec; 5] = [
    MenuItemSpec {
        id: "show",
        label: "显示主窗口",
        separator_before: false,
        action: MenuAction::ShowWindow,
    },
    MenuItemSpec {
        id: "hide",
        label: "隐藏到托盘",
        separator_before: false,
        action: MenuAction::HideToTray,
    },
    MenuItemSpec {
        id: "scan",
        label: "立即扫描用量",
        separator_before: false,
        action: MenuAction::ScanUsage,
    },
    MenuItemSpec {
        id: "meter",
        label: "检查统计程序",
        separator_before: false,
        action: MenuAction::CheckBillingService,
    },
    MenuItemSpec {
        id: "quit",
        label: "退出客户端",
        separator_before: true,
        action: MenuAction::Quit,
    },
];

#[derive(Clone, Copy, Debug, Eq, PartialEq, Serialize)]
#[serde(rename_all = "snake_case")]
pub(crate) enum FeedbackAction {
    Scan,
    Meter,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq, Serialize)]
#[serde(rename_all = "snake_case")]
pub(crate) enum FeedbackOutcome {
    Success,
    Error,
    NeedsAdmin,
    SignedOut,
    Running,
    Started,
    Installed,
    NotInstalled,
    InstallFailed,
    #[cfg(not(windows))]
    Unsupported,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq, Serialize)]
pub(crate) struct TrayFeedback {
    pub(crate) action: FeedbackAction,
    pub(crate) outcome: FeedbackOutcome,
}

#[derive(Default)]
pub(crate) struct TrayState {
    available: AtomicBool,
    scan_busy: Arc<AtomicBool>,
    meter_busy: Arc<AtomicBool>,
}

impl TrayState {
    pub(crate) fn set_available(&self, available: bool) {
        self.available.store(available, Ordering::Release);
    }

    pub(crate) fn is_available(&self) -> bool {
        self.available.load(Ordering::Acquire)
    }
}

struct BusyGuard(Arc<AtomicBool>);

impl BusyGuard {
    fn try_acquire(flag: Arc<AtomicBool>) -> Option<Self> {
        flag.compare_exchange(false, true, Ordering::Acquire, Ordering::Relaxed)
            .ok()
            .map(|_| Self(flag))
    }
}

impl Drop for BusyGuard {
    fn drop(&mut self) {
        self.0.store(false, Ordering::Release);
    }
}

pub(crate) trait CloseRequestOps {
    fn prevent_close(&mut self);
    fn hide_main_window(&mut self) -> bool;
    fn exit_app(&mut self);
}

pub(crate) fn handle_close_request(available: bool, ops: &mut impl CloseRequestOps) {
    if !available {
        ops.exit_app();
        return;
    }

    ops.prevent_close();
    if !ops.hide_main_window() {
        ops.exit_app();
    }
}

pub(crate) trait MenuActionOps {
    fn show_main_window(&mut self);
    fn hide_main_window(&mut self);
    fn scan_usage(&mut self);
    fn check_billing_service(&mut self);
    fn exit_app(&mut self);
}

pub(crate) fn handle_menu_action(action: MenuAction, ops: &mut impl MenuActionOps) {
    match action {
        MenuAction::ShowWindow => ops.show_main_window(),
        MenuAction::HideToTray => ops.hide_main_window(),
        MenuAction::ScanUsage => ops.scan_usage(),
        MenuAction::CheckBillingService => ops.check_billing_service(),
        MenuAction::Quit => ops.exit_app(),
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum AppExitEvent {
    ExitRequested,
    Exit,
}

pub(crate) trait ExitEventOps {
    fn stop_backend(&mut self);
}

pub(crate) fn handle_exit_event(event: AppExitEvent, ops: &mut impl ExitEventOps) {
    match event {
        AppExitEvent::ExitRequested | AppExitEvent::Exit => ops.stop_backend(),
    }
}

fn menu_action_for_id(id: &str) -> Option<MenuAction> {
    TRAY_MENU_ITEMS
        .iter()
        .find(|item| item.id == id)
        .map(|item| item.action)
}

fn build_menu<R: Runtime>(app: &App<R>) -> Result<Menu<R>, ()> {
    let menu = Menu::new(app).map_err(|_| ())?;
    for item in TRAY_MENU_ITEMS {
        if item.separator_before {
            let separator = PredefinedMenuItem::separator(app).map_err(|_| ())?;
            menu.append(&separator).map_err(|_| ())?;
        }
        let menu_item =
            MenuItem::with_id(app, item.id, item.label, true, None::<&str>).map_err(|_| ())?;
        menu.append(&menu_item).map_err(|_| ())?;
    }
    Ok(menu)
}

pub(crate) fn install(app: &App) -> Result<(), ()> {
    let icon = app.default_window_icon().cloned().ok_or(())?;
    let menu = build_menu(app)?;
    TrayIconBuilder::with_id("cqg-main-tray")
        .icon(icon)
        .tooltip("Codex Quota Guard")
        .menu(&menu)
        .show_menu_on_left_click(false)
        .on_menu_event(|app, event| {
            if let Some(action) = menu_action_for_id(event.id().as_ref()) {
                let mut ops = TauriMenuActionOps { app: app.clone() };
                handle_menu_action(action, &mut ops);
            }
        })
        .on_tray_icon_event(|tray, event| match event {
            TrayIconEvent::Click {
                button: MouseButton::Left,
                button_state: MouseButtonState::Up,
                ..
            }
            | TrayIconEvent::DoubleClick {
                button: MouseButton::Left,
                ..
            } => {
                show_main_window(tray.app_handle());
            }
            _ => {}
        })
        .build(app)
        .map_err(|_| ())?;
    Ok(())
}

pub(crate) struct TauriCloseRequestOps<'a> {
    pub(crate) app: AppHandle,
    pub(crate) api: &'a CloseRequestApi,
}

impl CloseRequestOps for TauriCloseRequestOps<'_> {
    fn prevent_close(&mut self) {
        self.api.prevent_close();
    }

    fn hide_main_window(&mut self) -> bool {
        hide_main_window(&self.app)
    }

    fn exit_app(&mut self) {
        self.app.exit(0);
    }
}

struct TauriMenuActionOps {
    app: AppHandle,
}

impl MenuActionOps for TauriMenuActionOps {
    fn show_main_window(&mut self) {
        show_main_window(&self.app);
    }

    fn hide_main_window(&mut self) {
        let _ = hide_main_window(&self.app);
    }

    fn scan_usage(&mut self) {
        request_usage_scan(&self.app);
    }

    fn check_billing_service(&mut self) {
        request_meter_check(&self.app);
    }

    fn exit_app(&mut self) {
        self.app.exit(0);
    }
}

pub(crate) struct TauriExitEventOps {
    pub(crate) app: AppHandle,
}

impl ExitEventOps for TauriExitEventOps {
    fn stop_backend(&mut self) {
        if let Some(state) = self.app.try_state::<BackendState>() {
            state.stop();
        }
    }
}

fn show_main_window(app: &AppHandle) -> bool {
    let Some(window) = app.get_webview_window("main") else {
        return false;
    };
    let unminimized = window.unminimize().is_ok();
    let shown = window.show().is_ok();
    let focused = window.set_focus().is_ok();
    unminimized && shown && focused
}

fn hide_main_window(app: &AppHandle) -> bool {
    app.get_webview_window("main")
        .is_some_and(|window| window.hide().is_ok())
}

fn request_usage_scan(app: &AppHandle) {
    show_main_window(app);
    if app.try_state::<BackendState>().is_none() {
        emit_feedback(app, FeedbackAction::Scan, FeedbackOutcome::Error);
        return;
    }
    let Some(state) = app.try_state::<TrayState>() else {
        emit_feedback(app, FeedbackAction::Scan, FeedbackOutcome::Error);
        return;
    };
    let Some(busy) = BusyGuard::try_acquire(Arc::clone(&state.scan_busy)) else {
        return;
    };

    let app = app.clone();
    tauri::async_runtime::spawn(async move {
        let _busy = busy;
        let Some(backend) = app.try_state::<BackendState>() else {
            emit_feedback(&app, FeedbackAction::Scan, FeedbackOutcome::Error);
            return;
        };
        let result = backend.json(Method::POST, ApiRoute::UsageScan).await;
        let outcome = match result {
            Ok(response) => classify_scan_outcome(&response),
            Err(error) => classify_backend_error(&error),
        };
        emit_feedback(&app, FeedbackAction::Scan, outcome);
    });
}

fn request_meter_check(app: &AppHandle) {
    show_main_window(app);
    let Some(state) = app.try_state::<TrayState>() else {
        emit_feedback(app, FeedbackAction::Meter, FeedbackOutcome::Error);
        return;
    };
    let Some(busy) = BusyGuard::try_acquire(Arc::clone(&state.meter_busy)) else {
        return;
    };

    let app = app.clone();
    tauri::async_runtime::spawn(async move {
        let _busy = busy;
        let service_app = app.clone();
        let outcome =
            tauri::async_runtime::spawn_blocking(move || ensure_meter_serialized(&service_app))
                .await
                .unwrap_or(meter_service::MeterStartOutcome::Failed);
        emit_feedback(&app, FeedbackAction::Meter, meter_outcome(outcome));
    });
}

fn classify_backend_error(error: &BackendError) -> FeedbackOutcome {
    match error {
        BackendError::SystemLoginRequired => FeedbackOutcome::SignedOut,
        _ => FeedbackOutcome::Error,
    }
}

fn classify_scan_outcome(response: &Value) -> FeedbackOutcome {
    let fallback = response.get("fallback");
    let fallback_state = fallback
        .and_then(|value| value.get("state"))
        .and_then(Value::as_str);
    if fallback_state == Some("SIGNED_OUT") {
        return FeedbackOutcome::SignedOut;
    }

    let service = response.get("service");
    let service_state = service
        .and_then(|value| value.get("state"))
        .and_then(Value::as_str);
    let has_error_code = |status: Option<&Value>| {
        status
            .and_then(|value| value.get("errorCode"))
            .is_some_and(|value| !value.is_null())
    };

    if matches!(fallback_state, Some("REPORT_FAILED" | "SCAN_FAILED"))
        || service_state == Some("ERROR")
        || has_error_code(fallback)
        || has_error_code(service)
    {
        return FeedbackOutcome::Error;
    }

    if fallback_state == Some("SERVICE_ACTIVE") {
        return if matches!(service_state, Some("UP_TO_DATE" | "REPORTED")) {
            FeedbackOutcome::Success
        } else {
            FeedbackOutcome::Error
        };
    }

    if matches!(
        fallback_state,
        Some("WAITING_FOR_SESSIONS" | "BASELINE_READY" | "UP_TO_DATE" | "REPORTED")
    ) {
        return FeedbackOutcome::Success;
    }

    FeedbackOutcome::Error
}

fn meter_outcome(outcome: meter_service::MeterStartOutcome) -> FeedbackOutcome {
    use meter_service::MeterStartOutcome;
    match outcome {
        MeterStartOutcome::Running => FeedbackOutcome::Running,
        MeterStartOutcome::Started => FeedbackOutcome::Started,
        MeterStartOutcome::Installed => FeedbackOutcome::Installed,
        MeterStartOutcome::NotInstalled => FeedbackOutcome::NotInstalled,
        MeterStartOutcome::NeedsAdmin => FeedbackOutcome::NeedsAdmin,
        MeterStartOutcome::InstallFailed => FeedbackOutcome::InstallFailed,
        MeterStartOutcome::Failed => FeedbackOutcome::Error,
        #[cfg(not(windows))]
        MeterStartOutcome::Unsupported => FeedbackOutcome::Unsupported,
    }
}

fn emit_feedback(app: &AppHandle, action: FeedbackAction, outcome: FeedbackOutcome) {
    show_main_window(app);
    let _ = app.emit(FEEDBACK_EVENT, TrayFeedback { action, outcome });
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;
    use std::{
        panic::{catch_unwind, AssertUnwindSafe},
        sync::{atomic::AtomicUsize, Barrier, Mutex},
        thread,
        time::Duration,
    };

    #[derive(Debug, PartialEq, Eq)]
    enum Effect {
        PreventClose,
        HideWindow,
        ShowWindow,
        Scan,
        CheckMeter,
        Exit,
        StopBackend,
    }

    #[derive(Default)]
    struct FakeOps {
        effects: Vec<Effect>,
        hide_succeeds: bool,
    }

    impl CloseRequestOps for FakeOps {
        fn prevent_close(&mut self) {
            self.effects.push(Effect::PreventClose);
        }

        fn hide_main_window(&mut self) -> bool {
            self.effects.push(Effect::HideWindow);
            self.hide_succeeds
        }

        fn exit_app(&mut self) {
            self.effects.push(Effect::Exit);
        }
    }

    impl MenuActionOps for FakeOps {
        fn show_main_window(&mut self) {
            self.effects.push(Effect::ShowWindow);
        }

        fn hide_main_window(&mut self) {
            self.effects.push(Effect::HideWindow);
        }

        fn scan_usage(&mut self) {
            self.effects.push(Effect::Scan);
        }

        fn check_billing_service(&mut self) {
            self.effects.push(Effect::CheckMeter);
        }

        fn exit_app(&mut self) {
            self.effects.push(Effect::Exit);
        }
    }

    impl ExitEventOps for FakeOps {
        fn stop_backend(&mut self) {
            self.effects.push(Effect::StopBackend);
        }
    }

    #[test]
    fn closing_with_a_ready_tray_hides_the_window_without_stopping_backend() {
        let mut ops = FakeOps {
            hide_succeeds: true,
            ..FakeOps::default()
        };

        handle_close_request(true, &mut ops);

        assert_eq!(ops.effects, [Effect::PreventClose, Effect::HideWindow]);
    }

    #[test]
    fn tray_close_falls_back_to_app_exit_when_hiding_fails() {
        let mut ops = FakeOps::default();

        handle_close_request(true, &mut ops);

        assert_eq!(
            ops.effects,
            [Effect::PreventClose, Effect::HideWindow, Effect::Exit]
        );
    }

    #[test]
    fn close_exits_when_tray_initialization_failed() {
        let mut ops = FakeOps::default();

        handle_close_request(false, &mut ops);

        assert_eq!(ops.effects, [Effect::Exit]);
    }

    #[test]
    fn quit_exits_and_both_app_exit_events_stop_the_local_backend() {
        let mut requested_ops = FakeOps::default();

        handle_menu_action(MenuAction::Quit, &mut requested_ops);
        handle_exit_event(AppExitEvent::ExitRequested, &mut requested_ops);

        assert_eq!(requested_ops.effects, [Effect::Exit, Effect::StopBackend]);

        let mut exit_ops = FakeOps::default();
        handle_exit_event(AppExitEvent::Exit, &mut exit_ops);
        assert_eq!(exit_ops.effects, [Effect::StopBackend]);
    }

    #[test]
    fn menu_exposes_only_the_five_fixed_actions_with_a_separator_before_quit() {
        let actual: Vec<_> = TRAY_MENU_ITEMS
            .iter()
            .map(|item| (item.id, item.label, item.separator_before, item.action))
            .collect();

        assert_eq!(
            actual,
            [
                ("show", "显示主窗口", false, MenuAction::ShowWindow),
                ("hide", "隐藏到托盘", false, MenuAction::HideToTray),
                ("scan", "立即扫描用量", false, MenuAction::ScanUsage),
                (
                    "meter",
                    "检查统计程序",
                    false,
                    MenuAction::CheckBillingService
                ),
                ("quit", "退出客户端", true, MenuAction::Quit),
            ]
        );
        assert_eq!(menu_action_for_id("scan"), Some(MenuAction::ScanUsage));
        assert_eq!(menu_action_for_id("system-login"), None);
        assert_eq!(menu_action_for_id("https://example.invalid"), None);
    }

    #[test]
    fn tray_scan_feedback_rejects_failed_diagnostics_and_detects_signed_out() {
        assert_eq!(
            classify_scan_outcome(&json!({
                "fallback": { "state": "REPORT_FAILED", "errorCode": "SECRET_CANARY" },
                "service": { "state": "UP_TO_DATE" }
            })),
            FeedbackOutcome::Error
        );
        assert_eq!(
            classify_scan_outcome(&json!({
                "fallback": { "state": "SIGNED_OUT", "errorCode": null },
                "service": { "state": "UP_TO_DATE" }
            })),
            FeedbackOutcome::SignedOut
        );
        assert_eq!(
            classify_scan_outcome(&json!({
                "fallback": { "state": "SERVICE_ACTIVE", "errorCode": null },
                "service": { "state": "REPORTED", "errorCode": null }
            })),
            FeedbackOutcome::Success
        );
        assert_eq!(
            classify_scan_outcome(&json!({
                "fallback": { "state": "SERVICE_ACTIVE", "errorCode": null },
                "service": { "state": "OFFLINE", "errorCode": null }
            })),
            FeedbackOutcome::Error
        );
        assert_eq!(
            classify_scan_outcome(&json!({
                "fallback": { "state": "UP_TO_DATE", "errorCode": null },
                "service": { "state": "ERROR", "errorCode": null }
            })),
            FeedbackOutcome::Error
        );
        assert_eq!(
            classify_scan_outcome(&json!({
                "fallback": { "state": "UP_TO_DATE", "errorCode": "SECRET_CANARY" },
                "service": { "state": "OFFLINE", "errorCode": null }
            })),
            FeedbackOutcome::Error
        );
    }

    #[test]
    fn tray_feedback_serializes_only_the_fixed_action_and_outcome_enums() {
        let feedback = TrayFeedback {
            action: FeedbackAction::Meter,
            outcome: FeedbackOutcome::NeedsAdmin,
        };

        assert_eq!(
            serde_json::to_value(feedback).expect("feedback serializes"),
            json!({ "action": "meter", "outcome": "needs_admin" })
        );
    }

    #[test]
    fn scan_and_meter_busy_gates_prevent_duplicate_work_independently() {
        let scan_gate = Arc::new(AtomicBool::new(false));
        let meter_gate = Arc::new(AtomicBool::new(false));
        let scan = BusyGuard::try_acquire(Arc::clone(&scan_gate));

        assert!(scan.is_some());
        assert!(BusyGuard::try_acquire(Arc::clone(&scan_gate)).is_none());
        assert!(BusyGuard::try_acquire(Arc::clone(&meter_gate)).is_some());

        drop(scan);
        assert!(BusyGuard::try_acquire(scan_gate).is_some());
    }

    #[test]
    fn meter_service_diagnostics_map_to_fixed_feedback_outcomes() {
        assert_eq!(
            meter_outcome(meter_service::MeterStartOutcome::NeedsAdmin),
            FeedbackOutcome::NeedsAdmin
        );
        assert_eq!(
            meter_outcome(meter_service::MeterStartOutcome::Running),
            FeedbackOutcome::Running
        );
        assert_eq!(
            meter_outcome(meter_service::MeterStartOutcome::InstallFailed),
            FeedbackOutcome::InstallFailed
        );
    }

    #[test]
    fn concurrent_service_checks_run_one_at_a_time() {
        let lock = Arc::new(Mutex::new(()));
        let barrier = Arc::new(Barrier::new(3));
        let active = Arc::new(AtomicUsize::new(0));
        let max_active = Arc::new(AtomicUsize::new(0));
        let workers: Vec<_> = (0..2)
            .map(|_| {
                let lock = Arc::clone(&lock);
                let barrier = Arc::clone(&barrier);
                let active = Arc::clone(&active);
                let max_active = Arc::clone(&max_active);
                thread::spawn(move || {
                    barrier.wait();
                    with_meter_check_lock(&lock, || {
                        let current = active.fetch_add(1, Ordering::SeqCst) + 1;
                        max_active.fetch_max(current, Ordering::SeqCst);
                        thread::sleep(Duration::from_millis(10));
                        active.fetch_sub(1, Ordering::SeqCst);
                    })
                })
            })
            .collect();

        barrier.wait();
        for worker in workers {
            assert!(worker.join().expect("worker completes").is_some());
        }

        assert_eq!(max_active.load(Ordering::SeqCst), 1);
    }

    #[test]
    fn poisoned_service_check_lock_returns_none_without_running_work() {
        let lock = Mutex::new(());
        let panic = catch_unwind(AssertUnwindSafe(|| {
            with_meter_check_lock(&lock, || panic!("poison the local test lock"));
        }));

        assert!(panic.is_err());
        assert!(lock.is_poisoned());
        let ran = AtomicUsize::new(0);
        assert!(with_meter_check_lock(&lock, || ran.fetch_add(1, Ordering::SeqCst)).is_none());
        assert_eq!(ran.load(Ordering::SeqCst), 0);
    }
}
