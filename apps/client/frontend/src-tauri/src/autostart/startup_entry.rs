use std::path::Path;

pub(super) const ENTRY_NAME: &str = "Codex Quota Guard";
pub(super) const RUN_KEY: &str = "Software\\Microsoft\\Windows\\CurrentVersion\\Run";
pub(super) const APPROVAL_KEY: &str =
    "Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\StartupApproved\\Run";
pub(super) const APPROVAL_ERROR: &str =
    "Windows 启动应用的审批数据无法识别，已保留原设置，请在任务管理器中检查。";
pub(super) const ENTRY_IDENTITY_ERROR: &str =
    "开机自启动项与当前安装目录不匹配，已阻止自动修改，请检查旧版本残留启动项。";
pub(super) const COMMAND_ERROR: &str =
    "客户端安装路径无法用于 Windows 启动应用，请安装到较短的本地路径后重试。";
pub(super) const CONCURRENT_ERROR: &str =
    "Windows 启动应用设置在操作期间发生变化，已停止修改，请重新读取后重试。";
const DISABLED_APPROVAL: [u8; 12] = [3, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0];

pub(super) fn command_for_executable(executable: &Path) -> Result<String, &'static str> {
    let text = executable.to_str().ok_or(COMMAND_ERROR)?;
    if !executable.is_absolute() || text.contains(['\0', '"']) {
        return Err(COMMAND_ERROR);
    }
    let command = format!("\"{text}\" --autostart-launch");
    // Microsoft's Run contract limits the command to 260 characters. Count the
    // UTF-16 code units Windows receives, excluding its terminating NUL.
    if command.encode_utf16().count() > 260 {
        return Err(COMMAND_ERROR);
    }
    Ok(command)
}

fn approval_enabled(data: Option<&[u8]>) -> Result<bool, &'static str> {
    let Some(data) = data else {
        return Ok(true);
    };
    if data.len() != 12 {
        return Err(APPROVAL_ERROR);
    }
    let state = u32::from_le_bytes([data[0], data[1], data[2], data[3]]);
    match state {
        0 | 2 => Ok(true),
        3 => Ok(false),
        _ => Err(APPROVAL_ERROR),
    }
}

fn command_matches(data: &[u8], expected: &str) -> bool {
    if data.len() < 2 || data.len() % 2 != 0 || data.len() > 522 {
        return false;
    }
    let mut wide: Vec<u16> = data
        .chunks_exact(2)
        .map(|pair| u16::from_le_bytes([pair[0], pair[1]]))
        .collect();
    if wide.pop() != Some(0) || wide.contains(&0) {
        return false;
    }
    String::from_utf16(&wide).is_ok_and(|command| command.eq_ignore_ascii_case(expected))
}

#[cfg(windows)]
pub(super) use registry::{EntrySnapshot, StartupEntry};

#[cfg(windows)]
mod registry {
    use super::*;
    use crate::autostart::{READ_ERROR, VERIFY_ERROR, WRITE_ERROR};
    use std::ptr;
    use windows_sys::Win32::System::Registry::{
        RegCloseKey, RegCreateKeyExW, RegDeleteValueW, RegOpenKeyExW, RegQueryValueExW,
        RegSetValueExW, HKEY, HKEY_CURRENT_USER, KEY_QUERY_VALUE, KEY_SET_VALUE, REG_BINARY,
        REG_SZ,
    };

    struct Key(HKEY);
    impl Drop for Key {
        fn drop(&mut self) {
            // SAFETY: Key owns a handle opened successfully by the registry API.
            unsafe { RegCloseKey(self.0) };
        }
    }

    #[derive(Clone, Debug, Eq, PartialEq)]
    pub(crate) struct Value {
        kind: u32,
        data: Vec<u8>,
    }

    #[derive(Clone, Eq, PartialEq)]
    pub(crate) struct EntrySnapshot {
        run: Option<Value>,
        approval: Option<Value>,
        pub(crate) enabled: Option<bool>,
    }

    pub(crate) trait RegistryStore {
        fn read(&self, path: &str) -> Result<Option<Value>, &'static str>;
        fn write(&self, path: &str, value: Option<&Value>) -> Result<(), &'static str>;
    }

    pub(crate) struct NativeRegistry;
    impl RegistryStore for NativeRegistry {
        fn read(&self, path: &str) -> Result<Option<Value>, &'static str> {
            read_value(path)
        }
        fn write(&self, path: &str, value: Option<&Value>) -> Result<(), &'static str> {
            write_value(path, value)
        }
    }

    pub(crate) struct StartupEntry<R: RegistryStore = NativeRegistry> {
        command: String,
        registry: R,
    }

    impl StartupEntry {
        pub(crate) fn new(executable: &Path) -> Result<Self, &'static str> {
            Ok(Self {
                command: command_for_executable(executable)?,
                registry: NativeRegistry,
            })
        }
    }

    impl<R: RegistryStore> StartupEntry<R> {
        pub(crate) fn snapshot(&self) -> Result<EntrySnapshot, &'static str> {
            let run = self.registry.read(RUN_KEY)?;
            let approval = self.registry.read(APPROVAL_KEY)?;
            if run.as_ref().is_some_and(|value| {
                value.kind != REG_SZ || !command_matches(&value.data, &self.command)
            }) {
                return Err(ENTRY_IDENTITY_ERROR);
            }
            if approval
                .as_ref()
                .is_some_and(|value| value.kind != REG_BINARY)
            {
                return Err(APPROVAL_ERROR);
            }
            // Validate even an orphaned approval value; absence of Run must not
            // turn unknown Windows/policy data into permission to enable startup.
            let approved = approval_enabled(approval.as_ref().map(|value| value.data.as_slice()))?;
            let enabled = run.as_ref().map(|_| approved);
            Ok(EntrySnapshot {
                run,
                approval,
                enabled,
            })
        }

        pub(crate) fn read(&self) -> Result<Option<bool>, &'static str> {
            Ok(self.snapshot()?.enabled)
        }

        pub(crate) fn registration_override(&self) -> Result<Option<bool>, &'static str> {
            let snapshot = self.snapshot()?;
            Ok(
                if snapshot.run.is_none()
                    && !approval_enabled(
                        snapshot
                            .approval
                            .as_ref()
                            .map(|value| value.data.as_slice()),
                    )?
                {
                    Some(false)
                } else {
                    None
                },
            )
        }

        pub(crate) fn ensure_matches(&self, expected: &EntrySnapshot) -> Result<(), &'static str> {
            if self.snapshot()? == *expected {
                Ok(())
            } else {
                Err(CONCURRENT_ERROR)
            }
        }

        pub(crate) fn set(
            &self,
            enabled: bool,
            expected: &mut EntrySnapshot,
        ) -> Result<(), &'static str> {
            self.ensure_matches(expected)?;
            if !enabled {
                let value = Value {
                    kind: REG_BINARY,
                    data: DISABLED_APPROVAL.to_vec(),
                };
                self.registry.write(APPROVAL_KEY, Some(&value))?;
                expected.approval = Some(value);
                expected.enabled = expected.run.as_ref().map(|_| false);
                self.ensure_matches(expected)?;
            }
            if expected.run.is_none() {
                let data = self
                    .command
                    .encode_utf16()
                    .chain(Some(0))
                    .flat_map(u16::to_le_bytes)
                    .collect();
                let value = Value { kind: REG_SZ, data };
                self.registry.write(RUN_KEY, Some(&value))?;
                expected.run = Some(value);
                expected.enabled = Some(approval_enabled(
                    expected
                        .approval
                        .as_ref()
                        .map(|value| value.data.as_slice()),
                )?);
                self.ensure_matches(expected)?;
            }
            if enabled {
                // Electron's Windows implementation enables a Run item by
                // deleting only its own approval value; Windows then owns it.
                self.registry.write(APPROVAL_KEY, None)?;
                expected.approval = None;
                expected.enabled = Some(true);
            }
            self.ensure_matches(expected)?;
            self.verify(enabled)?;
            Ok(())
        }

        pub(crate) fn verify(&self, enabled: bool) -> Result<(), &'static str> {
            if self.read()? == Some(enabled) {
                Ok(())
            } else {
                Err(VERIFY_ERROR)
            }
        }

        pub(crate) fn hold_disabled(
            &self,
            expected: &mut EntrySnapshot,
        ) -> Result<(), &'static str> {
            self.ensure_matches(expected)?;
            let value = Value {
                kind: REG_BINARY,
                data: DISABLED_APPROVAL.to_vec(),
            };
            self.registry.write(APPROVAL_KEY, Some(&value))?;
            expected.approval = Some(value);
            expected.enabled = expected.run.as_ref().map(|_| false);
            self.ensure_matches(expected)
        }

        pub(crate) fn restore_run(
            &self,
            snapshot: &EntrySnapshot,
            expected: &mut EntrySnapshot,
        ) -> Result<(), &'static str> {
            self.ensure_matches(expected)?;
            self.registry.write(RUN_KEY, snapshot.run.as_ref())?;
            expected.run = snapshot.run.clone();
            expected.enabled = expected.run.as_ref().map(|_| false);
            self.ensure_matches(expected)
        }

        pub(crate) fn remove_run(&self, expected: &mut EntrySnapshot) -> Result<(), &'static str> {
            self.ensure_matches(expected)?;
            self.registry.write(RUN_KEY, None)?;
            expected.run = None;
            expected.enabled = None;
            self.ensure_matches(expected)
        }

        pub(crate) fn restore_approval(
            &self,
            snapshot: &EntrySnapshot,
            expected: &mut EntrySnapshot,
        ) -> Result<(), &'static str> {
            self.ensure_matches(expected)?;
            self.registry
                .write(APPROVAL_KEY, snapshot.approval.as_ref())?;
            expected.approval = snapshot.approval.clone();
            expected.enabled = if expected.run.is_some() {
                Some(approval_enabled(
                    expected
                        .approval
                        .as_ref()
                        .map(|value| value.data.as_slice()),
                )?)
            } else {
                None
            };
            self.ensure_matches(expected)
        }

        pub(crate) fn remove(&self) -> Result<(), &'static str> {
            let mut expected = self.snapshot()?;
            self.registry.write(RUN_KEY, None)?;
            expected.run = None;
            expected.enabled = None;
            self.ensure_matches(&expected)?;
            self.registry.write(APPROVAL_KEY, None)?;
            expected.approval = None;
            self.ensure_matches(&expected)?;
            let snapshot = self.snapshot()?;
            if snapshot.run.is_none() && snapshot.approval.is_none() {
                Ok(())
            } else {
                Err(VERIFY_ERROR)
            }
        }
    }

    fn wide(text: &str) -> Vec<u16> {
        text.encode_utf16().chain(Some(0)).collect()
    }

    fn read_value(path: &str) -> Result<Option<Value>, &'static str> {
        let path = wide(path);
        let name = wide(ENTRY_NAME);
        let mut raw = ptr::null_mut();
        // SAFETY: Both NUL-terminated strings are fixed product locations. HKCU
        // Software is shared across WOW64 views; never read/write a second entry.
        match unsafe {
            RegOpenKeyExW(
                HKEY_CURRENT_USER,
                path.as_ptr(),
                0,
                KEY_QUERY_VALUE,
                &mut raw,
            )
        } {
            0 => {}
            2 => return Ok(None),
            _ => return Err(READ_ERROR),
        }
        let key = Key(raw);
        let mut kind = 0;
        let mut size = 0;
        // SAFETY: NULL data asks Windows for this single value's size and type.
        match unsafe {
            RegQueryValueExW(
                key.0,
                name.as_ptr(),
                ptr::null(),
                &mut kind,
                ptr::null_mut(),
                &mut size,
            )
        } {
            0 => {}
            2 => return Ok(None),
            _ => return Err(READ_ERROR),
        }
        if size > 4096 {
            return Err(if path == wide(APPROVAL_KEY) {
                APPROVAL_ERROR
            } else {
                ENTRY_IDENTITY_ERROR
            });
        }
        let mut data = vec![0u8; size as usize];
        let capacity = size;
        // SAFETY: data owns capacity bytes, and only a fixed product value is read.
        let code = unsafe {
            RegQueryValueExW(
                key.0,
                name.as_ptr(),
                ptr::null(),
                &mut kind,
                data.as_mut_ptr(),
                &mut size,
            )
        };
        if code != 0 || size > capacity {
            return Err(READ_ERROR);
        }
        data.truncate(size as usize);
        Ok(Some(Value { kind, data }))
    }

    fn write_value(path: &str, value: Option<&Value>) -> Result<(), &'static str> {
        let path = wide(path);
        let name = wide(ENTRY_NAME);
        let mut raw = ptr::null_mut();
        // SAFETY: Creates/opens a fixed HKCU key and changes only ENTRY_NAME.
        // No registry-supplied commands are expanded or executed, even on rollback.
        let code = unsafe {
            RegCreateKeyExW(
                HKEY_CURRENT_USER,
                path.as_ptr(),
                0,
                ptr::null(),
                0,
                KEY_SET_VALUE,
                ptr::null(),
                &mut raw,
                ptr::null_mut(),
            )
        };
        if code != 0 {
            return Err(WRITE_ERROR);
        }
        let key = Key(raw);
        // SAFETY: The owned bytes remain alive for this synchronous call. None
        // deletes only this application's value, never the key or other apps.
        let code = unsafe {
            match value {
                Some(value) => RegSetValueExW(
                    key.0,
                    name.as_ptr(),
                    0,
                    value.kind,
                    value.data.as_ptr(),
                    value.data.len() as u32,
                ),
                None => RegDeleteValueW(key.0, name.as_ptr()),
            }
        };
        if code == 0 || (value.is_none() && code == 2) {
            Ok(())
        } else {
            Err(WRITE_ERROR)
        }
    }

    #[cfg(test)]
    mod tests {
        use super::*;
        use std::cell::{Cell, RefCell};

        #[derive(Default)]
        struct FakeRegistry {
            run: RefCell<Option<Value>>,
            approval: RefCell<Option<Value>>,
            writes: Cell<usize>,
            fail_write: Cell<Option<usize>>,
            fail_read_after_write: Cell<Option<usize>>,
            fail_next_read: Cell<bool>,
        }

        impl RegistryStore for FakeRegistry {
            fn read(&self, path: &str) -> Result<Option<Value>, &'static str> {
                if self.fail_next_read.replace(false) {
                    return Err(READ_ERROR);
                }
                Ok(if path == RUN_KEY {
                    self.run.borrow().clone()
                } else {
                    self.approval.borrow().clone()
                })
            }
            fn write(&self, path: &str, value: Option<&Value>) -> Result<(), &'static str> {
                let count = self.writes.get() + 1;
                self.writes.set(count);
                if self.fail_write.get() == Some(count) {
                    return Err(WRITE_ERROR);
                }
                let target = if path == RUN_KEY {
                    &self.run
                } else {
                    &self.approval
                };
                target.replace(value.cloned());
                if self.fail_read_after_write.get() == Some(count) {
                    self.fail_next_read.set(true);
                }
                Ok(())
            }
        }

        fn entry() -> StartupEntry<FakeRegistry> {
            StartupEntry {
                command: r#""C:\Guard\guard.exe" --autostart-launch"#.to_owned(),
                registry: FakeRegistry::default(),
            }
        }

        fn restore(
            entry: &StartupEntry<FakeRegistry>,
            original: &EntrySnapshot,
            journal: &mut EntrySnapshot,
        ) -> Result<(), &'static str> {
            entry.hold_disabled(journal)?;
            entry.restore_run(original, journal)?;
            entry.restore_approval(original, journal)
        }

        #[test]
        fn partial_stage_write_is_journaled_and_restores_original_values() {
            let entry = entry();
            let original = entry.snapshot().unwrap();
            let mut journal = original.clone();
            entry.registry.fail_write.set(Some(2));

            assert_eq!(entry.set(false, &mut journal), Err(WRITE_ERROR));
            assert!(journal.run.is_none());
            assert_eq!(journal.approval.as_ref().unwrap().data, DISABLED_APPROVAL);
            assert!(entry.snapshot().unwrap() == journal);
            restore(&entry, &original, &mut journal).unwrap();
            assert!(entry.snapshot().unwrap() == original);
        }

        #[test]
        fn approval_delete_is_journaled_before_a_failed_verification_read() {
            let entry = entry();
            let mut journal = entry.snapshot().unwrap();
            entry.set(false, &mut journal).unwrap();
            let original = journal.clone();
            entry
                .registry
                .fail_read_after_write
                .set(Some(entry.registry.writes.get() + 1));

            assert_eq!(entry.set(true, &mut journal), Err(READ_ERROR));
            assert!(journal.approval.is_none());
            assert_eq!(journal.enabled, Some(true));
            restore(&entry, &original, &mut journal).unwrap();
            assert!(entry.snapshot().unwrap() == original);
        }

        #[test]
        fn external_windows_off_change_blocks_activation_and_rollback_overwrite() {
            let entry = entry();
            let mut journal = entry.snapshot().unwrap();
            entry.set(false, &mut journal).unwrap();
            let mut external = DISABLED_APPROVAL;
            external[4] = 42;
            entry.registry.approval.replace(Some(Value {
                kind: REG_BINARY,
                data: external.to_vec(),
            }));
            let writes = entry.registry.writes.get();

            assert_eq!(entry.set(true, &mut journal), Err(CONCURRENT_ERROR));
            assert_eq!(entry.hold_disabled(&mut journal), Err(CONCURRENT_ERROR));
            assert_eq!(entry.registry.writes.get(), writes);
            assert_eq!(
                entry.registry.approval.borrow().as_ref().unwrap().data,
                external
            );
        }

        #[test]
        fn foreign_commands_wrong_types_and_unknown_approval_never_write() {
            for (kind, command) in [(REG_SZ, "calc.exe"), (REG_BINARY, "")] {
                let entry = entry();
                let data = command
                    .encode_utf16()
                    .chain(Some(0))
                    .flat_map(u16::to_le_bytes)
                    .collect();
                entry.registry.run.replace(Some(Value { kind, data }));
                assert!(matches!(entry.snapshot(), Err(ENTRY_IDENTITY_ERROR)));
                assert_eq!(entry.registry.writes.get(), 0);
            }
            for (kind, data) in [
                (REG_SZ, DISABLED_APPROVAL.to_vec()),
                (REG_BINARY, vec![6; 12]),
                (REG_BINARY, vec![3; 11]),
            ] {
                let entry = entry();
                entry.registry.approval.replace(Some(Value { kind, data }));
                assert!(matches!(entry.snapshot(), Err(APPROVAL_ERROR)));
                assert_eq!(entry.registry.writes.get(), 0);
            }
        }

        #[test]
        fn a_known_orphan_off_record_overrides_default_but_is_not_an_installed_entry() {
            let entry = entry();
            entry.registry.approval.replace(Some(Value {
                kind: REG_BINARY,
                data: DISABLED_APPROVAL.to_vec(),
            }));
            assert_eq!(entry.read(), Ok(None));
            assert_eq!(entry.registration_override(), Ok(Some(false)));
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn bytes(text: &str) -> Vec<u8> {
        text.encode_utf16()
            .chain(Some(0))
            .flat_map(u16::to_le_bytes)
            .collect()
    }

    #[test]
    fn startup_approval_only_accepts_recognized_complete_records() {
        assert_eq!(approval_enabled(None), Ok(true));
        for state in [0u32, 2, 3] {
            let mut data = [0u8; 12];
            data[..4].copy_from_slice(&state.to_le_bytes());
            assert_eq!(approval_enabled(Some(&data)), Ok(state != 3));
        }
        for state in [1u32, 4, 6, 7, 0x10000002] {
            let mut data = [0u8; 12];
            data[..4].copy_from_slice(&state.to_le_bytes());
            assert_eq!(approval_enabled(Some(&data)), Err(APPROVAL_ERROR));
        }
        for length in [0, 4, 11, 13, 24] {
            assert_eq!(
                approval_enabled(Some(&vec![0; length])),
                Err(APPROVAL_ERROR)
            );
        }
    }

    #[test]
    fn startup_run_accepts_only_the_exact_quoted_executable_and_fixed_flag() {
        let expected = r#""C:\Program Files\Codex Quota Guard\guard.exe" --autostart-launch"#;
        assert!(command_matches(&bytes(expected), expected));
        assert!(command_matches(
            &bytes(&expected.to_ascii_uppercase()),
            expected
        ));
        for command in [
            r#"C:\Program Files\Codex Quota Guard\guard.exe --autostart-launch"#,
            r#""C:\Other\guard.exe" --autostart-launch"#,
            r#""C:\Program Files\Codex Quota Guard\guard.exe" --autostart-elevated"#,
            r#""C:\Program Files\Codex Quota Guard\guard.exe" --autostart-launch & calc.exe"#,
            r#""C:\Program Files\Codex Quota Guard\guard.exe" --autostart-launch "#,
        ] {
            assert!(!command_matches(&bytes(command), expected), "{command}");
        }
        assert!(!command_matches(
            &bytes(&format!("{expected}\0calc.exe")),
            expected
        ));
        assert!(!command_matches(&[0, 0xd8, 0, 0], expected));
        let mut missing_terminator = bytes(expected);
        missing_terminator.truncate(missing_terminator.len() - 2);
        assert!(!command_matches(&missing_terminator, expected));
    }

    #[cfg(windows)]
    #[test]
    fn startup_command_bounds_count_windows_utf16_and_reject_invalid_paths() {
        let prefix = "C:\\";
        let fixed = format!("\"{prefix}\" --autostart-launch")
            .encode_utf16()
            .count();
        let at_limit = format!("{prefix}{}", "x".repeat(260 - fixed));
        assert_eq!(
            command_for_executable(Path::new(&at_limit))
                .unwrap()
                .encode_utf16()
                .count(),
            260
        );
        assert_eq!(
            command_for_executable(Path::new(&format!("{at_limit}x"))),
            Err(COMMAND_ERROR)
        );
        let unicode_over_limit = format!("{prefix}{}", "🦀".repeat((260 - fixed) / 2 + 1));
        assert_eq!(
            command_for_executable(Path::new(&unicode_over_limit)),
            Err(COMMAND_ERROR)
        );
        for invalid in ["guard.exe", "C:\\guard\0.exe", "C:\\guard\".exe"] {
            assert_eq!(
                command_for_executable(Path::new(invalid)),
                Err(COMMAND_ERROR)
            );
        }
    }
}
