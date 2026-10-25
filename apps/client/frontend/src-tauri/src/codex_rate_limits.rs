//! Read official Codex account metadata from the user's local Codex CLI.
//! The desktop never proxies this request through the configured server.

use serde::Serialize;
use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use std::{
    io::{BufReader, Read, Write},
    path::{Path, PathBuf},
    process::{Child, ChildStdin, Command, Stdio},
    sync::mpsc::{self, Receiver},
    thread,
    time::{Duration, Instant},
};
use zeroize::Zeroize;
#[cfg(windows)]
use std::os::windows::process::CommandExt;

const TIMEOUT: Duration = Duration::from_secs(20);
const MAX_LINE_BYTES: usize = 1024 * 1024;
const MAX_AUTH_BYTES: usize = 65536;

#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct OfficialCodexRateLimits {
    pub plan_type: Option<String>,
    pub windows: Vec<OfficialCodexRateLimitWindow>,
    pub fetched_at: Option<String>,
}

#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct OfficialCodexRateLimitWindow {
    pub limit_id: String,
    pub slot: String,
    pub window_duration_mins: u64,
    pub used_percent: f64,
    pub resets_at: Option<String>,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum CodexRateLimitError {
    Unavailable,
    SessionConflict,
}

pub(crate) fn read() -> Result<OfficialCodexRateLimits, CodexRateLimitError> {
    let home = official_home().ok_or(CodexRateLimitError::Unavailable)?;
    let auth_before = auth_fingerprint(&home);
    let conflict_before = crate::codex_connection::conflict_snapshot();
    let executable = resolve_executable().ok_or(CodexRateLimitError::Unavailable)?;
    let mut command = Command::new(executable);
    command
        .args([
            "app-server",
            "--listen",
            "stdio://",
            "-c",
            "cli_auth_credentials_store=\"file\"",
            "-c",
            "features.plugins=false",
        ])
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::null())
        .env_remove("QUOTA_GUARD_API_TOKEN")
        .env_remove("QUOTA_GUARD_CONTROL_PLANE_URL")
        .env("CODEX_HOME", &home);
    #[cfg(windows)]
    command.creation_flags(0x08000000);
    let child = command
        .spawn()
        .map_err(|_| CodexRateLimitError::Unavailable)?;
    let mut rpc = Rpc::new(child).map_err(|_| CodexRateLimitError::Unavailable)?;
    rpc.call(
        "initialize",
        json!({"clientInfo":{"name":"codex_quota_guard","version":"0.3.24"}}),
    )
    .map_err(|_| CodexRateLimitError::Unavailable)?;
    rpc.notify(json!({"method":"initialized"}))
        .map_err(|_| CodexRateLimitError::Unavailable)?;
    let account = rpc
        .call("account/read", json!({"refreshToken":false}))
        .map_err(|_| CodexRateLimitError::Unavailable)?;
    let account_type = account
        .get("account")
        .and_then(|account| account.get("type"))
        .and_then(Value::as_str)
        .unwrap_or("");
    if account_type != "chatgpt" {
        let stable_placeholder = account_type == "apiKey"
            && conflict_before.is_some()
            && auth_before.is_some()
            && auth_fingerprint(&home) == auth_before;
        return Err(if stable_placeholder {
            CodexRateLimitError::SessionConflict
        } else {
            CodexRateLimitError::Unavailable
        });
    }
    let limits = rpc
        .call("account/rateLimits/read", json!({}))
        .map_err(|_| CodexRateLimitError::Unavailable)?;
    parse(&account, &limits)
}

fn official_home() -> Option<PathBuf> {
    if let Some(value) = std::env::var_os("CODEX_HOME").filter(|value| !value.is_empty()) {
        let path = PathBuf::from(value);
        return path.is_absolute().then_some(path);
    }
    #[cfg(windows)]
    {
        std::env::var_os("USERPROFILE").map(PathBuf::from).map(|path| path.join(".codex"))
    }
    #[cfg(not(windows))]
    {
        std::env::var_os("HOME").map(PathBuf::from).map(|path| path.join(".codex"))
    }
}

fn resolve_executable() -> Option<PathBuf> {
    #[cfg(windows)]
    {
        if let Some(path) = std::env::var_os("PATH") {
            for directory in std::env::split_paths(&path) {
                let candidate = directory.join("codex.exe");
                if candidate.is_file() {
                    return Some(candidate);
                }
            }
        }
        if let Some(local) = std::env::var_os("LOCALAPPDATA") {
            let bin = PathBuf::from(local).join("OpenAI").join("Codex").join("bin");
            let direct = bin.join("codex.exe");
            if direct.is_file() {
                return Some(direct);
            }
            let mut candidates = Vec::new();
            let children = std::fs::read_dir(bin).ok()?;
            for entry in children.take(256).flatten() {
                let directory = entry.path();
                if !directory.is_dir() {
                    continue;
                }
                let candidate = directory.join("codex.exe");
                if let Ok(metadata) = std::fs::metadata(&candidate) {
                    candidates.push((metadata.modified().ok()?, candidate));
                }
            }
            candidates.sort_by(|left, right| right.0.cmp(&left.0));
            return candidates.into_iter().next().map(|(_, path)| path);
        }
        None
    }
    #[cfg(not(windows))]
    {
        Some(PathBuf::from("codex"))
    }
}

fn auth_fingerprint(home: &Path) -> Option<String> {
    let path = home.join("auth.json");
    let metadata = std::fs::symlink_metadata(&path).ok()?;
    if !metadata.is_file() || metadata.len() > MAX_AUTH_BYTES as u64 {
        return None;
    }
    let mut bytes = Vec::new();
    std::fs::File::open(path)
        .ok()?
        .take(MAX_AUTH_BYTES as u64 + 1)
        .read_to_end(&mut bytes)
        .ok()?;
    if bytes.len() > MAX_AUTH_BYTES {
        bytes.zeroize();
        return None;
    }
    let fingerprint = format!("{:x}", Sha256::digest(&bytes));
    bytes.zeroize();
    Some(fingerprint)
}

fn parse(account: &Value, limits: &Value) -> Result<OfficialCodexRateLimits, CodexRateLimitError> {
    let plan = account
        .get("account")
        .and_then(|account| account.get("planType"))
        .and_then(Value::as_str)
        .map(str::trim)
        .filter(|value| !value.is_empty() && value.len() <= 64)
        .map(str::to_owned);
    let mut windows = Vec::new();
    if let Some(buckets) = limits
        .get("rateLimitsByLimitId")
        .and_then(Value::as_object)
        .filter(|buckets| !buckets.is_empty())
    {
        for (id, bucket) in buckets {
            add_windows(&mut windows, id, bucket);
        }
    } else if let Some(bucket) = limits.get("rateLimits").filter(|value| value.is_object()) {
        let id = bucket
            .get("limitId")
            .and_then(Value::as_str)
            .unwrap_or("codex");
        add_windows(&mut windows, id, bucket);
    }
    windows.sort_by(|left, right| {
        left.limit_id
            .cmp(&right.limit_id)
            .then(left.window_duration_mins.cmp(&right.window_duration_mins))
    });
    let fetched_at = Some(chrono::Utc::now().to_rfc3339());
    Ok(OfficialCodexRateLimits {
        plan_type: plan,
        windows,
        fetched_at,
    })
}

fn add_windows(windows: &mut Vec<OfficialCodexRateLimitWindow>, id: &str, bucket: &Value) {
    if id.is_empty() || id.len() > 64 {
        return;
    }
    add_window(windows, id, "primary", bucket.get("primary"));
    add_window(windows, id, "secondary", bucket.get("secondary"));
}

fn add_window(
    windows: &mut Vec<OfficialCodexRateLimitWindow>,
    id: &str,
    slot: &str,
    value: Option<&Value>,
) {
    let Some(value) = value else {
        return;
    };
    let Some(minutes) = value.get("windowDurationMins").and_then(Value::as_u64) else {
        return;
    };
    let Some(percent) = value.get("usedPercent").and_then(Value::as_f64) else {
        return;
    };
    if minutes == 0 || minutes > 525_600 || !percent.is_finite() || !(0.0..=100.0).contains(&percent) {
        return;
    }
    let resets_at = value
        .get("resetsAt")
        .and_then(Value::as_i64)
        .and_then(|seconds| chrono::DateTime::<chrono::Utc>::from_timestamp(seconds, 0))
        .map(|time| time.to_rfc3339());
    windows.push(OfficialCodexRateLimitWindow {
        limit_id: id.to_owned(),
        slot: slot.to_owned(),
        window_duration_mins: minutes,
        used_percent: percent,
        resets_at,
    });
}

struct Rpc {
    child: Child,
    stdin: ChildStdin,
    lines: Receiver<Result<String, ()>>,
    next_id: u64,
}

impl Rpc {
    fn new(mut child: Child) -> Result<Self, ()> {
        let stdin = child.stdin.take().ok_or(())?;
        let stdout = child.stdout.take().ok_or(())?;
        let (send, lines) = mpsc::channel();
        thread::Builder::new()
            .name("local-codex-rate-limit-rpc".into())
            .spawn(move || read_lines(stdout, send))
            .map_err(|_| ())?;
        Ok(Self {
            child,
            stdin,
            lines,
            next_id: 0,
        })
    }

    fn call(&mut self, method: &str, params: Value) -> Result<Value, ()> {
        self.next_id = self.next_id.saturating_add(1);
        let id = self.next_id;
        self.send(json!({"id":id,"method":method,"params":params}))?;
        let deadline = Instant::now() + TIMEOUT;
        loop {
            let remaining = deadline.checked_duration_since(Instant::now()).ok_or(())?;
            let line = self.lines.recv_timeout(remaining).map_err(|_| ())??;
            let message: Value = serde_json::from_str(&line).map_err(|_| ())?;
            if message.get("id").and_then(Value::as_u64) != Some(id) {
                continue;
            }
            if message.get("error").is_some() {
                return Err(());
            }
            return message.get("result").cloned().ok_or(());
        }
    }

    fn notify(&mut self, message: Value) -> Result<(), ()> {
        self.send(message)
    }

    fn send(&mut self, message: Value) -> Result<(), ()> {
        serde_json::to_writer(&mut self.stdin, &message).map_err(|_| ())?;
        self.stdin.write_all(b"\n").map_err(|_| ())?;
        self.stdin.flush().map_err(|_| ())
    }
}

impl Drop for Rpc {
    fn drop(&mut self) {
        let _ = self.child.kill();
        let _ = self.child.wait();
    }
}

fn read_lines(stdout: impl Read, send: mpsc::Sender<Result<String, ()>>) {
    let mut input = BufReader::new(stdout);
    loop {
        let mut line = Vec::new();
        let mut byte = [0u8; 1];
        loop {
            match input.read(&mut byte) {
                Ok(0) => {
                    if !line.is_empty() {
                        let _ = send.send(
                            String::from_utf8(line).map_err(|_| ()),
                        );
                    }
                    return;
                }
                Ok(_) if byte[0] == b'\n' => break,
                Ok(_) if line.len() < MAX_LINE_BYTES => line.push(byte[0]),
                Ok(_) => {
                    let _ = send.send(Err(()));
                    return;
                }
                Err(_) => {
                    let _ = send.send(Err(()));
                    return;
                }
            }
        }
        if line.last() == Some(&b'\r') {
            line.pop();
        }
        if send.send(String::from_utf8(line).map_err(|_| ())).is_err() {
            return;
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn official_rate_limit_response_keeps_only_bounded_window_values() {
        let account = json!({"account":{"type":"chatgpt","planType":"plus"}});
        let limits = json!({
            "rateLimitsByLimitId": {
                "codex": {
                    "primary": {"windowDurationMins":10080,"usedPercent":42.5,"resetsAt":1791400000},
                    "secondary": {"windowDurationMins":-1,"usedPercent":101}
                }
            }
        });
        let result = parse(&account, &limits).unwrap();
        assert_eq!(result.plan_type.as_deref(), Some("plus"));
        assert_eq!(result.windows.len(), 1);
        assert_eq!(result.windows[0].window_duration_mins, 10080);
        assert_eq!(result.windows[0].used_percent, 42.5);
        assert!(result.windows[0].resets_at.is_some());
    }
}
