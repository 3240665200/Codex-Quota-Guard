//! The renderer never receives the session-encryption key. Windows DPAPI protects
//! its durable copy for the current OS user; only our owned Java child gets it.
use std::{path::Path, process::Command};

pub(crate) fn configure(command: &mut Command, app_data: &Path) {
    // Never accept inherited overrides for a different profile or plaintext key.
    command
        .env_remove("QUOTA_GUARD_SESSION_STORAGE_KEY")
        .env_remove("QUOTA_GUARD_SESSION_STORAGE_DIR")
        .env_remove("QUOTA_GUARD_SESSION_STORAGE_REQUIRED");
    #[cfg(windows)]
    {
        let directory = app_data.join("secure-session");
        command
            .env("QUOTA_GUARD_SESSION_STORAGE_DIR", &directory)
            .env("QUOTA_GUARD_SESSION_STORAGE_REQUIRED", "true");
        match load_or_create_key(&directory) {
            Ok(key) => {
                command.env("QUOTA_GUARD_SESSION_STORAGE_KEY", key.hex());
            }
            // Keep Java available to report a fixed storage error, but never
            // fall back to plaintext or claim that an unsaved login is remembered.
            Err(_) => {
                command.env("QUOTA_GUARD_SESSION_STORAGE_KEY", "");
            }
        }
    }
    #[cfg(not(windows))]
    {
        let _ = app_data;
        command.env("QUOTA_GUARD_SESSION_STORAGE_REQUIRED", "false");
    }
}

#[cfg(windows)]
pub(crate) use windows::load_or_create_key;

#[cfg(windows)]
mod windows {
    use std::{
        fmt,
        fs::{self, File, OpenOptions},
        io::{self, Read, Write},
        os::windows::fs::MetadataExt,
        path::Path,
        ptr,
    };
    use windows_sys::Win32::{
        Foundation::LocalFree,
        Security::Cryptography::{
            BCryptGenRandom, CryptProtectData, CryptUnprotectData, BCRYPT_USE_SYSTEM_PREFERRED_RNG,
            CRYPTPROTECT_UI_FORBIDDEN, CRYPT_INTEGER_BLOB,
        },
    };
    use zeroize::Zeroize;

    const MAX_BLOB: usize = 8192;
    const REPARSE_POINT: u32 = 0x400;
    const ENTROPY: &[u8] = b"com.codexquotaguard.desktop.session-key.v1";

    #[derive(Debug)]
    pub(crate) struct VaultUnavailable;

    pub(crate) struct SessionKey([u8; 32]);
    impl fmt::Debug for SessionKey {
        fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
            formatter.write_str("SessionKey[redacted]")
        }
    }
    impl Drop for SessionKey {
        fn drop(&mut self) {
            self.0.zeroize();
        }
    }
    impl SessionKey {
        pub(crate) fn hex(&self) -> String {
            const DIGITS: &[u8; 16] = b"0123456789abcdef";
            let mut text = String::with_capacity(64);
            for byte in self.0 {
                text.push(DIGITS[(byte >> 4) as usize] as char);
                text.push(DIGITS[(byte & 15) as usize] as char);
            }
            text
        }
    }

    struct ProtectedBuffer(CRYPT_INTEGER_BLOB);
    impl Drop for ProtectedBuffer {
        fn drop(&mut self) {
            if !self.0.pbData.is_null() {
                // SAFETY: DPAPI allocated this successful result with LocalAlloc.
                // The reported bounded length belongs to the returned allocation.
                unsafe {
                    if (self.0.cbData as usize) <= MAX_BLOB {
                        std::slice::from_raw_parts_mut(self.0.pbData, self.0.cbData as usize)
                            .zeroize();
                    }
                    LocalFree(self.0.pbData.cast());
                }
            }
        }
    }

    pub(crate) fn load_or_create_key(directory: &Path) -> Result<SessionKey, VaultUnavailable> {
        if !directory.is_absolute() {
            return Err(VaultUnavailable);
        }
        validate_directory_chain(directory)?;
        fs::create_dir_all(directory).map_err(|_| VaultUnavailable)?;
        validate_directory_chain(directory)?;
        let path = directory.join("key.dpapi");
        match fs::symlink_metadata(&path) {
            Ok(_) => return read_key(&path),
            Err(error) if error.kind() == io::ErrorKind::NotFound => {}
            Err(_) => return Err(VaultUnavailable),
        }
        let mut key = SessionKey([0; 32]);
        // SAFETY: The writable buffer is exactly 32 bytes; system-preferred RNG
        // uses no algorithm handle. A negative NTSTATUS is a failure.
        if unsafe {
            BCryptGenRandom(
                ptr::null_mut(),
                key.0.as_mut_ptr(),
                32,
                BCRYPT_USE_SYSTEM_PREFERRED_RNG,
            )
        } < 0
        {
            return Err(VaultUnavailable);
        }
        let protected = protect(&key.0)?;
        let temporary = directory.join(format!(".key-{}.tmp", uuid::Uuid::new_v4()));
        let result = (|| {
            let mut file = OpenOptions::new()
                .write(true)
                .create_new(true)
                .open(&temporary)
                .map_err(|_| VaultUnavailable)?;
            file.write_all(&protected).map_err(|_| VaultUnavailable)?;
            file.sync_all().map_err(|_| VaultUnavailable)?;
            drop(file);
            // Atomic create-if-absent: readers never observe a partially written
            // key and concurrent launches cannot overwrite another launch's key.
            match fs::hard_link(&temporary, &path) {
                Ok(()) => Ok(key),
                Err(error) if error.kind() == io::ErrorKind::AlreadyExists => read_key(&path),
                Err(_) => Err(VaultUnavailable),
            }
        })();
        // This exact random temporary file is ours, never a user data directory.
        let _ = fs::remove_file(&temporary);
        result
    }

    fn validate_directory_chain(directory: &Path) -> Result<(), VaultUnavailable> {
        for ancestor in directory.ancestors() {
            match fs::symlink_metadata(ancestor) {
                Ok(metadata)
                    if metadata.is_dir() && metadata.file_attributes() & REPARSE_POINT == 0 => {}
                Err(error) if error.kind() == io::ErrorKind::NotFound => {}
                _ => return Err(VaultUnavailable),
            }
        }
        Ok(())
    }

    fn read_key(path: &Path) -> Result<SessionKey, VaultUnavailable> {
        let metadata = fs::symlink_metadata(path).map_err(|_| VaultUnavailable)?;
        if !metadata.is_file()
            || metadata.file_attributes() & REPARSE_POINT != 0
            || metadata.len() == 0
            || metadata.len() > MAX_BLOB as u64
        {
            return Err(VaultUnavailable);
        }
        let mut data = Vec::new();
        File::open(path)
            .map_err(|_| VaultUnavailable)?
            .take(MAX_BLOB as u64 + 1)
            .read_to_end(&mut data)
            .map_err(|_| VaultUnavailable)?;
        if data.is_empty() || data.len() > MAX_BLOB {
            return Err(VaultUnavailable);
        }
        let input = blob(&data);
        let entropy = blob(ENTROPY);
        let mut output = CRYPT_INTEGER_BLOB {
            cbData: 0,
            pbData: ptr::null_mut(),
        };
        // SAFETY: Input and entropy are borrowed read-only for the duration of
        // the call. No UI and no machine-wide decryption are permitted.
        let success = unsafe {
            CryptUnprotectData(
                &input,
                ptr::null_mut(),
                &entropy,
                ptr::null(),
                ptr::null(),
                CRYPTPROTECT_UI_FORBIDDEN,
                &mut output,
            )
        };
        if success == 0 {
            return Err(VaultUnavailable);
        }
        let output = ProtectedBuffer(output);
        if output.0.cbData != 32 || output.0.pbData.is_null() {
            return Err(VaultUnavailable);
        }
        let mut key = SessionKey([0; 32]);
        // SAFETY: A successful DPAPI call returned a verified 32-byte allocation.
        key.0
            .copy_from_slice(unsafe { std::slice::from_raw_parts(output.0.pbData, 32) });
        Ok(key)
    }

    fn blob(data: &[u8]) -> CRYPT_INTEGER_BLOB {
        CRYPT_INTEGER_BLOB {
            cbData: data.len() as u32,
            pbData: data.as_ptr().cast_mut(),
        }
    }

    fn protect(key: &[u8; 32]) -> Result<Vec<u8>, VaultUnavailable> {
        let input = blob(key);
        let entropy = blob(ENTROPY);
        let mut output = CRYPT_INTEGER_BLOB {
            cbData: 0,
            pbData: ptr::null_mut(),
        };
        // SAFETY: DPAPI only reads the live input/entropy buffers. LOCAL_MACHINE
        // is intentionally absent so the OS user's protection boundary is kept.
        let success = unsafe {
            CryptProtectData(
                &input,
                ptr::null(),
                &entropy,
                ptr::null(),
                ptr::null(),
                CRYPTPROTECT_UI_FORBIDDEN,
                &mut output,
            )
        };
        if success == 0 {
            return Err(VaultUnavailable);
        }
        let output = ProtectedBuffer(output);
        if output.0.pbData.is_null() || output.0.cbData == 0 || output.0.cbData as usize > MAX_BLOB
        {
            return Err(VaultUnavailable);
        }
        // SAFETY: DPAPI returned a bounded, valid output allocation.
        Ok(
            unsafe { std::slice::from_raw_parts(output.0.pbData, output.0.cbData as usize) }
                .to_vec(),
        )
    }
}
