use std::sync::atomic::{AtomicBool, Ordering};
use tauri::{
    menu::{Menu, MenuItem, PredefinedMenuItem},
    tray::{MouseButton, MouseButtonState, TrayIconBuilder, TrayIconEvent},
    App, AppHandle, CloseRequestApi, Manager, Runtime,
};

#[derive(Default)]
pub(crate) struct TrayState {
    available: AtomicBool,
}

impl TrayState {
    pub(crate) fn set_available(&self, available: bool) {
        self.available.store(available, Ordering::Release);
    }

    pub(crate) fn is_available(&self) -> bool {
        self.available.load(Ordering::Acquire)
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum MenuAction {
    ShowWindow,
    HideToTray,
    Quit,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) struct MenuItemSpec {
    pub id: &'static str,
    pub label: &'static str,
    pub separator_before: bool,
    pub action: MenuAction,
}

pub(crate) const TRAY_MENU_ITEMS: [MenuItemSpec; 3] = [
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
        id: "quit",
        label: "退出客户端",
        separator_before: true,
        action: MenuAction::Quit,
    },
];

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
    fn exit_app(&mut self);
}

pub(crate) fn handle_menu_action(action: MenuAction, ops: &mut impl MenuActionOps) {
    match action {
        MenuAction::ShowWindow => ops.show_main_window(),
        MenuAction::HideToTray => ops.hide_main_window(),
        MenuAction::Quit => ops.exit_app(),
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
    pub app: AppHandle,
    pub api: &'a CloseRequestApi,
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

    fn exit_app(&mut self) {
        self.app.exit(0);
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

#[cfg(test)]
mod tests {
    use super::*;

    #[derive(Debug, PartialEq, Eq)]
    enum Effect {
        PreventClose,
        Hide,
        Show,
        Exit,
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
            self.effects.push(Effect::Hide);
            self.hide_succeeds
        }
        fn exit_app(&mut self) {
            self.effects.push(Effect::Exit);
        }
    }

    impl MenuActionOps for FakeOps {
        fn show_main_window(&mut self) {
            self.effects.push(Effect::Show);
        }
        fn hide_main_window(&mut self) {
            self.effects.push(Effect::Hide);
        }
        fn exit_app(&mut self) {
            self.effects.push(Effect::Exit);
        }
    }

    #[test]
    fn close_hides_when_the_tray_is_available_and_exits_when_it_is_not() {
        let mut hidden = FakeOps {
            hide_succeeds: true,
            ..FakeOps::default()
        };
        handle_close_request(true, &mut hidden);
        assert_eq!(hidden.effects, [Effect::PreventClose, Effect::Hide]);

        let mut exited = FakeOps::default();
        handle_close_request(false, &mut exited);
        assert_eq!(exited.effects, [Effect::Exit]);
    }

    #[test]
    fn tray_contains_only_window_and_quit_actions() {
        let actual: Vec<_> = TRAY_MENU_ITEMS
            .iter()
            .map(|item| (item.id, item.action))
            .collect();
        assert_eq!(
            actual,
            [
                ("show", MenuAction::ShowWindow),
                ("hide", MenuAction::HideToTray),
                ("quit", MenuAction::Quit),
            ]
        );
        assert_eq!(menu_action_for_id("scan"), None);
    }
}
