//! Independent signed meter updates. Only the SYSTEM Windows service starts this supervisor.
use crate::MeterConfig;
use base64::{engine::general_purpose::STANDARD, Engine};
use minisign_verify::{PublicKey, Signature};
use semver::Version;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::{
    fs::{self, File, OpenOptions},
    io::{Read, Write},
    path::{Path, PathBuf},
    sync::{
        atomic::{AtomicBool, Ordering},
        Arc,
    },
    thread,
    time::{Duration, SystemTime, UNIX_EPOCH},
};

#[cfg(windows)]
mod windows;
const MAX_PACKAGE: u64 = 64 * 1024 * 1024;
const PUBLISHER: &str = include_str!("../../resources/update-public.key");
const POLL_SECONDS: u64 = 300;
type Result<T> = std::result::Result<T, String>;

#[derive(Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct UpdatePlan {
    pub version: String,
    pub previous_version: String,
    pub previous_sha256: String,
    pub sha256: String,
    pub size_bytes: u64,
    pub signature: String,
    pub origin: String,
    pub config_sha256: String,
    pub created_at: u64,
}
#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct Manifest {
    version: String,
    url: String,
    signature: String,
    sha256: String,
    size_bytes: u64,
    mandatory: bool,
}

fn fail(code: &str) -> String {
    code.into()
}
fn now() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_or(0, |d| d.as_secs())
}
fn parsed(value: &str) -> Result<Version> {
    let version = Version::parse(value).map_err(|_| fail("METER_UPDATE_VERSION_INVALID"))?;
    if !version.pre.is_empty() || !version.build.is_empty() {
        return Err(fail("METER_UPDATE_VERSION_INVALID"));
    }
    Ok(version)
}
fn newer(version: &str, current: &str) -> Result<bool> {
    Ok(parsed(version)? > parsed(current)?)
}
fn bounded(path: &Path, limit: u64) -> Result<Vec<u8>> {
    crate::server_state::check_path(path)?;
    let file = File::open(path).map_err(|_| fail("METER_UPDATE_READ_FAILED"))?;
    if !file
        .metadata()
        .map_err(|_| fail("METER_UPDATE_READ_FAILED"))?
        .is_file()
    {
        return Err(fail("METER_UPDATE_PATH_UNSAFE"));
    }
    let mut bytes = Vec::new();
    file.take(limit + 1)
        .read_to_end(&mut bytes)
        .map_err(|_| fail("METER_UPDATE_READ_FAILED"))?;
    if bytes.len() as u64 > limit {
        return Err(fail("METER_UPDATE_TOO_LARGE"));
    }
    Ok(bytes)
}
fn hash_file(path: &Path) -> Result<String> {
    crate::server_state::check_path(path)?;
    let mut file = File::open(path).map_err(|_| fail("METER_UPDATE_READ_FAILED"))?;
    let mut digest = Sha256::new();
    let mut buffer = [0u8; 65536];
    loop {
        let count = file
            .read(&mut buffer)
            .map_err(|_| fail("METER_UPDATE_READ_FAILED"))?;
        if count == 0 {
            break;
        }
        digest.update(&buffer[..count]);
    }
    Ok(format!("{:x}", digest.finalize()))
}
fn verify_with_key(path: &Path, signature: &str, version: &str, key: &str) -> Result<()> {
    if signature.len() > 32768 {
        return Err(fail("METER_UPDATE_SIGNATURE_INVALID"));
    }
    let key = String::from_utf8(
        STANDARD
            .decode(key.trim())
            .map_err(|_| fail("METER_UPDATE_SIGNATURE_INVALID"))?,
    )
    .map_err(|_| fail("METER_UPDATE_SIGNATURE_INVALID"))?;
    let text = String::from_utf8(
        STANDARD
            .decode(signature.trim())
            .map_err(|_| fail("METER_UPDATE_SIGNATURE_INVALID"))?,
    )
    .map_err(|_| fail("METER_UPDATE_SIGNATURE_INVALID"))?;
    let key = PublicKey::decode(&key).map_err(|_| fail("METER_UPDATE_SIGNATURE_INVALID"))?;
    let signature = Signature::decode(&text).map_err(|_| fail("METER_UPDATE_SIGNATURE_INVALID"))?;
    let mut verifier = key
        .verify_stream(&signature)
        .map_err(|_| fail("METER_UPDATE_SIGNATURE_INVALID"))?;
    let mut file = File::open(path).map_err(|_| fail("METER_UPDATE_READ_FAILED"))?;
    let mut buffer = [0u8; 65536];
    loop {
        let count = file
            .read(&mut buffer)
            .map_err(|_| fail("METER_UPDATE_READ_FAILED"))?;
        if count == 0 {
            break;
        }
        verifier.update(&buffer[..count]);
    }
    verifier
        .finalize()
        .map_err(|_| fail("METER_UPDATE_SIGNATURE_INVALID"))?;
    let signed = signature
        .trusted_comment()
        .split('\t')
        .find_map(|field| field.strip_prefix("version:"))
        .ok_or_else(|| fail("METER_UPDATE_UNSIGNED_VERSION"))?;
    if parsed(signed)? != parsed(version)? {
        return Err(fail("METER_UPDATE_SIGNED_VERSION_MISMATCH"));
    }
    Ok(())
}
fn verify_package(path: &Path, plan: &UpdatePlan) -> Result<()> {
    crate::server_state::check_path(path)?;
    if plan.size_bytes < 64
        || plan.size_bytes > MAX_PACKAGE
        || fs::metadata(path)
            .map_err(|_| fail("METER_UPDATE_READ_FAILED"))?
            .len()
            != plan.size_bytes
        || hash_file(path)? != plan.sha256
    {
        return Err(fail("METER_UPDATE_HASH_MISMATCH"));
    }
    verify_with_key(path, &plan.signature, &plan.version, PUBLISHER)
}
fn write_json(path: &Path, value: &impl Serialize) -> Result<()> {
    crate::server_state::check_path(path)?;
    let temporary = path.with_extension(format!("{}.tmp", uuid::Uuid::new_v4()));
    let result = (|| {
        let mut file = OpenOptions::new()
            .create_new(true)
            .write(true)
            .open(&temporary)
            .map_err(|_| fail("METER_UPDATE_WRITE_FAILED"))?;
        file.write_all(&serde_json::to_vec(value).map_err(|_| fail("METER_UPDATE_WRITE_FAILED"))?)
            .and_then(|()| file.sync_all())
            .map_err(|_| fail("METER_UPDATE_WRITE_FAILED"))?;
        drop(file);
        fs::rename(&temporary, path).map_err(|_| fail("METER_UPDATE_WRITE_FAILED"))
    })();
    let _ = fs::remove_file(temporary);
    result
}
fn configuration(path: &Path) -> Result<MeterConfig> {
    let config: MeterConfig =
        serde_json::from_slice(&bounded(path, 8192)?).map_err(|_| fail("METER_CONFIG_INVALID"))?;
    config.validate()?;
    Ok(config)
}
fn asset_url(origin: &str, url: &str) -> Result<reqwest::Url> {
    let offered = reqwest::Url::parse(url).map_err(|_| fail("METER_UPDATE_URL_INVALID"))?;
    if !offered.path().starts_with("/api/v1/meter-updates/assets/")
        || offered.query().is_some()
        || offered.fragment().is_some()
        || !offered.username().is_empty()
        || offered.password().is_some()
    {
        return Err(fail("METER_UPDATE_URL_INVALID"));
    }
    reqwest::Url::parse(origin)
        .and_then(|base| base.join(offered.path()))
        .map_err(|_| fail("METER_UPDATE_URL_INVALID"))
}
fn manifest(origin: &str) -> Result<Option<Manifest>> {
    let client = reqwest::blocking::Client::builder()
        .no_proxy()
        .redirect(reqwest::redirect::Policy::none())
        .connect_timeout(Duration::from_secs(5))
        .timeout(Duration::from_secs(20))
        .build()
        .map_err(|_| fail("METER_UPDATE_NETWORK"))?;
    let url = format!(
        "{origin}/api/v1/meter-updates/manifest/windows/{}/{}",
        std::env::consts::ARCH,
        env!("CARGO_PKG_VERSION")
    );
    let response = client
        .get(url)
        .send()
        .map_err(|_| fail("METER_UPDATE_NETWORK"))?;
    if response.status() == reqwest::StatusCode::NO_CONTENT {
        return Ok(None);
    }
    if response.status() != reqwest::StatusCode::OK {
        return Err(fail("METER_UPDATE_NETWORK"));
    }
    let mut bytes = Vec::new();
    response
        .take(65537)
        .read_to_end(&mut bytes)
        .map_err(|_| fail("METER_UPDATE_NETWORK"))?;
    if bytes.len() > 65536 {
        return Err(fail("METER_UPDATE_MANIFEST_INVALID"));
    }
    let value: Manifest =
        serde_json::from_slice(&bytes).map_err(|_| fail("METER_UPDATE_MANIFEST_INVALID"))?;
    if !value.mandatory
        || value.size_bytes > MAX_PACKAGE
        || value.size_bytes < 64
        || value.sha256.len() != 64
        || !value.sha256.bytes().all(|b| b.is_ascii_hexdigit())
        || value.signature.len() > 32768
    {
        return Err(fail("METER_UPDATE_MANIFEST_INVALID"));
    }
    if !newer(&value.version, env!("CARGO_PKG_VERSION"))? {
        return Ok(None);
    }
    Ok(Some(value))
}

pub struct Supervisor {
    stop: Arc<AtomicBool>,
    config: PathBuf,
}
impl Supervisor {
    pub fn start(config: PathBuf) -> Self {
        let stop = Arc::new(AtomicBool::new(false));
        #[cfg(windows)]
        {
            let path = config.clone();
            let ended = stop.clone();
            // Recover an interrupted binary transaction even if the meter journal cannot initialize.
            let _ = windows::recover_if_needed(&path);
            thread::spawn(move || {
                for _ in 0..100 {
                    if ended.load(Ordering::Relaxed) {
                        return;
                    }
                    thread::sleep(Duration::from_millis(100));
                }
                while !ended.load(Ordering::Relaxed) {
                    if let Err(code) = windows::check_and_stage(&path, &ended) {
                        windows::record_error(&path, &code);
                    }
                    for _ in 0..POLL_SECONDS {
                        if ended.load(Ordering::Relaxed) {
                            return;
                        }
                        thread::sleep(Duration::from_secs(1));
                    }
                }
            });
        }
        Self { stop, config }
    }
    pub fn heartbeat(&self) {
        #[cfg(windows)]
        {
            let _ = windows::heartbeat(&self.config);
        }
    }
}
impl Drop for Supervisor {
    fn drop(&mut self) {
        self.stop.store(true, Ordering::Relaxed);
    }
}

/// Read-only verification mode used by the hidden SYSTEM update transaction worker.
pub fn verify_work_directory(directory: &Path) -> Result<()> {
    #[cfg(windows)]
    {
        return windows::verify_work_directory(directory);
    }
    #[cfg(not(windows))]
    {
        let _ = directory;
        Err(fail("METER_SERVICE_UNSUPPORTED"))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn update_versions_are_independent_and_never_downgrade() {
        assert!(newer("0.2.14", "0.2.13").unwrap());
        assert!(!newer("0.2.12", "0.2.13").unwrap());
        assert!(!newer("0.2.13", "0.2.13").unwrap());
        assert!(newer("0.2.14-beta", "0.2.13").is_err());
    }
    #[test]
    fn download_urls_stay_on_the_selected_server() {
        let value = asset_url(
            "https://updates.example",
            "http://backend:8080/api/v1/meter-updates/assets/id/meter.exe",
        )
        .unwrap();
        assert_eq!(
            value.as_str(),
            "https://updates.example/api/v1/meter-updates/assets/id/meter.exe"
        );
        assert!(asset_url("https://updates.example", "https://other.example/run.exe").is_err());
        assert!(asset_url(
            "https://updates.example",
            "https://other.example/api/v1/meter-updates/assets/id?a=1"
        )
        .is_err());
    }
    #[test]
    fn signatures_bind_both_payload_and_version_and_reject_other_publisher_keys() {
        let root =
            std::env::temp_dir().join(format!("cqg-signature-test-{}", uuid::Uuid::new_v4()));
        fs::create_dir(&root).unwrap();
        let path = root.join("proof.txt");
        fs::write(&path, include_bytes!("test-data/proof.txt")).unwrap();
        let signature = include_str!("test-data/proof.txt.sig");
        let key = include_str!("test-data/public.key");
        assert!(verify_with_key(&path, signature, "0.2.0", key).is_ok());
        assert!(verify_with_key(&path, signature, "0.2.1", key).is_err());
        assert!(verify_with_key(&path, signature, "0.2.0", PUBLISHER).is_err());
        let modified = String::from_utf8(STANDARD.decode(signature.trim()).unwrap())
            .unwrap()
            .replace("version:0.2.0", "version:0.2.1");
        assert!(verify_with_key(&path, &STANDARD.encode(modified), "0.2.1", key).is_err());
        fs::write(&path, b"modified payload").unwrap();
        assert!(verify_with_key(&path, signature, "0.2.0", key).is_err());
        fs::remove_file(path).unwrap();
        fs::remove_dir(root).unwrap();
    }
}
