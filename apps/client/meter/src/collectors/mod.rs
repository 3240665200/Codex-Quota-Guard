//! Source adapters enrich usage metadata; accounting IDs, storage and reporting stay shared.
mod codex_cli;
mod codex_desktop;
pub(crate) mod codex_rollout;
mod diagnostic_store;
mod reconciliation;
pub(super) use reconciliation::recent_corrections;

use std::{collections::BTreeMap, path::Path};

pub(super) trait Adapter {
    fn enrich(&self, row: &mut serde_json::Value, cursor: &crate::FileCursor);
}
pub(super) trait Source {
    fn available(&self) -> bool;
    fn file(&self, path: &Path) -> Box<dyn Adapter + '_>;
}
pub(super) struct Reader {
    sources: Vec<Box<dyn Source>>,
}
impl Reader {
    pub(super) fn open(home: &Path) -> Self {
        Self {
            sources: vec![
                Box::new(codex_desktop::Source::open(home)),
                Box::new(codex_cli::Source::open(home)),
            ],
        }
    }
    fn available(&self) -> bool {
        self.sources.iter().any(|source| source.available())
    }
    pub(super) fn file(&self, path: &Path) -> Timeline<'_> {
        Timeline {
            adapters: self
                .sources
                .iter()
                .filter(|source| source.available())
                .map(|source| source.file(path))
                .collect(),
        }
    }
}
pub(super) struct Timeline<'a> {
    adapters: Vec<Box<dyn Adapter + 'a>>,
}
impl Timeline<'_> {
    pub(super) fn enrich(&self, row: &mut serde_json::Value, cursor: &crate::FileCursor) {
        let Some(payload) = row.get("payload").and_then(|p| p.as_object()) else {
            return;
        };
        if payload
            .get("service_tier")
            .or_else(|| payload.get("serviceTier"))
            .and_then(|v| v.as_str())
            .is_some_and(|tier| matches!(tier, "default" | "standard" | "fast" | "priority"))
        {
            return;
        }
        // Adapters see only routing metadata, never chat content or token counts.
        let routing: serde_json::Map<String, serde_json::Value> =
            ["thread_id", "session_id", "turn_id", "root_turn_id"]
                .into_iter()
                .filter_map(|key| payload.get(key).cloned().map(|value| (key.into(), value)))
                .collect();
        let mut candidates = BTreeMap::new();
        for adapter in &self.adapters {
            let mut probe = serde_json::json!({"type":row.get("type"),"timestamp":row.get("timestamp"),"payload":routing});
            adapter.enrich(&mut probe, cursor);
            if let Some(tier) = probe["payload"]["service_tier"].as_str() {
                let mode = match tier {
                    "priority" | "fast" => "fast",
                    "default" | "standard" => "standard",
                    _ => "unknown",
                };
                candidates
                    .entry(mode.to_owned())
                    .or_insert_with(|| tier.to_owned());
            }
        }
        if candidates.is_empty() {
            return;
        }
        let tier = if candidates.len() == 1 {
            candidates
                .into_values()
                .next()
                .unwrap_or_else(|| "unknown".into())
        } else {
            "unknown".into()
        };
        if let Some(payload) = row.get_mut("payload").and_then(|p| p.as_object_mut()) {
            payload.insert("service_tier".into(), serde_json::Value::String(tier));
        }
    }
}
impl Adapter for diagnostic_store::Timeline<'_> {
    fn enrich(&self, row: &mut serde_json::Value, cursor: &crate::FileCursor) {
        Self::enrich(self, row, cursor);
    }
}

pub(crate) fn run_cli(arguments: &[std::ffi::OsString]) -> Result<i32, String> {
    codex_cli::run(arguments)
}

#[cfg(test)]
mod tests {
    use super::*;
    struct Fixed(&'static str);
    impl Adapter for Fixed {
        fn enrich(&self, row: &mut serde_json::Value, _: &crate::FileCursor) {
            assert!(row["payload"].get("usage").is_none());
            assert!(row["payload"].get("prompt").is_none());
            row["payload"]["service_tier"] = serde_json::json!(self.0);
        }
    }
    #[test]
    fn independent_sources_must_agree_and_cannot_override_explicit_usage_evidence() {
        let mut row = serde_json::json!({"type":"token_usage_record","timestamp":"2026-10-27T09:00:00Z","payload":{"usage":{"total_tokens":10},"prompt":"not-visible-to-adapters"}});
        let adapters = Timeline {
            adapters: vec![Box::new(Fixed("priority")), Box::new(Fixed("standard"))],
        };
        adapters.enrich(&mut row, &crate::FileCursor::fresh());
        assert_eq!(row["payload"]["service_tier"], "unknown");
        row["payload"]["service_tier"] = serde_json::json!("default");
        adapters.enrich(&mut row, &crate::FileCursor::fresh());
        assert_eq!(row["payload"]["service_tier"], "default");
        row["payload"]
            .as_object_mut()
            .unwrap()
            .remove("service_tier");
        Timeline {
            adapters: vec![Box::new(Fixed("priority")), Box::new(Fixed("fast"))],
        }
        .enrich(&mut row, &crate::FileCursor::fresh());
        assert_eq!(row["payload"]["service_tier"], "priority");
    }
}
