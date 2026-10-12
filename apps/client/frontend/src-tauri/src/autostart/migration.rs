pub(super) const ROLLBACK_ERROR: &str =
    "开机自启动迁移未完成且恢复失败，请在任务管理器和任务计划程序中检查本软件的启动项。";
pub(super) const CLEANUP_ERROR: &str =
    "普通启动项已保存，但无法确认旧启动任务已清理，请重新启动客户端后重试。";

pub(super) trait MigrationStore {
    type Snapshot;
    fn checkpoint(&mut self) -> Result<Self::Snapshot, &'static str>;
    fn stage_disabled(&mut self) -> Result<(), &'static str>;
    fn disable_legacy(&mut self) -> Result<(), &'static str>;
    fn activate(&mut self, enabled: bool) -> Result<(), &'static str>;
    fn remove_legacy(&mut self) -> Result<(), &'static str>;
    fn verify_removed(&self) -> Result<(), &'static str>;
    fn rollback(&mut self, snapshot: Self::Snapshot) -> Result<(), &'static str>;
}

pub(super) trait RollbackStore {
    type Snapshot;
    fn hold_disabled(&mut self) -> Result<(), &'static str>;
    fn remove_run(&mut self) -> Result<(), &'static str>;
    fn restore_run(&mut self, snapshot: &Self::Snapshot) -> Result<(), &'static str>;
    fn restore_legacy(&mut self, snapshot: &Self::Snapshot) -> Result<(), &'static str>;
    fn restoring_available_legacy(snapshot: &Self::Snapshot) -> bool;
    fn restore_approval(&mut self, snapshot: &Self::Snapshot) -> Result<(), &'static str>;
}

pub(super) fn rollback<S: RollbackStore>(
    store: &mut S,
    snapshot: &S::Snapshot,
) -> Result<(), &'static str> {
    store.hold_disabled()?;
    if S::restoring_available_legacy(snapshot) {
        store.remove_run()?;
    } else {
        store.restore_run(snapshot)?;
    }
    store.restore_legacy(snapshot)?;
    store.restore_approval(snapshot)?;
    Ok(())
}

pub(super) fn migrate(store: &mut impl MigrationStore, enabled: bool) -> Result<(), &'static str> {
    let snapshot = store.checkpoint()?;
    let result = (|| {
        store.stage_disabled()?;
        store.disable_legacy()?;
        store.activate(enabled)?;
        store.remove_legacy()
    })();
    if let Err(error) = result {
        return match store.rollback(snapshot) {
            Ok(()) => Err(error),
            Err(_) => Err(ROLLBACK_ERROR),
        };
    }
    // Once deletion succeeded, do not recreate a task on an uncertain final read.
    // The ordinary entry was verified before removal and remains the valid entry.
    store.verify_removed().map_err(|_| CLEANUP_ERROR)
}

#[cfg(test)]
mod tests {
    use super::*;

    struct FakeMigration {
        entry: Option<bool>,
        legacy: Option<bool>,
        fail: Option<&'static str>,
        rollback_fails: bool,
        calls: Vec<&'static str>,
    }

    impl FakeMigration {
        fn step(&mut self, step: &'static str) -> Result<(), &'static str> {
            self.calls.push(step);
            if self.fail == Some(step) {
                Err("migration failed")
            } else {
                Ok(())
            }
        }
    }

    impl MigrationStore for FakeMigration {
        type Snapshot = (Option<bool>, Option<bool>);
        fn checkpoint(&mut self) -> Result<Self::Snapshot, &'static str> {
            self.step("checkpoint")?;
            Ok((self.entry, self.legacy))
        }
        fn stage_disabled(&mut self) -> Result<(), &'static str> {
            self.entry = Some(false);
            self.step("stage")
        }
        fn disable_legacy(&mut self) -> Result<(), &'static str> {
            if self.legacy.is_some() {
                self.legacy = Some(false);
            }
            self.step("disable")
        }
        fn activate(&mut self, enabled: bool) -> Result<(), &'static str> {
            self.entry = Some(enabled);
            self.step("activate")
        }
        fn remove_legacy(&mut self) -> Result<(), &'static str> {
            self.step("remove")?;
            self.legacy = None;
            Ok(())
        }
        fn verify_removed(&self) -> Result<(), &'static str> {
            if self.fail == Some("verify") {
                Err("read failed")
            } else {
                Ok(())
            }
        }
        fn rollback(&mut self, snapshot: Self::Snapshot) -> Result<(), &'static str> {
            self.calls.push("rollback");
            rollback(self, &snapshot)
        }
    }

    impl RollbackStore for FakeMigration {
        type Snapshot = (Option<bool>, Option<bool>);
        fn hold_disabled(&mut self) -> Result<(), &'static str> {
            self.entry = self.entry.map(|_| false);
            if self.rollback_fails {
                Err("restore failed")
            } else {
                self.step("hold")
            }
        }
        fn restore_run(&mut self, snapshot: &Self::Snapshot) -> Result<(), &'static str> {
            self.step("restore_run")?;
            self.entry = snapshot.0.map(|_| false);
            Ok(())
        }
        fn remove_run(&mut self) -> Result<(), &'static str> {
            self.step("remove_run")?;
            self.entry = None;
            Ok(())
        }
        fn restore_legacy(&mut self, snapshot: &Self::Snapshot) -> Result<(), &'static str> {
            self.step("restore_legacy")?;
            self.legacy = snapshot.1;
            Ok(())
        }
        fn restoring_available_legacy(snapshot: &Self::Snapshot) -> bool {
            snapshot.1 == Some(true)
        }
        fn restore_approval(&mut self, snapshot: &Self::Snapshot) -> Result<(), &'static str> {
            self.step("restore_approval")?;
            if snapshot.1 != Some(true) {
                self.entry = snapshot.0;
            }
            Ok(())
        }
    }

    #[test]
    fn migration_succeeds_with_one_entry_and_preserves_the_selected_state() {
        for enabled in [false, true] {
            let mut store = FakeMigration {
                entry: None,
                legacy: Some(enabled),
                fail: None,
                rollback_fails: false,
                calls: Vec::new(),
            };
            assert_eq!(migrate(&mut store, enabled), Ok(()));
            assert_eq!(store.entry, Some(enabled));
            assert_eq!(store.legacy, None);
            assert_eq!(
                store.calls,
                ["checkpoint", "stage", "disable", "activate", "remove"]
            );
        }
    }

    #[test]
    fn every_pre_delete_failure_restores_the_available_old_entry_and_its_off_choice() {
        for enabled in [false, true] {
            for failure in ["checkpoint", "stage", "disable", "activate", "remove"] {
                let mut store = FakeMigration {
                    entry: None,
                    legacy: Some(enabled),
                    fail: Some(failure),
                    rollback_fails: false,
                    calls: Vec::new(),
                };
                assert_eq!(migrate(&mut store, enabled), Err("migration failed"));
                assert_eq!(store.entry, None, "{failure}");
                assert_eq!(store.legacy, Some(enabled), "{failure}");
                assert_eq!(store.calls.contains(&"rollback"), failure != "checkpoint");
            }
        }
    }

    #[test]
    fn conflict_failure_keeps_only_one_available_entry() {
        for failure in ["stage", "disable", "activate", "remove"] {
            let mut store = FakeMigration {
                entry: Some(true),
                legacy: Some(true),
                fail: Some(failure),
                rollback_fails: false,
                calls: Vec::new(),
            };
            assert_eq!(migrate(&mut store, true), Err("migration failed"));
            assert_eq!(store.entry, None);
            assert_eq!(store.legacy, Some(true));
        }
    }

    #[test]
    fn rollback_failure_is_explicit_and_post_delete_read_failure_never_recreates_a_task() {
        let mut store = FakeMigration {
            entry: None,
            legacy: Some(true),
            fail: Some("activate"),
            rollback_fails: true,
            calls: Vec::new(),
        };
        assert_eq!(migrate(&mut store, true), Err(ROLLBACK_ERROR));
        let mut store = FakeMigration {
            entry: None,
            legacy: Some(true),
            fail: Some("verify"),
            rollback_fails: false,
            calls: Vec::new(),
        };
        assert_eq!(migrate(&mut store, true), Err(CLEANUP_ERROR));
        assert_eq!(store.entry, Some(true));
        assert_eq!(store.legacy, None);
        assert!(!store.calls.contains(&"rollback"));
    }

    #[test]
    fn each_restore_failure_stops_before_it_can_activate_two_entries() {
        for failure in ["hold", "restore_run", "restore_legacy", "restore_approval"] {
            let mut store = FakeMigration {
                entry: Some(false),
                legacy: Some(false),
                fail: Some(failure),
                rollback_fails: false,
                calls: Vec::new(),
            };
            assert_eq!(
                rollback(&mut store, &(Some(true), Some(false))),
                Err("migration failed")
            );
            assert!(
                !(store.entry == Some(true) && store.legacy == Some(true)),
                "{failure}"
            );
            let failure_index = store
                .calls
                .iter()
                .position(|call| *call == failure)
                .unwrap();
            assert_eq!(store.calls.len(), failure_index + 1, "{failure}");
        }
    }

    #[test]
    fn restored_available_legacy_never_hides_behind_a_disabled_ordinary_entry() {
        for original in [false, true] {
            let mut store = FakeMigration {
                entry: Some(false),
                legacy: Some(false),
                fail: None,
                rollback_fails: false,
                calls: Vec::new(),
            };
            assert_eq!(rollback(&mut store, &(Some(original), Some(true))), Ok(()));
            assert_eq!(store.entry, None);
            assert_eq!(store.legacy, Some(true));
            assert_eq!(
                store.calls,
                ["hold", "remove_run", "restore_legacy", "restore_approval"]
            );
        }
    }
}
