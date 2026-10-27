//! CLI invocation adapter. Only timestamp, thread, turn and tier enter its journal.
use super::{diagnostic_store, Adapter};
use chrono::{DateTime, Utc};
use rusqlite::{Connection, OpenFlags};
use std::{
    collections::BTreeSet,
    ffi::OsString,
    fs,
    io::{Read, Write},
    path::{Path, PathBuf},
    process::{Command, Stdio},
    time::Duration,
};

#[cfg(windows)]
mod windows_job;
const JOURNAL: &str = "quota-guard-cli.sqlite";
const MAX_LINE: usize = 65536;
pub(super) struct Source(diagnostic_store::Reader);
impl Source {
    pub(super) fn open(home: &Path) -> Self {
        Self(diagnostic_store::Reader::open_file(&home.join(JOURNAL)))
    }
}
impl super::Source for Source {
    fn available(&self) -> bool {
        self.0.available()
    }
    fn file(&self, path: &Path) -> Box<dyn Adapter + '_> {
        Box::new(self.0.file(path))
    }
}

struct Evidence {
    at: DateTime<Utc>,
    thread: String,
    turn: String,
    tier: String,
    unset: bool,
}
fn id(prefix: &str, names: &[&str]) -> Option<String> {
    let ids: BTreeSet<_> = prefix
        .split(|c: char| c.is_whitespace() || matches!(c, '{' | '}' | ',' | ':'))
        .filter_map(|word| names.iter().find_map(|name| word.strip_prefix(name)))
        .filter_map(|value| uuid::Uuid::parse_str(value.trim_matches('"')).ok())
        .map(|v| v.to_string())
        .collect();
    (ids.len() == 1).then(|| ids.into_iter().next()).flatten()
}
fn evidence(line: &[u8]) -> Option<Evidence> {
    if line.len() > MAX_LINE {
        return None;
    }
    let text = std::str::from_utf8(line).ok()?;
    let (prefix, tags) = text.split_once("tags_json=")?;
    if !prefix.contains("feedback_tags:") {
        return None;
    }
    let at = DateTime::parse_from_rfc3339(prefix.split_whitespace().next()?)
        .ok()?
        .with_timezone(&Utc);
    let thread = id(prefix, &["thread_id=", "thread.id="])?;
    let turn = id(prefix, &["turn_id=", "turn.id="])?;
    let mut tags = serde_json::Deserializer::from_str(tags.trim())
        .into_iter::<serde_json::Value>()
        .next()?
        .ok()?;
    if let Some(text) = tags.as_str() {
        tags = serde_json::from_str(text).ok()?;
    }
    let raw_tier = tags.get("service_tier")?.as_str()?;
    let tier = match raw_tier {
        "fast" => "fast",
        "priority" => "priority",
        "default" => "default",
        "standard" => "standard",
        _ => "unknown",
    };
    Some(Evidence {
        at,
        thread,
        turn,
        tier: tier.into(),
        unset: raw_tier == "unset",
    })
}

// Some CLI versions normalize an explicit standard/default request to an unset
// diagnostic tag. Only the same invocation's last explicit override may resolve it.
fn explicitly_standard(arguments: &[OsString]) -> bool {
    let mut selected = None;
    let mut args = arguments.iter();
    while let Some(argument) = args.next() {
        let text = argument.to_string_lossy();
        if text == "--" {
            break;
        }
        let value = if matches!(text.as_ref(), "-c" | "--config") {
            args.next().map(|v| v.to_string_lossy())
        } else {
            text.strip_prefix("--config=")
                .or_else(|| text.strip_prefix("-c"))
                .map(std::borrow::Cow::Borrowed)
        };
        let Some(value) = value else {
            continue;
        };
        let Some((key, value)) = value.split_once('=') else {
            continue;
        };
        if key.trim() != "service_tier" {
            continue;
        }
        let value = value.trim();
        let decoded = serde_json::from_str::<String>(value)
            .ok()
            .or_else(|| {
                value
                    .strip_prefix('\'')
                    .and_then(|v| v.strip_suffix('\''))
                    .map(str::to_owned)
            })
            .unwrap_or_else(|| value.into());
        selected = Some(matches!(decoded.as_str(), "standard" | "default"));
    }
    selected.unwrap_or(false)
}
struct Journal(Connection);
impl Journal {
    fn open(home: &Path) -> Option<Self> {
        if !home.is_absolute() || !home.is_dir() {
            return None;
        }
        let path = home.join(JOURNAL);
        if fs::symlink_metadata(&path).is_ok_and(|m| !m.file_type().is_file()) {
            return None;
        }
        let connection = Connection::open_with_flags(
            path,
            OpenFlags::SQLITE_OPEN_READ_WRITE
                | OpenFlags::SQLITE_OPEN_CREATE
                | OpenFlags::SQLITE_OPEN_NO_MUTEX,
        )
        .ok()?;
        connection.busy_timeout(Duration::from_millis(250)).ok()?;
        connection.execute_batch("pragma max_page_count=8192;
            create table if not exists logs(id integer primary key,ts integer not null,ts_nanos integer not null,thread_id text not null,target text not null,feedback_log_body text not null);
            create unique index if not exists cli_evidence_unique on logs(thread_id,ts,ts_nanos,feedback_log_body);
            create index if not exists cli_evidence_lookup on logs(thread_id,ts desc,ts_nanos desc);").ok()?;
        // This is disposable metadata, separate from all usage journals and billing records.
        let _ = connection.execute(
            "delete from logs where ts<?",
            [Utc::now().timestamp() - 7 * 86400],
        );
        Some(Self(connection))
    }
    fn record(&self, observation: &Evidence) {
        let body = format!(
            "thread_id={} turn_id={} tags_json={{\"service_tier\":\"{}\"}}",
            observation.thread, observation.turn, observation.tier
        );
        let _=self.0.execute("insert or ignore into logs(ts,ts_nanos,thread_id,target,feedback_log_body) values(?,?,?,'feedback_tags',?)",
            rusqlite::params![observation.at.timestamp(),observation.at.timestamp_subsec_nanos(),observation.thread,body]);
    }
}

pub(super) fn run(arguments: &[OsString]) -> Result<i32, String> {
    let declared_standard = explicitly_standard(arguments);
    let real = std::env::var_os("QUOTA_GUARD_CODEX_EXECUTABLE")
        .map(PathBuf::from)
        .ok_or("CODEX_CLI_EXECUTABLE_REQUIRED")?;
    let real = real
        .canonicalize()
        .map_err(|_| "CODEX_CLI_EXECUTABLE_UNAVAILABLE")?;
    if !real
        .file_name()
        .and_then(|v| v.to_str())
        .is_some_and(|name| name.eq_ignore_ascii_case("codex.exe"))
        || Some(&real)
            == std::env::current_exe()
                .ok()
                .and_then(|path| path.canonicalize().ok())
                .as_ref()
    {
        return Err("CODEX_CLI_EXECUTABLE_INVALID".into());
    }
    let home = std::env::var_os("CODEX_HOME")
        .map(PathBuf::from)
        .or_else(|| std::env::var_os("USERPROFILE").map(|p| PathBuf::from(p).join(".codex")));
    let mut command = Command::new(real);
    let logging = std::env::var("RUST_LOG").unwrap_or_else(|_| "warn".into());
    command
        .args(arguments)
        .env(
            "RUST_LOG",
            format!("{logging},feedback_tags=info,[turn]=info,[run_turn]=info"),
        )
        .env_remove("QUOTA_GUARD_CODEX_CAPTURE")
        .env_remove("QUOTA_GUARD_CODEX_EXECUTABLE")
        .env("NO_COLOR", "1")
        .stdin(Stdio::inherit())
        .stdout(Stdio::inherit())
        .stderr(Stdio::piped());
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        command.creation_flags(0x08000000);
    }
    let mut child = command.spawn().map_err(|_| "CODEX_CLI_START_FAILED")?;
    #[cfg(windows)]
    let _job = match windows_job::ChildJob::assign(&child) {
        Ok(job) => job,
        Err(_) => {
            let _ = child.kill();
            let _ = child.wait();
            return Err("CODEX_CLI_CHILD_JOB_FAILED".into());
        }
    };
    let stderr = child.stderr.take().ok_or("CODEX_CLI_STDERR_UNAVAILABLE")?;
    let capture = match std::thread::Builder::new()
        .name("codex-cli-metadata".into())
        .spawn(move || {
            capture_stderr(
                stderr,
                std::io::stderr().lock(),
                home.as_deref(),
                declared_standard,
            );
        }) {
        Ok(worker) => worker,
        Err(_) => {
            let _ = child.kill();
            let _ = child.wait();
            return Err("CODEX_CLI_CAPTURE_THREAD_FAILED".into());
        }
    };
    let status = child.wait();
    if status.is_err() {
        let _ = child.kill();
    }
    // Close inherited pipes held by any remaining CLI descendants before draining.
    #[cfg(windows)]
    drop(_job);
    let _ = capture.join();
    status
        .map(|status| status.code().unwrap_or(1))
        .map_err(|_| "CODEX_CLI_WAIT_FAILED".into())
}

fn capture_stderr(
    mut stderr: impl Read,
    mut output: impl Write,
    home: Option<&Path>,
    declared_standard: bool,
) {
    let mut journal = None;
    let mut buffer = [0u8; 8192];
    let mut line = Vec::new();
    let mut oversized = false;
    while let Ok(count) = stderr.read(&mut buffer) {
        if count == 0 {
            break;
        }
        let _ = output.write_all(&buffer[..count]);
        for byte in &buffer[..count] {
            if *byte == b'\n' {
                if !oversized {
                    if let Some(mut observation) = evidence(&line) {
                        if observation.unset && declared_standard {
                            observation.tier = "standard".into();
                        }
                        if journal.is_none() {
                            journal = home.and_then(Journal::open);
                        }
                        if let Some(journal) = &journal {
                            journal.record(&observation);
                        }
                    }
                }
                line.clear();
                oversized = false;
            } else if line.len() < MAX_LINE {
                line.push(*byte);
            } else {
                oversized = true;
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    const THREAD: &str = "11111111-1111-4111-8111-111111111111";
    const TURN: &str = "22222222-2222-4222-8222-222222222222";
    fn line(tier: &str) -> String {
        format!("2026-10-27T09:00:00Z INFO turn{{thread.id={THREAD} turn.id={TURN}}}: feedback_tags: tags_json={{\"service_tier\":\"{tier}\",\"private_prompt\":\"must-not-save\"}}")
    }
    #[test]
    fn standard_requires_an_explicit_current_invocation_and_honors_later_overrides() {
        let args = |s: &[&str]| s.iter().map(OsString::from).collect::<Vec<_>>();
        assert!(explicitly_standard(&args(&[
            "exec",
            "--config",
            "service_tier=\"standard\""
        ])));
        assert!(explicitly_standard(&args(&[
            "exec",
            "-cservice_tier='default'"
        ])));
        assert!(!explicitly_standard(&args(&["exec"])));
        assert!(!explicitly_standard(&args(&[
            "exec",
            "--",
            "-cservice_tier=standard"
        ])));
        assert!(!explicitly_standard(&args(&[
            "-c",
            "service_tier=standard",
            "--config=service_tier=priority"
        ])));
        assert!(!explicitly_standard(&args(&[
            "-c",
            "service_tier=standard",
            "-c",
            "service_tier=auto"
        ])));
    }
    #[test]
    fn requires_real_scoped_diagnostics_and_never_guesses_unset() {
        assert_eq!(
            evidence(line("priority").as_bytes()).unwrap().tier,
            "priority"
        );
        assert_eq!(evidence(line("unset").as_bytes()).unwrap().tier, "unknown");
        assert!(evidence(line("fast").replace("thread.id=", "not_thread=").as_bytes()).is_none());
        assert!(evidence(
            line("fast")
                .replace("feedback_tags:", "user_text:")
                .as_bytes()
        )
        .is_none());
        assert!(evidence(line("fast").replace("turn.id=", "not_turn=").as_bytes()).is_none());
        assert!(
            evidence(format!("{} {}", line("fast"), "x".repeat(MAX_LINE)).as_bytes()).is_none()
        );
    }
    #[test]
    fn cli_evidence_is_isolated_and_repairs_without_changing_event_ids() {
        let home = std::env::temp_dir().join(format!("cqg-cli-{}", uuid::Uuid::new_v4()));
        fs::create_dir_all(home.join("sessions")).unwrap();
        let journal = Journal::open(&home).unwrap();
        journal.record(&evidence(line("priority").as_bytes()).unwrap());
        let saved: String = journal
            .0
            .query_row("select feedback_log_body from logs", [], |r| r.get(0))
            .unwrap();
        assert!(!saved.contains("must-not-save"));
        let row = serde_json::json!({"timestamp":"2026-10-27T09:01:00Z","type":"token_usage_record","payload":{"thread_id":THREAD,"turn_id":TURN,"response_id":"resp_cli_fixture","usage":{"total_tokens":10,"input_tokens":8,"output_tokens":2}}});
        let raw = serde_json::to_vec(&row).unwrap();
        let id = crate::parse_direct_record(&raw).unwrap().event_id;
        fs::write(
            home.join("sessions")
                .join(format!("rollout-{THREAD}.jsonl")),
            [raw.as_slice(), b"\n"].concat(),
        )
        .unwrap();
        let events = crate::scan(&home, &crate::ScanState::default())
            .unwrap()
            .events;
        assert_eq!(events[0].service_tier.as_deref(), Some("priority"));
        assert_eq!(events[0].event_id, id);
        assert_eq!(super::super::recent_corrections(&home)[0].event_id, id);
        drop(journal);
        fs::remove_dir_all(home).unwrap();
    }
}
