use std::{
    io,
    mem::size_of,
    os::windows::io::{AsRawHandle, FromRawHandle, OwnedHandle},
    process::Child,
};

use windows_sys::Win32::System::JobObjects::{
    AssignProcessToJobObject, CreateJobObjectW, JobObjectExtendedLimitInformation,
    SetInformationJobObject, JOBOBJECT_EXTENDED_LIMIT_INFORMATION,
    JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE,
};

pub(super) struct ChildJob {
    _handle: OwnedHandle,
}

impl ChildJob {
    pub(super) fn assign(child: &Child) -> io::Result<Self> {
        // A null SECURITY_ATTRIBUTES pointer creates a non-inheritable handle.
        // SAFETY: Null attributes and name are valid inputs; the return is checked below.
        let raw_handle = unsafe { CreateJobObjectW(std::ptr::null(), std::ptr::null()) };
        if raw_handle.is_null() {
            return Err(io::Error::last_os_error());
        }

        // SAFETY: CreateJobObjectW returned a valid, uniquely owned job handle.
        let handle = unsafe { OwnedHandle::from_raw_handle(raw_handle) };

        let mut limits = JOBOBJECT_EXTENDED_LIMIT_INFORMATION::default();
        limits.BasicLimitInformation.LimitFlags = JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE;
        // SAFETY: `handle` is a live job handle; `limits` is aligned and its exact size is passed.
        let updated = unsafe {
            SetInformationJobObject(
                handle.as_raw_handle(),
                JobObjectExtendedLimitInformation,
                (&limits as *const JOBOBJECT_EXTENDED_LIMIT_INFORMATION).cast(),
                size_of::<JOBOBJECT_EXTENDED_LIMIT_INFORMATION>() as u32,
            )
        };
        if updated == 0 {
            return Err(io::Error::last_os_error());
        }

        // SAFETY: Both handles remain valid for this call; `child` owns the process handle.
        let assigned =
            unsafe { AssignProcessToJobObject(handle.as_raw_handle(), child.as_raw_handle()) };
        if assigned == 0 {
            return Err(io::Error::last_os_error());
        }

        Ok(Self { _handle: handle })
    }
}

#[cfg(test)]
mod tests {
    use super::ChildJob;
    use std::{
        os::windows::process::CommandExt,
        process::{Child, Command, Stdio},
        thread,
        time::{Duration, Instant},
    };

    const CREATE_NO_WINDOW: u32 = 0x0800_0000;
    const EXIT_TIMEOUT: Duration = Duration::from_secs(5);

    struct TestChild(Child);

    impl TestChild {
        fn spawn_waiting() -> Self {
            let child = Command::new("powershell.exe")
                .args([
                    "-NoLogo",
                    "-NoProfile",
                    "-NonInteractive",
                    "-Command",
                    "Start-Sleep -Seconds 60",
                ])
                .creation_flags(CREATE_NO_WINDOW)
                .stdin(Stdio::null())
                .stdout(Stdio::null())
                .stderr(Stdio::null())
                .spawn()
                .expect("PowerShell test child should start");
            Self(child)
        }
    }

    impl Drop for TestChild {
        fn drop(&mut self) {
            if self.0.try_wait().ok().flatten().is_none() {
                let _ = self.0.kill();
            }
            let _ = self.0.wait();
        }
    }

    fn exits_within(child: &mut Child, timeout: Duration) -> bool {
        let deadline = Instant::now() + timeout;
        while Instant::now() < deadline {
            match child.try_wait() {
                Ok(Some(_)) => return true,
                Ok(None) => thread::sleep(Duration::from_millis(25)),
                Err(_) => return false,
            }
        }
        false
    }

    #[test]
    fn child_remains_alive_while_its_job_handle_is_owned() {
        let mut child = TestChild::spawn_waiting();
        let job = ChildJob::assign(&child.0).expect("test child should enter its job");

        thread::sleep(Duration::from_millis(250));
        assert!(child
            .0
            .try_wait()
            .expect("child status should be readable")
            .is_none());

        drop(job);
    }

    #[test]
    fn dropping_the_job_terminates_its_owned_child() {
        let mut child = TestChild::spawn_waiting();
        let job = ChildJob::assign(&child.0).expect("test child should enter its job");

        drop(job);

        assert!(exits_within(&mut child.0, EXIT_TIMEOUT));
    }
}
