// This target exercises the registration policy with a fake scheduler. It never
// registers, runs, disables, or deletes a real Windows task.
#[path = "../src/autostart.rs"]
#[allow(dead_code)] // The policy target compiles but never invokes native OS writes.
mod autostart;

use autostart::same_logon_user;
use autostart::{
    initialize_default, parse_launch_mode, read_status, set_enabled, startup_action, uninstall,
    LaunchMode, StartupAction, TaskStore,
};

#[derive(Default)]
struct FakeTasks {
    enabled: Option<bool>,
    remembered: Option<bool>,
    legacy_enabled: Option<bool>,
    writes: Vec<bool>,
    removals: usize,
    fail_read: bool,
    fail_write: bool,
    ignore_write: bool,
}

impl TaskStore for FakeTasks {
    fn read(&self) -> Result<Option<bool>, &'static str> {
        if self.fail_read {
            Err("read failed")
        } else {
            Ok(self.enabled)
        }
    }

    fn write(&mut self, enabled: bool) -> Result<(), &'static str> {
        if self.fail_write {
            return Err("write failed");
        }
        self.writes.push(enabled);
        if !self.ignore_write {
            self.enabled = Some(enabled);
        }
        Ok(())
    }

    fn remove(&mut self) -> Result<(), &'static str> {
        self.removals += 1;
        self.enabled = None;
        Ok(())
    }

    fn install_preference(&self) -> Result<Option<bool>, &'static str> {
        Ok(self.remembered)
    }

    fn read_legacy(&self) -> Result<Option<bool>, &'static str> {
        Ok(self.legacy_enabled)
    }

    fn remember(&mut self, enabled: bool) -> Result<(), &'static str> {
        self.remembered = Some(enabled);
        Ok(())
    }
}

#[test]
fn first_startup_after_same_user_admin_permission_defaults_enabled() {
    let mut tasks = FakeTasks::default();
    let state = initialize_default(&mut tasks).unwrap();
    assert!(state.supported && state.enabled);
    assert_eq!(tasks.writes, [true]);
}

#[test]
fn later_startup_observes_actual_lua_state_without_registering_again() {
    for enabled in [false, true] {
        let mut tasks = FakeTasks {
            enabled: Some(enabled),
            ..Default::default()
        };
        let state = initialize_default(&mut tasks).unwrap();
        assert_eq!(state.enabled, enabled);
        assert!(tasks.writes.is_empty());
        assert_eq!(tasks.remembered, Some(enabled));
    }
}

#[test]
fn explicit_disable_is_preserved_when_reinstalling() {
    let mut tasks = FakeTasks::default();
    assert!(!set_enabled(&mut tasks, false).unwrap().enabled);
    assert_eq!(tasks.enabled, Some(false));
    assert!(!initialize_default(&mut tasks).unwrap().enabled);
    assert_eq!(tasks.writes, [false]);
}

#[test]
fn status_observes_external_changes_instead_of_a_cached_preference() {
    let mut tasks = FakeTasks {
        enabled: Some(true),
        ..Default::default()
    };
    assert!(read_status(&tasks).unwrap().enabled);
    tasks.enabled = Some(false);
    assert!(!read_status(&tasks).unwrap().enabled);
    tasks.enabled = None;
    assert!(!read_status(&tasks).unwrap().enabled);
    assert!(tasks.writes.is_empty());
}

#[test]
fn failed_or_ineffective_registration_is_not_reported_as_success() {
    let mut tasks = FakeTasks {
        fail_write: true,
        ..Default::default()
    };
    assert!(set_enabled(&mut tasks, true).is_err());
    let mut tasks = FakeTasks {
        ignore_write: true,
        ..Default::default()
    };
    assert!(set_enabled(&mut tasks, true).is_err());
    let mut tasks = FakeTasks {
        fail_read: true,
        ..Default::default()
    };
    assert!(read_status(&tasks).is_err());
    assert!(initialize_default(&mut tasks).is_err());
    assert!(tasks.writes.is_empty());
}

#[test]
fn uninstall_removes_registration_but_update_uninstall_preserves_it() {
    let mut tasks = FakeTasks {
        enabled: Some(false),
        ..Default::default()
    };
    uninstall(&mut tasks, true).unwrap();
    assert_eq!(tasks.removals, 0);
    assert_eq!(tasks.enabled, Some(false));
    uninstall(&mut tasks, false).unwrap();
    assert_eq!(tasks.removals, 1);
    assert_eq!(tasks.enabled, None);
}

#[test]
fn uninstall_before_upgrade_removes_the_task_but_preserves_the_users_off_choice() {
    let mut tasks = FakeTasks::default();
    set_enabled(&mut tasks, false).unwrap();
    uninstall(&mut tasks, false).unwrap();
    assert_eq!(tasks.enabled, None);
    assert!(!initialize_default(&mut tasks).unwrap().enabled);
}

#[test]
fn external_task_scheduler_disable_survives_uninstall_before_upgrade() {
    let mut tasks = FakeTasks::default();
    set_enabled(&mut tasks, true).unwrap();
    // A user changed the real Windows task after previously enabling it in UI.
    tasks.enabled = Some(false);
    uninstall(&mut tasks, false).unwrap();
    assert!(!initialize_default(&mut tasks).unwrap().enabled);
}

fn args(values: &[&str]) -> Vec<String> {
    values.iter().map(|value| (*value).to_owned()).collect()
}

#[test]
fn launch_modes_only_accept_fixed_logon_elevation_and_uninstall_operations() {
    assert_eq!(parse_launch_mode(&args(&[])).unwrap(), LaunchMode::Desktop);
    assert!(parse_launch_mode(&args(&["--autostart-install"])).is_err());
    assert_eq!(
        parse_launch_mode(&args(&["--autostart-uninstall"])).unwrap(),
        LaunchMode::Uninstall
    );
    assert_eq!(
        parse_launch_mode(&args(&["--autostart-launch"])).unwrap(),
        LaunchMode::Logon
    );
    assert_eq!(
        parse_launch_mode(&args(&["--autostart-elevated"])).unwrap(),
        LaunchMode::ElevatedLogon
    );
    for invalid in [
        args(&["--autostart-install", "--command", "calc.exe"]),
        args(&["--autostart-install", "--user", "someone-else"]),
        args(&["--autostart-uninstall", "--autostart-install"]),
        args(&["--autostart-launch", "anything"]),
        args(&["--autostart-elevated", "--user", "someone-else"]),
    ] {
        assert!(parse_launch_mode(&invalid).is_err());
    }
}

#[test]
fn manual_unelevated_start_does_not_initialize_and_logon_requests_uac() {
    assert_eq!(
        startup_action(LaunchMode::Desktop, false, true).unwrap(),
        StartupAction::Desktop
    );
    assert_eq!(
        startup_action(LaunchMode::Desktop, true, true).unwrap(),
        StartupAction::Initialize
    );
    assert_eq!(
        startup_action(LaunchMode::Logon, false, true).unwrap(),
        StartupAction::Elevate
    );
    assert_eq!(
        startup_action(LaunchMode::Logon, true, true).unwrap(),
        StartupAction::Initialize
    );
    assert_eq!(
        startup_action(LaunchMode::ElevatedLogon, true, true).unwrap(),
        StartupAction::Initialize
    );
    assert!(startup_action(LaunchMode::ElevatedLogon, false, true).is_err());
    assert_eq!(
        startup_action(LaunchMode::Uninstall, false, true).unwrap(),
        StartupAction::Uninstall
    );
}

#[test]
fn another_account_never_initializes_or_launches_the_client() {
    for mode in [
        LaunchMode::Desktop,
        LaunchMode::Logon,
        LaunchMode::ElevatedLogon,
        LaunchMode::Uninstall,
    ] {
        for elevated in [false, true] {
            assert!(startup_action(mode, elevated, false).is_err());
        }
    }
}

#[test]
fn migration_preserves_the_verified_legacy_tasks_actual_off_choice() {
    let mut tasks = FakeTasks {
        legacy_enabled: Some(false),
        remembered: Some(true),
        ..Default::default()
    };
    assert!(
        !read_status(&tasks).unwrap().enabled,
        "legacy Highest is not reported as the current LUA mechanism"
    );
    assert!(!initialize_default(&mut tasks).unwrap().enabled);
    assert_eq!(tasks.writes, [false]);
    assert_eq!(tasks.remembered, Some(false));
}

#[test]
fn a_current_lua_task_wins_over_stale_legacy_preferences() {
    let mut tasks = FakeTasks {
        enabled: Some(false),
        legacy_enabled: Some(true),
        remembered: Some(true),
        ..Default::default()
    };
    assert!(!initialize_default(&mut tasks).unwrap().enabled);
    assert!(tasks.writes.is_empty());
}

#[test]
fn elevation_with_another_accounts_credentials_never_binds_that_account_to_logon() {
    assert!(same_logon_user(
        "S-1-5-21-1-2-3-1001",
        "S-1-5-21-1-2-3-1001"
    ));
    assert!(!same_logon_user(
        "S-1-5-21-1-2-3-1001",
        "S-1-5-21-1-2-3-1002"
    ));
    assert!(!same_logon_user("", ""));
}
