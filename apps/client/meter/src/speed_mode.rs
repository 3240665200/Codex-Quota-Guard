//! Read only per-turn diagnostic tags, never current global defaults or chat text.
use chrono::{DateTime, Utc};
use rusqlite::{Connection, OpenFlags};
use serde::Serialize;
use std::cell::RefCell;
use std::{collections::{BTreeMap, BTreeSet}, fs, io::{BufRead, BufReader, Seek, SeekFrom}, path::Path, time::Duration};

type TurnSamples = BTreeMap<String, Vec<(DateTime<Utc>, String)>>;
#[derive(Default)]
pub(super) struct Timeline<'a> {
    thread: Option<String>, turns: TurnSamples,
    reader: Option<&'a Reader>, alternates: RefCell<BTreeMap<String, TurnSamples>>,
}
pub(super) struct Reader(Option<Connection>);
impl Reader {
    pub(super) fn open(home: &Path) -> Self {
        let file = home.join("logs_2.sqlite");
        if !fs::symlink_metadata(&file).is_ok_and(|m| m.file_type().is_file()) { return Self(None); }
        let conn = Connection::open_with_flags(file, OpenFlags::SQLITE_OPEN_READ_ONLY | OpenFlags::SQLITE_OPEN_NO_MUTEX).ok();
        if let Some(c) = &conn { let _ = c.busy_timeout(Duration::from_millis(50)); }
        Self(conn)
    }
    pub(super) fn file(&self, file: &Path) -> Timeline<'_> {
        let mut timeline = Timeline::default();
        timeline.reader=Some(self);
        let Some(thread) = file.file_stem().and_then(|s| s.to_str()).and_then(|s| s.get(s.len().saturating_sub(36)..))
            .and_then(|s| uuid::Uuid::parse_str(s).ok()).map(|id| id.to_string()) else { return timeline; };
        timeline.thread = Some(thread.clone());
        timeline.turns=self.thread(&thread);
        timeline
    }
    fn thread(&self, thread: &str) -> TurnSamples {
        let mut turns=TurnSamples::new();
        let Some(conn) = &self.0 else { return turns; };
        // Uses Codex's thread/timestamp index. Work is bounded for each changed file.
        let Ok(mut query) = conn.prepare("select ts,ts_nanos,substr(feedback_log_body,1,65537) from logs where thread_id=? and target='feedback_tags' order by ts desc,ts_nanos desc,id desc limit 512") else { return turns; };
        let Ok(rows) = query.query_map([thread], |r| Ok((r.get::<_, i64>(0)?, r.get::<_, u32>(1)?, r.get::<_, String>(2)?))) else { return turns; };
        for row in rows.flatten() {
            let Some(at) = DateTime::<Utc>::from_timestamp(row.0, row.1) else { continue; };
            if let Some((turn, tier)) = parse_tags(&row.2, &thread) {
                turns.entry(super::hex_digest(turn.as_bytes())).or_default().push((at,tier));
            }
        }
        turns
    }
}
fn one_id(prefix: &str, key: &str) -> Option<String> {
    let ids: BTreeSet<_> = prefix.split(|c: char| c.is_whitespace() || matches!(c, '{' | '}' | ','))
        .filter_map(|word| word.strip_prefix(key))
        .filter_map(|id| uuid::Uuid::parse_str(id.trim_matches('"')).ok()).map(|id| id.to_string()).collect();
    (ids.len() == 1).then(|| ids.into_iter().next()).flatten()
}
fn parse_tags(body: &str, thread: &str) -> Option<(String,String)> {
    if body.len() > 65536 { return None; }
    let (prefix, raw) = body.split_once("tags_json=")?;
    if prefix.contains("thread_id=") && one_id(prefix,"thread_id=").as_deref()!=Some(thread) { return None; }
    let turn = one_id(prefix,"turn_id=")?;
    let mut tags = serde_json::Deserializer::from_str(raw.trim()).into_iter::<serde_json::Value>().next()?.ok()?;
    if let Some(text) = tags.as_str() { tags = serde_json::from_str(text).ok()?; }
    let tier = tags.get("service_tier")?.as_str()?;
    let tier = match tier { "default" | "standard" | "fast" | "priority" => tier, _ => "unknown" };
    Some((turn,tier.to_owned()))
}
impl Timeline<'_> {
    pub(super) fn enrich(&self, row: &mut serde_json::Value, cursor: &super::FileCursor) {
        if row.get("type").and_then(|v| v.as_str()) == Some("turn_context") { return; }
        let Some(at) = row.get("timestamp").and_then(|v| v.as_str()).and_then(|s| DateTime::parse_from_rfc3339(s).ok()).map(|at| at.with_timezone(&Utc)) else { return; };
        let Some(payload) = row.get_mut("payload").and_then(|v| v.as_object_mut()) else { return; };
        if payload.get("service_tier").or_else(|| payload.get("serviceTier")).and_then(|v| v.as_str())
            .is_some_and(|tier| matches!(tier,"default" | "standard" | "fast" | "priority")) { return; }
        // Resumed/compacted rollouts can have a new file UUID while the usage
        // still belongs to the original logical thread. The event is authoritative.
        // session_id can describe a different underlying session; thread_id
        // takes precedence, just as it does when identifying the usage event.
        let explicit=payload.get("thread_id").filter(|v|!v.is_null())
            .or_else(||payload.get("session_id").filter(|v|!v.is_null()));
        let thread=match explicit {
            Some(value)=>{let Some(id)=value.as_str().and_then(|v|uuid::Uuid::parse_str(v).ok()) else { return; };Some(id.to_string())},
            None=>None,
        };
        let Some(thread)=thread.or_else(||self.thread.clone()) else { return; };
        let turn = payload.get("turn_id").or_else(|| payload.get("root_turn_id")).and_then(|v| v.as_str())
            .map(|id| super::hex_digest(id.as_bytes())).or_else(|| cursor.current_turn_hash.clone());
        let Some(turn)=turn else { return; };
        let mode=if self.thread.as_deref()==Some(&thread) {
            sample_mode(&self.turns,&turn,at)
        } else {
            let mut alternates=self.alternates.borrow_mut();
            if !alternates.contains_key(&thread) {
                let Some(reader)=self.reader else { return; };
                if alternates.len()>=8 { alternates.pop_first(); }
                alternates.insert(thread.clone(),reader.thread(&thread));
            }
            sample_mode(&alternates[&thread],&turn,at)
        };
        if let Some(mode)=mode { payload.insert("service_tier".into(), serde_json::Value::String(mode)); }
    }
}
fn sample_mode(turns:&TurnSamples,turn:&str,at:DateTime<Utc>)->Option<String> {
    let samples=turns.get(turn)?;
    let last_at=samples.iter().filter(|(t,_)|*t<=at).map(|(t,_)|*t).max()?;
    let modes:BTreeSet<_>=samples.iter().filter(|(t,_)|*t==last_at).map(|(_,m)|m.as_str()).collect();
    (modes.len()==1).then(||(*modes.first().unwrap()).to_owned())
}

#[derive(Serialize)]
#[serde(rename_all="camelCase")]
pub(super) struct Correction { pub(super) event_id: String, pub(super) service_tier: String }
/// Best-effort bounded repair of already-uploaded events. The server never
/// inserts missing events through this path, so a quota reset cannot be undone.
pub(super) fn recent_corrections(home: &Path) -> Vec<Correction> {
    let reader = Reader::open(home);
    if reader.0.is_none() { return Vec::new(); }
    let Ok(mut files) = super::session_files(&home.join("sessions")) else { return Vec::new(); };
    files.sort_by_key(|p| std::cmp::Reverse(fs::metadata(p).and_then(|m| m.modified()).ok()));
    let mut remaining = 128 * 1024 * 1024u64;
    let mut result = BTreeMap::new(); let mut seen = BTreeSet::new();
    for file in files.into_iter().take(16) {
        let Ok(meta) = fs::symlink_metadata(&file) else { continue; };
        if !meta.file_type().is_file() || !meta.modified().ok().and_then(|t| t.elapsed().ok()).is_some_and(|age| age < Duration::from_secs(86400)) { continue; }
        let bytes = meta.len().min(32 * 1024 * 1024).min(remaining);
        remaining -= bytes; if bytes == 0 { break; }
        let Ok(mut input) = fs::File::open(&file) else { continue; };
        let start = meta.len().saturating_sub(bytes);
        if input.seek(SeekFrom::Start(start)).is_err() { continue; }
        let mut input = BufReader::new(input);
        let mut line=Vec::new();
        if start > 0 { let _ = input.read_until(b'\n', &mut line); }
        let timeline=reader.file(&file);
        loop {
            line.clear(); if !input.read_until(b'\n', &mut line).is_ok_and(|n| n > 0) { break; }
            if !line.ends_with(b"\n") || line.len() > super::MAX_RECORD_BYTES { continue; }
            let Ok(mut row) = serde_json::from_slice::<serde_json::Value>(&line) else { continue; };
            timeline.enrich(&mut row, &super::FileCursor::fresh());
            // Keep the original newline convention used by the live scanner.
            let raw = line.strip_suffix(b"\n").unwrap_or(&line); let raw = raw.strip_suffix(b"\r").unwrap_or(raw);
            if let Some(event) = super::parse_token_usage_record(&row, raw, None, false) {
                if let Some(tier) = event.service_tier.filter(|m| matches!(m.as_str(),"default"|"standard"|"fast"|"priority")) {
                    if let Ok(at)=DateTime::parse_from_rfc3339(&event.occurred_at) {
                        if seen.insert(event.event_id.clone()) {
                            result.insert((at.with_timezone(&Utc),event.event_id.clone()),Correction { event_id:event.event_id, service_tier:tier });
                            if result.len()>500 { result.pop_first(); }
                        }
                    }
                }
            }
        }
    }
    result.into_iter().rev().map(|(_,correction)|correction).collect()
}

#[cfg(test)]
mod tests {
    use super::*;
    const THREAD: &str="11111111-1111-4111-8111-111111111111";
    const TURN: &str="22222222-2222-4222-8222-222222222222";
    fn body(tier: &str) -> String { format!("thread_id={THREAD} run_turn{{turn_id={TURN}}}: tags_json={{\"service_tier\":\"{tier}\",\"unused\":\"private fixture\"}}") }
    fn usage(at: &str, id: &str) -> serde_json::Value { serde_json::json!({"timestamp":at,"type":"token_usage_record","payload":{"thread_id":THREAD,"turn_id":TURN,"response_id":id,"usage":{"total_tokens":10,"input_tokens":8,"output_tokens":2}}}) }
    #[test] fn matches_turn_and_event_time_including_a_speed_change() {
        let mut t=Timeline::default();
        t.thread=Some(THREAD.into());
        t.turns.insert(super::super::hex_digest(TURN.as_bytes()),vec![
            ("2026-10-26T01:00:00Z".parse().unwrap(),"priority".into()),
            ("2026-10-26T02:00:00Z".parse().unwrap(),"default".into())]);
        for (at,want) in [("2026-10-26T01:01:00Z","priority"),("2026-10-26T02:01:00Z","default")] {
            let mut row=usage(at,"one");t.enrich(&mut row,&super::super::FileCursor::fresh());
            assert_eq!(row["payload"]["service_tier"],want);
        }
        let mut early=usage("2026-10-26T00:59:00Z","one");t.enrich(&mut early,&super::super::FileCursor::fresh());assert!(early["payload"].get("service_tier").is_none());
        let mut other=usage("2026-10-26T02:01:00Z","one");other["payload"]["turn_id"]=serde_json::json!(THREAD);t.enrich(&mut other,&super::super::FileCursor::fresh());assert!(other["payload"].get("service_tier").is_none());
    }
    #[test] fn refuses_conflicting_tags_and_preserves_explicit_usage_modes() {
        assert_eq!(parse_tags(&body("priority"),THREAD).unwrap().1,"priority");
        assert!(parse_tags(&format!("turn_id={THREAD} {}",body("default")),THREAD).is_none());
        assert!(parse_tags(&body("default"),TURN).is_none());
        let mut t=Timeline::default();t.thread=Some(THREAD.into());let at="2026-10-26T01:00:00Z".parse().unwrap();
        t.turns.insert(super::super::hex_digest(TURN.as_bytes()),vec![(at,"priority".into()),(at,"default".into())]);
        let mut row=usage("2026-10-26T02:00:00Z","one");t.enrich(&mut row,&super::super::FileCursor::fresh());assert!(row["payload"].get("service_tier").is_none());
        row["payload"]["service_tier"]=serde_json::json!("fast");t.enrich(&mut row,&super::super::FileCursor::fresh());assert_eq!(row["payload"]["service_tier"],"fast");
    }
    #[test] fn scanner_and_metadata_repair_keep_the_original_event_identity() {
        let home=std::env::temp_dir().join(format!("cqg-speed-{}",uuid::Uuid::new_v4()));fs::create_dir_all(home.join("sessions")).unwrap();
        let conn=Connection::open(home.join("logs_2.sqlite")).unwrap();
        conn.execute_batch("create table logs(id integer primary key,ts integer,ts_nanos integer,thread_id text,target text,feedback_log_body text);").unwrap();
        conn.execute("insert into logs values(1,1792976400,0,?,'feedback_tags',?)",rusqlite::params![THREAD,body("priority")]).unwrap();drop(conn);
        let row=usage("2026-10-26T02:00:00Z","fixture-response");let raw=serde_json::to_vec(&row).unwrap();
        let expected=super::super::parse_direct_record(&raw).unwrap().event_id;
        let file=home.join("sessions").join(format!("rollout-{THREAD}.jsonl"));fs::write(&file,[raw.as_slice(),b"\n"].concat()).unwrap();
        let result=super::super::scan(&home,&super::super::ScanState::default()).unwrap();
        assert_eq!(result.events[0].service_tier.as_deref(),Some("priority"));assert_eq!(result.events[0].event_id,expected);
        let repair=recent_corrections(&home);assert_eq!(repair[0].event_id,expected);assert_eq!(repair[0].service_tier,"priority");
        fs::remove_dir_all(home).unwrap();
    }
    #[test] fn resumed_rollouts_use_the_event_thread_instead_of_the_file_uuid() {
        let home=std::env::temp_dir().join(format!("cqg-resumed-speed-{}",uuid::Uuid::new_v4()));fs::create_dir_all(home.join("sessions")).unwrap();
        let file_thread="33333333-3333-4333-8333-333333333333";
        let conn=Connection::open(home.join("logs_2.sqlite")).unwrap();
        conn.execute_batch("create table logs(id integer primary key,ts integer,ts_nanos integer,thread_id text,target text,feedback_log_body text);").unwrap();
        conn.execute("insert into logs values(1,1792976400,0,?,'feedback_tags',?)",rusqlite::params![THREAD,body("priority")]).unwrap();
        conn.execute("insert into logs values(2,1792976400,0,?,'feedback_tags',?)",rusqlite::params![file_thread,body("default").replace(THREAD,file_thread)]).unwrap();drop(conn);
        let mut row=usage("2026-10-26T02:00:00Z","resumed-response");row["payload"]["session_id"]=serde_json::json!(THREAD);
        let raw=serde_json::to_vec(&row).unwrap();let expected=super::super::parse_direct_record(&raw).unwrap().event_id;
        let file=home.join("sessions").join(format!("rollout-{file_thread}.jsonl"));fs::write(&file,[raw.as_slice(),b"\n"].concat()).unwrap();
        let result=super::super::scan(&home,&super::super::ScanState::default()).unwrap();
        assert_eq!(result.events[0].service_tier.as_deref(),Some("priority"));assert_eq!(result.events[0].event_id,expected);
        let repair=recent_corrections(&home);assert_eq!(repair[0].service_tier,"priority");assert_eq!(repair[0].event_id,expected);
        // The underlying session ID cannot override the explicit logical thread.
        row["payload"]["session_id"]=serde_json::json!(file_thread);
        Reader::open(&home).file(&file).enrich(&mut row,&super::super::FileCursor::fresh());assert_eq!(row["payload"]["service_tier"],"priority");
        row["payload"].as_object_mut().unwrap().remove("service_tier");
        row["payload"].as_object_mut().unwrap().remove("thread_id");row["payload"]["session_id"]=serde_json::json!(THREAD);
        Reader::open(&home).file(&file).enrich(&mut row,&super::super::FileCursor::fresh());assert_eq!(row["payload"]["service_tier"],"priority");
        fs::remove_dir_all(home).unwrap();
    }
}
