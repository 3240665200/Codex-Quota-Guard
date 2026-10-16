use super::*;
use serde_json::json;

const PROXY_CONFIG: &str = r#"model_provider = "custom"
model = "keep-current-model"
approval_policy = "never"

[model_providers.custom]
name = "CC Switch"
base_url = "http://127.0.0.1:15721/v1"
wire_api = "responses"
experimental_bearer_token = "PROXY_MANAGED"

# This user-owned server must survive restoration.
[mcp_servers.keep]
command = "keep-this-command"
"#;

const DORMANT_CONFIG: &str = r#"model = "keep-current-model"
approval_policy = "never"

[model_providers.custom]
name = "custom"
base_url = "http://127.0.0.1:15721/v1"
wire_api = "responses"
experimental_bearer_token = "PROXY_MANAGED"

[mcp_servers.keep]
command = "keep-this-command"
"#;

const DIRECT_CONFIG: &str = r#"model_provider = "upstream"
model = "old-model-must-not-be-restored"

[model_providers.upstream]
name = "Example upstream"
base_url = "https://upstream.example/v1"
wire_api = "responses"
experimental_bearer_token = "fixture-api-key"
requires_openai_auth = false
"#;

// These are synthetic values, never credentials copied from the user's files.
const OAUTH: &[u8] = b"{\n  \"auth_mode\": \"chatgpt\",\n  \"OPENAI_API_KEY\": null,\n  \"tokens\": {\"access_token\": \"fresh-fixture-access\", \"refresh_token\": \"fresh-fixture-refresh\", \"account_id\": \"fixture-account\"},\n  \"last_refresh\": \"2026-10-14T00:00:00Z\"\n}\n";
const PLACEHOLDER_AUTH: &[u8] =
    b"{\"OPENAI_API_KEY\":\"PROXY_MANAGED\",\"auth_mode\":\"apikey\",\"unrelated\":true}";

struct Fixture {
    root: PathBuf,
}

impl Fixture {
    fn new(config: &str, auth: Option<&[u8]>) -> Self {
        let root = std::env::temp_dir().join(format!(
            "quota-guard-codex-restore-test-{}",
            uuid::Uuid::new_v4()
        ));
        assert!(root.is_absolute());
        fs::create_dir_all(root.join(".codex")).unwrap();
        fs::create_dir_all(root.join(".cc-switch")).unwrap();
        let fixture = Self { root };
        fs::write(fixture.config(), config).unwrap();
        if let Some(auth) = auth {
            fs::write(fixture.auth(), auth).unwrap();
        }
        fixture
            .db()
            .execute_batch(
                "CREATE TABLE proxy_config (
                app_type TEXT PRIMARY KEY, listen_address TEXT, listen_port INTEGER,
                enabled INTEGER NOT NULL DEFAULT 0,
                live_takeover_active INTEGER NOT NULL DEFAULT 0
             );
             INSERT INTO proxy_config (app_type, listen_address, listen_port)
             VALUES ('codex', '127.0.0.1', 15721);
             CREATE TABLE proxy_live_backup (
                app_type TEXT PRIMARY KEY, original_config TEXT NOT NULL, backed_up_at TEXT
             );
             CREATE TABLE providers (
                id TEXT NOT NULL, app_type TEXT NOT NULL, settings_config TEXT NOT NULL,
                category TEXT, meta TEXT NOT NULL DEFAULT '{}', is_current INTEGER NOT NULL,
                PRIMARY KEY (id, app_type)
             );",
            )
            .unwrap();
        fixture
    }

    fn config(&self) -> PathBuf {
        self.root.join(".codex/config.toml")
    }
    fn auth(&self) -> PathBuf {
        self.root.join(".codex/auth.json")
    }
    fn db(&self) -> Connection {
        Connection::open(self.root.join(".cc-switch/cc-switch.db")).unwrap()
    }
    fn config_bytes(&self) -> Vec<u8> {
        fs::read(self.config()).unwrap()
    }
    fn parsed_config(&self) -> DocumentMut {
        parse_config(&self.config_bytes()).unwrap()
    }
    fn backup(&self, settings: Json) {
        self.db()
            .execute(
                "INSERT OR REPLACE INTO proxy_live_backup VALUES ('codex', ?1, 'fixture-time')",
                [settings.to_string()],
            )
            .unwrap();
    }
    fn provider(&self, id: &str, current: bool, settings: Json) {
        self.db()
            .execute(
                "INSERT INTO providers (id, app_type, settings_config, category, is_current)
             VALUES (?1, 'codex', ?2, ?3, ?4)",
                rusqlite::params![
                    id,
                    settings.to_string(),
                    if id == "codex-official" {
                        Some("official")
                    } else {
                        None
                    },
                    current
                ],
            )
            .unwrap();
    }
    fn select(&self, id: &str) {
        fs::write(
            self.root.join(".cc-switch/settings.json"),
            json!({"currentProviderCodex": id}).to_string(),
        )
        .unwrap();
    }
    fn official(&self) {
        self.provider(
            "codex-official",
            true,
            json!({"config": "model = \"old-model\"\n", "auth": {}}),
        );
        self.select("codex-official");
    }
}

impl Drop for Fixture {
    fn drop(&mut self) {
        // Every recursively removed path is this fixture's unique temporary
        // directory, never a home/configuration directory supplied by the OS.
        assert!(self.root.is_absolute());
        assert_eq!(self.root.parent(), Some(std::env::temp_dir().as_path()));
        assert!(self
            .root
            .file_name()
            .unwrap()
            .to_string_lossy()
            .starts_with("quota-guard-codex-restore-test-"));
        let _ = fs::remove_dir_all(&self.root);
    }
}

fn assert_mirror(doc: &DocumentMut, id: &str) {
    let provider = &doc["model_providers"][id];
    assert_eq!(provider["name"].as_str(), Some("OpenAI"));
    assert_eq!(provider["wire_api"].as_str(), Some("responses"));
    assert_eq!(provider["requires_openai_auth"].as_bool(), Some(true));
    assert_eq!(provider["supports_websockets"].as_bool(), Some(true));
    for field in [
        "base_url",
        "experimental_bearer_token",
        "env_key",
        "http_headers",
    ] {
        assert!(
            provider.get(field).is_none(),
            "unexpected mirror field {field}"
        );
    }
}

#[test]
fn conflict_snapshot_requires_selected_provider_active_takeover_and_proxy_only_auth() {
    let fixture = Fixture::new(PROXY_CONFIG, Some(PLACEHOLDER_AUTH));
    assert!(conflict_snapshot_at(&fixture.root).unwrap().is_none());
    fixture
        .db()
        .execute("UPDATE proxy_config SET live_takeover_active = 1", [])
        .unwrap();
    let snapshot = conflict_snapshot_at(&fixture.root).unwrap().unwrap();
    assert!(snapshot.matches_current(&fixture.root.join(".codex")));
    fs::write(fixture.auth(), OAUTH).unwrap();
    assert!(!snapshot.matches_current(&fixture.root.join(".codex")));
    assert!(conflict_snapshot_at(&fixture.root).unwrap().is_none());
}

#[test]
fn dormant_or_other_proxy_and_ambiguous_profiles_never_prove_cc_conflict() {
    for config in [
        DORMANT_CONFIG.to_owned(),
        PROXY_CONFIG.replace("15721", "15722"),
        PROXY_CONFIG.replace("127.0.0.1", "[::1]"),
        PROXY_CONFIG.replace("http://127.0.0.1", "https://127.0.0.1"),
        PROXY_CONFIG.replace(
            "http://127.0.0.1:15721/v1",
            "http://127.0.0.1:15721/v1?other=true",
        ),
        format!("{PROXY_CONFIG}\n[profiles.other]\nmodel_provider = \"custom\"\n"),
    ] {
        let fixture = Fixture::new(&config, Some(PLACEHOLDER_AUTH));
        fixture
            .db()
            .execute("UPDATE proxy_config SET live_takeover_active = 1", [])
            .unwrap();
        assert!(conflict_snapshot_at(&fixture.root).unwrap().is_none());
    }
}

#[test]
fn conflict_snapshot_rejects_a_changed_cc_database_or_wal() {
    let fixture = Fixture::new(PROXY_CONFIG, Some(PLACEHOLDER_AUTH));
    fixture
        .db()
        .execute("UPDATE proxy_config SET live_takeover_active = 1", [])
        .unwrap();
    let before = conflict_snapshot_at(&fixture.root).unwrap().unwrap();
    fixture
        .db()
        .execute("UPDATE proxy_config SET listen_port = 15722", [])
        .unwrap();
    assert!(!before.matches_current(&fixture.root.join(".codex")));
    fixture
        .db()
        .execute("UPDATE proxy_config SET listen_port = 15721", [])
        .unwrap();
    let before = conflict_snapshot_at(&fixture.root).unwrap().unwrap();
    fs::write(
        fixture.root.join(".cc-switch/cc-switch.db-wal"),
        b"synthetic-change",
    )
    .unwrap();
    assert!(!before.matches_current(&fixture.root.join(".codex")));
}

#[test]
fn backup_restores_third_party_connection_without_reverting_user_settings() {
    let fixture = Fixture::new(PROXY_CONFIG, Some(PLACEHOLDER_AUTH));
    fixture.official(); // The live backup takes precedence over provider rows.
    fixture.backup(json!({"config": DIRECT_CONFIG,
        "auth": {"OPENAI_API_KEY": "fixture-api-key", "auth_mode": "apikey"}}));
    let before_db = fs::read(fixture.root.join(".cc-switch/cc-switch.db")).unwrap();

    assert_eq!(restore_at(&fixture.root), Ok(RestoreOutcome::Restored));
    let doc = fixture.parsed_config();
    assert_eq!(doc["model_provider"].as_str(), Some("upstream"));
    assert_eq!(
        doc["model_providers"]["custom"]["base_url"].as_str(),
        Some("https://upstream.example/v1")
    );
    assert_eq!(
        doc["model_providers"]["upstream"]["experimental_bearer_token"].as_str(),
        Some("fixture-api-key")
    );
    assert_eq!(doc["model"].as_str(), Some("keep-current-model"));
    assert_eq!(doc["approval_policy"].as_str(), Some("never"));
    assert_eq!(
        doc["mcp_servers"]["keep"]["command"].as_str(),
        Some("keep-this-command")
    );
    assert!(String::from_utf8(fixture.config_bytes())
        .unwrap()
        .contains("# This user-owned server must survive restoration."));
    let auth: Json = serde_json::from_slice(&fs::read(fixture.auth()).unwrap()).unwrap();
    assert_eq!(auth["OPENAI_API_KEY"], "fixture-api-key");
    assert_eq!(auth["unrelated"], true);
    assert_eq!(
        fs::read(fixture.root.join(".cc-switch/cc-switch.db")).unwrap(),
        before_db
    );
    assert_eq!(restore_at(&fixture.root), Ok(RestoreOutcome::Unchanged));
}

#[test]
fn official_fallback_repairs_dormant_route_and_preserves_oauth_bytes() {
    let fixture = Fixture::new(DORMANT_CONFIG, Some(OAUTH));
    fixture.provider(
        "codex-official",
        false,
        json!({"config": "", "auth": {
            "tokens": {"access_token": "stale-backup", "refresh_token": "stale-backup"}
        }}),
    );
    fixture.provider("other-provider", true, json!({"config": DIRECT_CONFIG}));
    fixture.select("codex-official");

    assert_eq!(restore_at(&fixture.root), Ok(RestoreOutcome::Restored));
    let doc = fixture.parsed_config();
    assert!(doc.get("model_provider").is_none());
    assert_mirror(&doc, "custom");
    assert_eq!(fs::read(fixture.auth()).unwrap(), OAUTH);
    assert_eq!(
        doc["mcp_servers"]["keep"]["command"].as_str(),
        Some("keep-this-command")
    );
}

#[test]
fn missing_local_selection_falls_back_to_the_unique_current_provider() {
    let fixture = Fixture::new(PROXY_CONFIG, Some(OAUTH));
    fixture.official();
    fixture.select("deleted-provider");
    assert_eq!(restore_at(&fixture.root), Ok(RestoreOutcome::Restored));
    assert_mirror(&fixture.parsed_config(), "custom");
    assert_eq!(fs::read(fixture.auth()).unwrap(), OAUTH);
}

#[test]
fn unknown_localhost_service_is_untouched() {
    let config = PROXY_CONFIG
        .replace("15721", "15722")
        .replace("PROXY_MANAGED", "user-local-key");
    let fixture = Fixture::new(&config, Some(OAUTH));
    fixture.official();
    assert_eq!(restore_at(&fixture.root), Ok(RestoreOutcome::Unchanged));
    assert_eq!(fixture.config_bytes(), config.as_bytes());
    assert_eq!(fs::read(fixture.auth()).unwrap(), OAUTH);
}

#[test]
fn matching_port_alone_does_not_prove_cc_switch_ownership() {
    let config = PROXY_CONFIG
        .replace("custom", "user-service")
        .replace("CC Switch", "User service")
        .replace("PROXY_MANAGED", "user-local-key");
    let fixture = Fixture::new(&config, Some(OAUTH));
    fixture.official();
    assert_eq!(restore_at(&fixture.root), Ok(RestoreOutcome::Unchanged));
    assert_eq!(fixture.config_bytes(), config.as_bytes());
    assert_eq!(fs::read(fixture.auth()).unwrap(), OAUTH);
}

#[test]
fn backup_that_is_still_a_proxy_is_rejected_without_writes() {
    let fixture = Fixture::new(PROXY_CONFIG, Some(PLACEHOLDER_AUTH));
    fixture.backup(json!({"config": PROXY_CONFIG, "auth": {"OPENAI_API_KEY": "PROXY_MANAGED"}}));
    assert_eq!(restore_at(&fixture.root), Err(NO_BACKUP));
    assert_eq!(fixture.config_bytes(), PROXY_CONFIG.as_bytes());
    assert_eq!(fs::read(fixture.auth()).unwrap(), PLACEHOLDER_AUTH);
}

#[test]
fn profile_routes_keep_repaired_alias_and_unrelated_preferences() {
    let config = format!("openai_base_url = \"http://127.0.0.1:15721/v1\"\n{DORMANT_CONFIG}\n[profiles.work]\nmodel_provider = \"custom\"\nopenai_base_url = \"http://127.0.0.1:15721/v1\"\nmodel = \"profile-model\"\n[profiles.other]\nmodel_provider = \"ollama\"\n");
    let fixture = Fixture::new(&config, Some(OAUTH));
    fixture.official();
    fixture
        .db()
        .execute(
            "UPDATE proxy_config SET live_takeover_active=1 WHERE app_type='codex'",
            [],
        )
        .unwrap();

    assert_eq!(restore_at(&fixture.root), Ok(RestoreOutcome::Restored));
    let doc = fixture.parsed_config();
    assert!(doc.get("openai_base_url").is_none());
    assert!(doc["profiles"]["work"].get("openai_base_url").is_none());
    assert_eq!(
        doc["profiles"]["work"]["model_provider"].as_str(),
        Some("custom")
    );
    assert_eq!(
        doc["profiles"]["work"]["model"].as_str(),
        Some("profile-model")
    );
    assert_eq!(
        doc["profiles"]["other"]["model_provider"].as_str(),
        Some("ollama")
    );
    assert_mirror(&doc, "custom");
    assert_eq!(fs::read(fixture.auth()).unwrap(), OAUTH);
}

#[test]
fn root_proxy_url_without_a_provider_table_restores_the_third_party_route() {
    let config =
        "openai_base_url = \"http://127.0.0.1:15721/v1\"\nmodel = \"keep-current-model\"\n";
    let fixture = Fixture::new(config, Some(OAUTH));
    fixture
        .db()
        .execute(
            "UPDATE proxy_config SET live_takeover_active=1 WHERE app_type='codex'",
            [],
        )
        .unwrap();
    fixture.backup(json!({"config": DIRECT_CONFIG, "auth": {"OPENAI_API_KEY": "fixture-api-key"}}));

    assert_eq!(restore_at(&fixture.root), Ok(RestoreOutcome::Restored));
    let doc = fixture.parsed_config();
    assert!(doc.get("openai_base_url").is_none());
    assert_eq!(doc["model_provider"].as_str(), Some("upstream"));
    assert_eq!(
        doc["model_providers"]["upstream"]["base_url"].as_str(),
        Some("https://upstream.example/v1")
    );
    assert_eq!(doc["model"].as_str(), Some("keep-current-model"));
    assert_eq!(fs::read(fixture.auth()).unwrap(), OAUTH);
}

#[test]
fn inline_provider_table_survives_recovery_serialization() {
    let config = "model_provider = \"custom\"\nmodel_providers = { custom = { base_url = \"http://127.0.0.1:15721/v1\", experimental_bearer_token = \"PROXY_MANAGED\" } }\n";
    let fixture = Fixture::new(config, Some(OAUTH));
    fixture.official();
    assert_eq!(restore_at(&fixture.root), Ok(RestoreOutcome::Restored));
    assert_mirror(&fixture.parsed_config(), "custom");
    assert_eq!(fs::read(fixture.auth()).unwrap(), OAUTH);
}

#[test]
fn dormant_route_ownership_does_not_claim_an_unrelated_profile_url() {
    let config = format!("{DORMANT_CONFIG}\n[profiles.user_owned]\nopenai_base_url = \"http://127.0.0.1:15721/v1\"\nmodel = \"profile-model\"\n");
    let fixture = Fixture::new(&config, Some(OAUTH));
    fixture.official(); // takeover remains false; only the custom table has a marker.

    assert_eq!(restore_at(&fixture.root), Ok(RestoreOutcome::Restored));
    let doc = fixture.parsed_config();
    assert_mirror(&doc, "custom");
    assert_eq!(
        doc["profiles"]["user_owned"]["openai_base_url"].as_str(),
        Some("http://127.0.0.1:15721/v1")
    );
    assert!(doc["profiles"]["user_owned"]
        .get("model_provider")
        .is_none());
    assert_eq!(
        doc["profiles"]["user_owned"]["model"].as_str(),
        Some("profile-model")
    );
    assert_eq!(fs::read(fixture.auth()).unwrap(), OAUTH);
}

#[test]
fn conflicting_unowned_destination_provider_is_not_overwritten() {
    let config = format!("{PROXY_CONFIG}\n[model_providers.upstream]\nname = \"User modified endpoint\"\nbase_url = \"https://user-modified.example/v1\"\nwire_api = \"responses\"\nexperimental_bearer_token = \"user-key\"\n");
    let fixture = Fixture::new(&config, Some(OAUTH));
    fixture.backup(json!({"config": DIRECT_CONFIG, "auth": {"OPENAI_API_KEY": "fixture-api-key"}}));

    assert_eq!(restore_at(&fixture.root), Err(CHANGED));
    assert_eq!(fixture.config_bytes(), config.as_bytes());
    assert_eq!(fs::read(fixture.auth()).unwrap(), OAUTH);
}

#[test]
fn profile_with_only_proxy_url_restores_the_source_global_provider() {
    let config = "model = \"keep-current-model\"\n[profiles.work]\nopenai_base_url = \"http://127.0.0.1:15721/v1\"\nmodel = \"profile-model\"\n";
    let fixture = Fixture::new(config, Some(OAUTH));
    fixture
        .db()
        .execute(
            "UPDATE proxy_config SET live_takeover_active=1 WHERE app_type='codex'",
            [],
        )
        .unwrap();
    fixture.backup(json!({"config": DIRECT_CONFIG, "auth": {"OPENAI_API_KEY": "fixture-api-key"}}));

    assert_eq!(restore_at(&fixture.root), Ok(RestoreOutcome::Restored));
    let doc = fixture.parsed_config();
    assert!(doc.get("model_provider").is_none());
    assert!(doc["profiles"]["work"].get("openai_base_url").is_none());
    assert_eq!(
        doc["profiles"]["work"]["model_provider"].as_str(),
        Some("upstream")
    );
    assert_eq!(
        doc["model_providers"]["upstream"]["base_url"].as_str(),
        Some("https://upstream.example/v1")
    );
    assert_eq!(
        doc["profiles"]["work"]["model"].as_str(),
        Some("profile-model")
    );
    assert_eq!(fs::read(fixture.auth()).unwrap(), OAUTH);
}

#[test]
fn legacy_third_party_api_key_is_projected_without_overwriting_oauth() {
    let fixture = Fixture::new(PROXY_CONFIG, Some(OAUTH));
    let config = DIRECT_CONFIG.replace("experimental_bearer_token = \"fixture-api-key\"\n", "");
    fixture.backup(json!({"config": config, "auth": {"OPENAI_API_KEY": "fixture-api-key"}}));
    assert_eq!(restore_at(&fixture.root), Ok(RestoreOutcome::Restored));
    let doc = fixture.parsed_config();
    for id in ["custom", "upstream"] {
        assert_eq!(
            doc["model_providers"][id]["experimental_bearer_token"].as_str(),
            Some("fixture-api-key")
        );
    }
    assert_eq!(fs::read(fixture.auth()).unwrap(), OAUTH);
}

#[test]
fn ordinary_http_headers_do_not_suppress_legacy_api_key_projection() {
    let fixture = Fixture::new(PROXY_CONFIG, Some(OAUTH));
    let config = DIRECT_CONFIG.replace(
        "experimental_bearer_token = \"fixture-api-key\"\n",
        "http_headers = { \"X-Client\" = \"fixture-client\" }\n",
    );
    fixture.backup(json!({"config": config, "auth": {"OPENAI_API_KEY": "fixture-api-key"}}));
    assert_eq!(restore_at(&fixture.root), Ok(RestoreOutcome::Restored));
    let doc = fixture.parsed_config();
    for id in ["custom", "upstream"] {
        assert_eq!(
            doc["model_providers"][id]["experimental_bearer_token"].as_str(),
            Some("fixture-api-key")
        );
        assert_eq!(
            doc["model_providers"][id]["http_headers"]["X-Client"].as_str(),
            Some("fixture-client")
        );
    }
    assert_eq!(fs::read(fixture.auth()).unwrap(), OAUTH);
}

#[test]
fn ambiguous_default_openai_with_a_third_party_url_is_rejected() {
    let fixture = Fixture::new(PROXY_CONFIG, Some(OAUTH));
    fixture.backup(
        json!({"config": "openai_base_url = \"https://third-party.example/v1\"\n", "auth": {}}),
    );
    assert_eq!(restore_at(&fixture.root), Err(NO_BACKUP));
    assert_eq!(fixture.config_bytes(), PROXY_CONFIG.as_bytes());
    assert_eq!(fs::read(fixture.auth()).unwrap(), OAUTH);
}

#[test]
fn non_string_source_selector_is_rejected_without_writes() {
    let fixture = Fixture::new(PROXY_CONFIG, Some(OAUTH));
    fixture.backup(json!({"config": "model_provider = 123\n", "auth": {}}));
    assert_eq!(restore_at(&fixture.root), Err(NO_BACKUP));
    assert_eq!(fixture.config_bytes(), PROXY_CONFIG.as_bytes());
    assert_eq!(fs::read(fixture.auth()).unwrap(), OAUTH);
}

#[test]
fn source_provider_must_be_a_table() {
    let fixture = Fixture::new(PROXY_CONFIG, Some(OAUTH));
    fixture.backup(json!({"config": "model_provider = \"custom\"\n[model_providers]\ncustom = \"bad\"\n", "auth": {}}));
    assert_eq!(restore_at(&fixture.root), Err(NO_BACKUP));
    assert_eq!(fixture.config_bytes(), PROXY_CONFIG.as_bytes());
    assert_eq!(fs::read(fixture.auth()).unwrap(), OAUTH);
}

#[test]
fn official_api_key_backup_can_restore_a_missing_auth_file() {
    let fixture = Fixture::new(PROXY_CONFIG, None);
    fixture.backup(json!({"config": "", "auth": {
        "OPENAI_API_KEY": "official-fixture-api-key", "auth_mode": "apikey"
    }}));
    assert_eq!(restore_at(&fixture.root), Ok(RestoreOutcome::Restored));
    let auth: Json = serde_json::from_slice(&fs::read(fixture.auth()).unwrap()).unwrap();
    assert_eq!(auth["OPENAI_API_KEY"], "official-fixture-api-key");
    assert_eq!(auth["auth_mode"], "apikey");
    assert_mirror(&fixture.parsed_config(), "custom");
}

#[test]
fn missing_oauth_is_not_resurrected_from_a_stale_backup() {
    let fixture = Fixture::new(PROXY_CONFIG, None);
    fixture.backup(json!({"config": "", "auth": serde_json::from_slice::<Json>(OAUTH).unwrap()}));
    assert_eq!(restore_at(&fixture.root), Err(NO_BACKUP));
    assert_eq!(fixture.config_bytes(), PROXY_CONFIG.as_bytes());
    assert!(!fixture.auth().exists());
}

#[test]
fn official_restore_clears_only_placeholder_key_from_current_oauth() {
    let mut auth: Json = serde_json::from_slice(OAUTH).unwrap();
    auth["OPENAI_API_KEY"] = json!("PROXY_MANAGED");
    let fixture = Fixture::new(PROXY_CONFIG, Some(&serde_json::to_vec(&auth).unwrap()));
    fixture.official();
    assert_eq!(restore_at(&fixture.root), Ok(RestoreOutcome::Restored));
    let restored: Json = serde_json::from_slice(&fs::read(fixture.auth()).unwrap()).unwrap();
    auth["OPENAI_API_KEY"] = Json::Null;
    assert_eq!(restored, auth);
}

#[test]
fn invalid_auth_stops_before_configuration_is_written() {
    let fixture = Fixture::new(PROXY_CONFIG, Some(b"not json"));
    fixture.official();
    assert_eq!(restore_at(&fixture.root), Err(INVALID));
    assert_eq!(fixture.config_bytes(), PROXY_CONFIG.as_bytes());
    assert_eq!(fs::read(fixture.auth()).unwrap(), b"not json");
}

#[test]
fn a_directory_instead_of_config_is_a_read_error() {
    let fixture = Fixture::new(PROXY_CONFIG, Some(OAUTH));
    fs::remove_file(fixture.config()).unwrap();
    fs::create_dir(fixture.config()).unwrap();
    assert_eq!(restore_at(&fixture.root), Err(INVALID));
    assert!(fixture.config().is_dir());
    assert_eq!(fs::read(fixture.auth()).unwrap(), OAUTH);
}

#[test]
fn concurrent_configuration_change_is_detected_before_any_write() {
    let fixture = Fixture::new(PROXY_CONFIG, Some(OAUTH));
    fs::write(fixture.config(), b"model = \"external-edit\"\n").unwrap();
    let mut writes = 0;
    let result = commit_pair(
        &fixture.config(),
        PROXY_CONFIG.as_bytes(),
        b"replacement",
        &fixture.auth(),
        Some(OAUTH),
        Some(b"new-auth"),
        |_, _, _| {
            writes += 1;
            Ok(())
        },
    );
    assert_eq!(result, Err(CHANGED));
    assert_eq!(writes, 0);
    assert_eq!(fixture.config_bytes(), b"model = \"external-edit\"\n");
    assert_eq!(fs::read(fixture.auth()).unwrap(), OAUTH);
}

#[test]
fn auth_write_failure_does_not_change_configuration() {
    let fixture = Fixture::new(PROXY_CONFIG, Some(OAUTH));
    let mut writes = 0;
    let result = commit_pair(
        &fixture.config(),
        PROXY_CONFIG.as_bytes(),
        b"replacement",
        &fixture.auth(),
        Some(OAUTH),
        Some(b"new-auth"),
        |path, _, _| {
            writes += 1;
            assert_eq!(path, fixture.auth());
            Err(WRITE_FAILED)
        },
    );
    assert_eq!(result, Err(WRITE_FAILED));
    assert_eq!(writes, 1);
    assert_eq!(fixture.config_bytes(), PROXY_CONFIG.as_bytes());
    assert_eq!(fs::read(fixture.auth()).unwrap(), OAUTH);
}

#[test]
fn second_file_write_failure_rolls_back_existing_auth() {
    let fixture = Fixture::new(PROXY_CONFIG, Some(OAUTH));
    let mut writes = Vec::new();
    let result = commit_pair(
        &fixture.config(),
        PROXY_CONFIG.as_bytes(),
        b"replacement",
        &fixture.auth(),
        Some(OAUTH),
        Some(b"new-auth"),
        |path, expected, bytes| {
            writes.push(path.to_path_buf());
            if path == fixture.config() {
                Err(WRITE_FAILED)
            } else {
                replace_checked(path, expected, bytes)
            }
        },
    );
    assert_eq!(result, Err(WRITE_FAILED));
    assert_eq!(
        writes,
        vec![fixture.auth(), fixture.config(), fixture.auth()]
    );
    assert_eq!(fixture.config_bytes(), PROXY_CONFIG.as_bytes());
    assert_eq!(fs::read(fixture.auth()).unwrap(), OAUTH);
}

#[test]
fn second_file_write_failure_removes_only_newly_created_auth() {
    let fixture = Fixture::new(PROXY_CONFIG, None);
    let result = commit_pair(
        &fixture.config(),
        PROXY_CONFIG.as_bytes(),
        b"replacement",
        &fixture.auth(),
        None,
        Some(b"new-auth"),
        |path, expected, bytes| {
            if path == fixture.config() {
                Err(WRITE_FAILED)
            } else {
                replace_checked(path, expected, bytes)
            }
        },
    );
    assert_eq!(result, Err(WRITE_FAILED));
    assert!(!fixture.auth().exists());
    assert_eq!(fixture.config_bytes(), PROXY_CONFIG.as_bytes());
}

#[test]
fn rollback_does_not_overwrite_a_concurrent_auth_refresh() {
    let fixture = Fixture::new(PROXY_CONFIG, Some(OAUTH));
    let result = commit_pair(
        &fixture.config(),
        PROXY_CONFIG.as_bytes(),
        b"replacement",
        &fixture.auth(),
        Some(OAUTH),
        Some(b"new-auth"),
        |path, expected, bytes| {
            if path == fixture.config() {
                fs::write(fixture.auth(), b"external-auth-refresh").unwrap();
                Err(WRITE_FAILED)
            } else {
                replace_checked(path, expected, bytes)
            }
        },
    );
    assert_eq!(result, Err(ROLLBACK_FAILED));
    assert_eq!(fixture.config_bytes(), PROXY_CONFIG.as_bytes());
    assert_eq!(fs::read(fixture.auth()).unwrap(), b"external-auth-refresh");
}

#[test]
fn replace_checked_refuses_a_changed_file_without_leaving_temporary_files() {
    let fixture = Fixture::new(PROXY_CONFIG, Some(OAUTH));
    assert_eq!(
        replace_checked(&fixture.config(), Some(b"stale-read"), b"replacement"),
        Err(CHANGED)
    );
    assert_eq!(fixture.config_bytes(), PROXY_CONFIG.as_bytes());
    assert!(!fs::read_dir(fixture.root.join(".codex"))
        .unwrap()
        .any(|entry| {
            entry
                .unwrap()
                .file_name()
                .to_string_lossy()
                .starts_with(".quota-guard-restore-")
        }));
}
