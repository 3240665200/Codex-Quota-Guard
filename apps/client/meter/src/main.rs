use quota_guard_meter::{prepare_server_state, MeterConfig, MeterRuntime};
use std::fs;
use std::path::{Path, PathBuf};
use std::time::Duration;

const SERVICE_NAME: &str = "CodexQuotaGuardMeter";

fn read_config(path: &Path) -> Result<MeterConfig, String> {
    let metadata = fs::metadata(path).map_err(|_| "METER_CONFIG_UNAVAILABLE")?;
    if !metadata.is_file() || metadata.len() > 8192 {
        return Err("METER_CONFIG_INVALID".into());
    }
    let bytes = fs::read(path).map_err(|_| "METER_CONFIG_UNAVAILABLE")?;
    let config: MeterConfig = serde_json::from_slice(&bytes).map_err(|_| "METER_CONFIG_INVALID")?;
    config.validate()?;
    Ok(config)
}

fn run_foreground(config_path: &Path, once: bool) -> Result<(), String> {
    let mut runtime = MeterRuntime::new(read_config(config_path)?)?;
    loop {
        let status = runtime.tick();
        if let Some(code) = runtime.fatal_error() {
            let _ = runtime.shutdown();
            return Err(code.into());
        }
        if once {
            runtime.shutdown()?;
            println!("{}", status.state);
            return if status.error_code.is_some() {
                Err("METER_TICK_FAILED".into())
            } else {
                Ok(())
            };
        }
        std::thread::sleep(Duration::from_secs(3));
    }
}

enum Command {
    SpeedProbe { home: PathBuf },
    Run {
        path: PathBuf,
        service: bool,
        once: bool,
    },
    PrepareServerState {
        path: PathBuf,
        state_dir: PathBuf,
    },
}

fn command_from_args(
    mut arguments: impl Iterator<Item = std::ffi::OsString>,
) -> Result<Command, String> {
    let mode = arguments.next().ok_or("METER_ARGUMENTS_INVALID")?;
    if mode == "speed-probe" {
        if arguments.next().as_deref()!=Some(std::ffi::OsStr::new("--codex-home")) { return Err("METER_ARGUMENTS_INVALID".into()); }
        let home=PathBuf::from(arguments.next().ok_or("METER_ARGUMENTS_INVALID")?);
        if !home.is_absolute() || arguments.next().is_some() { return Err("METER_ARGUMENTS_INVALID".into()); }
        return Ok(Command::SpeedProbe { home });
    }
    let service = mode == "service";
    let prepare = mode == "prepare-server-state";
    if !service && mode != "run" && !prepare {
        return Err("METER_ARGUMENTS_INVALID".into());
    }
    if arguments.next().as_deref() != Some(std::ffi::OsStr::new("--config")) {
        return Err("METER_ARGUMENTS_INVALID".into());
    }
    let path = arguments
        .next()
        .map(PathBuf::from)
        .ok_or("METER_ARGUMENTS_INVALID")?;
    if !path.is_absolute() {
        return Err("METER_ARGUMENTS_INVALID".into());
    }
    if prepare {
        if arguments.next().as_deref() != Some(std::ffi::OsStr::new("--state-dir")) {
            return Err("METER_ARGUMENTS_INVALID".into());
        }
        let state_dir = arguments
            .next()
            .map(PathBuf::from)
            .ok_or("METER_ARGUMENTS_INVALID")?;
        if !state_dir.is_absolute() || arguments.next().is_some() {
            return Err("METER_ARGUMENTS_INVALID".into());
        }
        return Ok(Command::PrepareServerState { path, state_dir });
    }
    let once = match arguments.next() {
        None => false,
        Some(value) if value == "--once" && !service => true,
        Some(_) => return Err("METER_ARGUMENTS_INVALID".into()),
    };
    if arguments.next().is_some() {
        return Err("METER_ARGUMENTS_INVALID".into());
    }
    Ok(Command::Run {
        path,
        service,
        once,
    })
}

fn main() {
    if std::env::args_os().skip(1).collect::<Vec<_>>() == [std::ffi::OsString::from("--version")] {
        println!("quota-guard-meter {}", env!("CARGO_PKG_VERSION"));
        return;
    }
    let outcome = (|| {
        let (config_path, service, once) = match command_from_args(std::env::args_os().skip(1))? {
            Command::SpeedProbe { home } => { println!("{}",serde_json::to_string(&quota_guard_meter::speed_detection_probe(&home)).map_err(|_|"METER_PROBE_INVALID")?);return Ok(()); }
            Command::PrepareServerState { path, state_dir } => {
                prepare_server_state(&path, &state_dir)?;
                println!("METER_SERVER_STATE_PREPARED");
                return Ok(());
            }
            Command::Run {
                path,
                service,
                once,
            } => (path, service, once),
        };
        if service {
            if once {
                return Err("METER_ARGUMENTS_INVALID".into());
            }
            #[cfg(windows)]
            {
                return windows_service_entry::run(config_path);
            }
            #[cfg(not(windows))]
            {
                return Err("METER_SERVICE_UNSUPPORTED".into());
            }
        }
        run_foreground(&config_path, once)
    })();
    if let Err(code) = outcome {
        eprintln!("{code}");
        std::process::exit(1);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::ffi::OsString;

    #[test]
    fn prepare_server_state_arguments_require_both_absolute_paths_and_no_extra_flags() {
        let config = std::env::temp_dir().join("synthetic-meter-config.json");
        let destination = std::env::temp_dir().join("synthetic-meter-context");
        let arguments = vec![
            OsString::from("prepare-server-state"),
            OsString::from("--config"),
            config.clone().into_os_string(),
            OsString::from("--state-dir"),
            destination.clone().into_os_string(),
        ];
        assert!(matches!(command_from_args(arguments.clone().into_iter()),
            Ok(Command::PrepareServerState { path, state_dir }) if path == config && state_dir == destination));
        for invalid in [
            arguments[..3].to_vec(),
            {
                let mut relative = arguments.clone();
                relative[4] = "relative-context".into();
                relative
            },
            {
                let mut extra = arguments.clone();
                extra.push("--once".into());
                extra
            },
        ] {
            assert!(
                matches!(command_from_args(invalid.into_iter()), Err(code) if code == "METER_ARGUMENTS_INVALID")
            );
        }
    }
}

#[cfg(windows)]
mod windows_service_entry {
    use super::*;
    use std::ffi::c_void;
    use std::os::windows::ffi::OsStrExt;
    use std::ptr;
    use std::sync::atomic::{AtomicBool, Ordering};
    use std::sync::OnceLock;
    use windows_sys::Win32::System::Services::{
        RegisterServiceCtrlHandlerExW, SetServiceStatus, StartServiceCtrlDispatcherW,
        SERVICE_ACCEPT_SHUTDOWN, SERVICE_ACCEPT_STOP, SERVICE_CONTROL_SHUTDOWN,
        SERVICE_CONTROL_STOP, SERVICE_RUNNING, SERVICE_START_PENDING, SERVICE_STATUS,
        SERVICE_STATUS_HANDLE, SERVICE_STOPPED, SERVICE_TABLE_ENTRYW, SERVICE_WIN32_OWN_PROCESS,
    };

    static CONFIG: OnceLock<PathBuf> = OnceLock::new();
    static STOP: AtomicBool = AtomicBool::new(false);

    pub fn run(config_path: PathBuf) -> Result<(), String> {
        CONFIG
            .set(config_path)
            .map_err(|_| "METER_SERVICE_CONFIG_INVALID")?;
        let mut name: Vec<u16> = std::ffi::OsStr::new(SERVICE_NAME)
            .encode_wide()
            .chain(std::iter::once(0))
            .collect();
        let table = [
            SERVICE_TABLE_ENTRYW {
                lpServiceName: name.as_mut_ptr(),
                lpServiceProc: Some(service_main),
            },
            SERVICE_TABLE_ENTRYW {
                lpServiceName: ptr::null_mut(),
                lpServiceProc: None,
            },
        ];
        // SAFETY: The service name and table remain alive until the dispatcher exits.
        if unsafe { StartServiceCtrlDispatcherW(table.as_ptr()) } == 0 {
            return Err("METER_SERVICE_DISPATCH_FAILED".into());
        }
        Ok(())
    }

    unsafe extern "system" fn service_main(_argc: u32, _argv: *mut *mut u16) {
        let name: Vec<u16> = std::ffi::OsStr::new(SERVICE_NAME)
            .encode_wide()
            .chain(std::iter::once(0))
            .collect();
        // SAFETY: The callback has the required system ABI and no context pointer is dereferenced.
        let handle = unsafe {
            RegisterServiceCtrlHandlerExW(name.as_ptr(), Some(control_handler), ptr::null())
        };
        if handle.is_null() {
            return;
        }
        set_status(handle, SERVICE_START_PENDING, 0, 0, 10_000);
        let result = CONFIG
            .get()
            .ok_or_else(|| "METER_SERVICE_CONFIG_INVALID".to_owned())
            .and_then(|path| read_config(path))
            .and_then(MeterRuntime::new);
        let Ok(mut runtime) = result else {
            set_status(handle, SERVICE_STOPPED, 0, 1, 0);
            return;
        };
        set_status(
            handle,
            SERVICE_RUNNING,
            SERVICE_ACCEPT_STOP | SERVICE_ACCEPT_SHUTDOWN,
            0,
            0,
        );
        while !STOP.load(Ordering::SeqCst) {
            runtime.tick();
            if runtime.fatal_error().is_some() {
                let _ = runtime.shutdown();
                set_status(handle, SERVICE_STOPPED, 0, 1, 0);
                return;
            }
            for _ in 0..30 {
                if STOP.load(Ordering::SeqCst) {
                    break;
                }
                std::thread::sleep(Duration::from_millis(100));
            }
        }
        let _ = runtime.shutdown();
        set_status(handle, SERVICE_STOPPED, 0, 0, 0);
    }

    unsafe extern "system" fn control_handler(
        control: u32,
        _event_type: u32,
        _event_data: *mut c_void,
        _context: *mut c_void,
    ) -> u32 {
        if control == SERVICE_CONTROL_STOP || control == SERVICE_CONTROL_SHUTDOWN {
            STOP.store(true, Ordering::SeqCst);
        }
        0
    }

    fn set_status(
        handle: SERVICE_STATUS_HANDLE,
        state: u32,
        accepted: u32,
        exit_code: u32,
        wait_hint: u32,
    ) {
        let status = SERVICE_STATUS {
            dwServiceType: SERVICE_WIN32_OWN_PROCESS,
            dwCurrentState: state,
            dwControlsAccepted: accepted,
            dwWin32ExitCode: exit_code,
            dwServiceSpecificExitCode: 0,
            dwCheckPoint: 0,
            dwWaitHint: wait_hint,
        };
        // SAFETY: The handle was returned by the SCM and status is fully initialized.
        unsafe { SetServiceStatus(handle, &status) };
    }
}
