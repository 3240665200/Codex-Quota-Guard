// This target exercises the registration policy with a fake scheduler. It never
// registers, runs, disables, or deletes a real Windows task.
#[path = "../src/autostart.rs"]
#[allow(dead_code)] // The policy target compiles but never invokes native OS writes.
mod autostart;

use autostart::{acl_is_protected, same_logon_user, WriteGrant};
use autostart::{
    install_default, parse_launch_mode, read_status, set_enabled, uninstall, LaunchMode, TaskStore,
};

#[derive(Default)]
struct FakeTasks {
    enabled: Option<bool>,
    remembered: Option<bool>,
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

    fn remember(&mut self, enabled: bool) -> Result<(), &'static str> {
        self.remembered = Some(enabled);
        Ok(())
    }
}

#[test]
fn a_new_install_registers_enabled_and_reports_the_actual_task() {
    let mut tasks = FakeTasks::default();
    let state = install_default(&mut tasks).unwrap();
    assert!(state.supported && state.enabled);
    assert_eq!(tasks.writes, [true]);
}

#[test]
fn upgrade_refreshes_registration_without_reenabling_a_disabled_task() {
    for enabled in [false, true] {
        let mut tasks = FakeTasks {
            enabled: Some(enabled),
            ..Default::default()
        };
        let state = install_default(&mut tasks).unwrap();
        assert_eq!(state.enabled, enabled);
        assert_eq!(tasks.writes, [enabled]);
    }
}

#[test]
fn explicit_disable_is_preserved_when_reinstalling() {
    let mut tasks = FakeTasks::default();
    assert!(!set_enabled(&mut tasks, false).unwrap().enabled);
    assert_eq!(tasks.enabled, Some(false));
    assert!(!install_default(&mut tasks).unwrap().enabled);
    assert_eq!(tasks.writes, [false, false]);
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
    assert!(install_default(&mut tasks).is_err());
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
    assert!(!install_default(&mut tasks).unwrap().enabled);
}

#[test]
fn external_task_scheduler_disable_survives_uninstall_before_upgrade() {
    let mut tasks = FakeTasks::default();
    set_enabled(&mut tasks, true).unwrap();
    // A user changed the real Windows task after previously enabling it in UI.
    tasks.enabled = Some(false);
    uninstall(&mut tasks, false).unwrap();
    assert!(!install_default(&mut tasks).unwrap().enabled);
}

fn args(values: &[&str]) -> Vec<String> {
    values.iter().map(|value| (*value).to_owned()).collect()
}

#[test]
fn installer_modes_are_fixed_headless_operations_without_arbitrary_commands() {
    assert_eq!(parse_launch_mode(&args(&[])).unwrap(), LaunchMode::Desktop);
    assert_eq!(
        parse_launch_mode(&args(&["--autostart-install"])).unwrap(),
        LaunchMode::Install
    );
    assert_eq!(
        parse_launch_mode(&args(&["--autostart-uninstall"])).unwrap(),
        LaunchMode::Uninstall
    );
    assert_eq!(
        parse_launch_mode(&args(&["--autostart-launch"])).unwrap(),
        LaunchMode::Logon
    );
    for invalid in [
        args(&["--autostart-install", "--command", "calc.exe"]),
        args(&["--autostart-install", "--user", "someone-else"]),
        args(&["--autostart-uninstall", "--autostart-install"]),
        args(&["--autostart-launch", "anything"]),
    ] {
        assert!(parse_launch_mode(&invalid).is_err());
    }
}

#[test]
fn elevated_tasks_refuse_user_owned_or_user_writable_installation_objects() {
    let administrators = "S-1-5-32-544";
    let system = "S-1-5-18";
    let trusted_installer = "S-1-5-80-956008885-3418522649-1831038044-1853292631-2271478464";
    let user = "S-1-5-21-1-2-3-1001";
    let grants = [
        WriteGrant {
            sid: administrators.to_owned(),
            writable: true,
        },
        WriteGrant {
            sid: system.to_owned(),
            writable: true,
        },
        WriteGrant {
            sid: trusted_installer.to_owned(),
            writable: true,
        },
        WriteGrant {
            sid: user.to_owned(),
            writable: false,
        },
    ];
    assert!(acl_is_protected(administrators, &grants));
    assert!(acl_is_protected(trusted_installer, &grants));
    assert!(!acl_is_protected(user, &grants));
    let writable = [WriteGrant {
        sid: user.to_owned(),
        writable: true,
    }];
    assert!(!acl_is_protected(administrators, &writable));
    let everyone = [WriteGrant {
        sid: "S-1-1-0".to_owned(),
        writable: true,
    }];
    assert!(!acl_is_protected(system, &everyone));
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
