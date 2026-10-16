//! Restore only CC Switch-owned connection settings after the guard has
//! confirmed its exit. Never replace the user's whole config or roll back OAuth
//! tokens, and never write to the CC Switch database.
use crate::conflict_evidence::ConflictSnapshot;
use reqwest::Url;
use rusqlite::{Connection, OpenFlags, OptionalExtension};
use serde_json::Value as Json;
use std::{
    fs::{self, OpenOptions},
    io::{Read, Write},
    path::{Path, PathBuf},
    time::Duration,
};
use toml_edit::{value, DocumentMut, Item, Table};

const MAX_FILE: u64 = 4 * 1024 * 1024;
const INVALID: &str = "无法安全读取 Codex 连接配置，未自动恢复。请检查配置文件后重新检查。";
const NO_BACKUP: &str = "未找到可用的 CC Switch 原连接备份，未猜测服务地址或凭据。请退出 Quota Guard，在 CC Switch 中恢复直连后重试。";
const CHANGED: &str = "Codex 配置正在被其他程序修改，已停止恢复。请关闭其他配置编辑器后重新检查。";
const WRITE_FAILED: &str = "无法保存 Codex 连接配置，请检查文件权限后重新检查。";
const ROLLBACK_FAILED: &str =
    "Codex 连接恢复未完成，凭据回退也未完成。请退出 Quota Guard，在 CC Switch 中恢复直连后重试。";
const CUSTOM_PATH: &str = "检测到自定义 Codex 或 CC Switch 目录，暂不自动恢复。请退出 Quota Guard，在 CC Switch 中恢复直连后重试。";

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum RestoreOutcome {
    Unchanged,
    Restored,
}

pub(crate) fn restore() -> Result<RestoreOutcome, &'static str> {
    #[cfg(windows)]
    {
        restore_at(&default_profile()?)
    }
    #[cfg(not(windows))]
    {
        Ok(RestoreOutcome::Unchanged)
    }
}

#[cfg(windows)]
fn default_profile() -> Result<PathBuf, &'static str> {
    let profile = known_folder(&windows_sys::Win32::UI::Shell::FOLDERID_Profile)?;
    let roaming = known_folder(&windows_sys::Win32::UI::Shell::FOLDERID_RoamingAppData)?;
    let paths = roaming.join("com.ccswitch.desktop/app_paths.json");
    if let Some(bytes) = read_optional(&paths)? {
        let paths: Json = serde_json::from_slice(&bytes).map_err(|_| CUSTOM_PATH)?;
        if paths
            .get("app_config_dir_override")
            .and_then(Json::as_str)
            .is_some_and(|path| !path.is_empty() && Path::new(path) != profile.join(".cc-switch"))
        {
            return Err(CUSTOM_PATH);
        }
    }
    if std::env::var_os("CODEX_HOME")
        .is_some_and(|path| !path.is_empty() && Path::new(&path) != profile.join(".codex"))
    {
        return Err(CUSTOM_PATH);
    }
    Ok(profile)
}

/// Capture before the official RPC, then require exactly the same snapshot
/// after its dedicated conflict response. Failure or ambiguity grants nothing.
pub(crate) fn conflict_snapshot() -> Option<ConflictSnapshot> {
    #[cfg(windows)]
    {
        conflict_snapshot_at(&default_profile().ok()?)
            .ok()
            .flatten()
    }
    #[cfg(not(windows))]
    {
        None
    }
}

fn conflict_snapshot_at(profile: &Path) -> Result<Option<ConflictSnapshot>, &'static str> {
    let codex = profile.join(".codex");
    let Some(before) = ConflictSnapshot::capture(&codex) else {
        return Ok(None);
    };
    let Some(config) = read_optional(&codex.join("config.toml"))? else {
        return Ok(None);
    };
    let config = parse_config(&config)?;
    // A profile or legacy root endpoint may change effective selection in
    // ways we cannot prove here; never infer ownership from a dormant table.
    if config.get("profiles").is_some()
        || config.get("profile").is_some()
        || config.get("openai_base_url").is_some()
    {
        return Ok(None);
    }
    let Some(selected) = config.get("model_provider").and_then(Item::as_str) else {
        return Ok(None);
    };
    let Some(provider) = config
        .get("model_providers")
        .and_then(|providers| providers.get(selected))
    else {
        return Ok(None);
    };
    let Some(auth) = read_optional(&codex.join("auth.json"))? else {
        return Ok(None);
    };
    let auth: Json = serde_json::from_slice(&auth).map_err(|_| INVALID)?;
    if auth.get("OPENAI_API_KEY").and_then(Json::as_str) != Some("PROXY_MANAGED")
        || auth.get("auth_mode").and_then(Json::as_str) == Some("chatgpt")
        || auth.get("tokens").is_some_and(|tokens| {
            !tokens.is_null() && tokens.as_object().map_or(true, |tokens| !tokens.is_empty())
        })
        || ["access_token", "refresh_token", "id_token"]
            .iter()
            .any(|key| auth.get(key).is_some_and(|value| !value.is_null()))
    {
        return Ok(None);
    }
    let cc_home = profile.join(".cc-switch");
    let settings = read_optional(&cc_home.join("settings.json"))?
        .map(|bytes| serde_json::from_slice::<Json>(&bytes).map_err(|_| INVALID))
        .transpose()?;
    if settings
        .as_ref()
        .and_then(|settings| settings.get("codexConfigDir"))
        .and_then(Json::as_str)
        .is_some_and(|path| !path.is_empty() && Path::new(path) != codex)
    {
        return Ok(None);
    }
    let db_path = cc_home.join("cc-switch.db");
    validate_path(&db_path)?;
    let db = Connection::open_with_flags(
        &db_path,
        OpenFlags::SQLITE_OPEN_READ_ONLY | OpenFlags::SQLITE_OPEN_NO_MUTEX,
    )
    .map_err(|_| INVALID)?;
    db.busy_timeout(Duration::from_millis(250))
        .map_err(|_| INVALID)?;
    let proxy = db.query_row(
        "SELECT listen_address, listen_port, live_takeover_active FROM proxy_config WHERE app_type = 'codex'",
        [], |row| Ok(ProxyAddress { host: row.get(0)?, port: row.get(1)?, takeover: row.get(2)? }),
    ).optional().map_err(|_| INVALID)?;
    if !proxy.is_some_and(|proxy| proxy.takeover && conflict_proxy_provider(provider, &proxy)) {
        return Ok(None);
    }
    Ok(before.matches_current(&codex).then_some(before))
}

fn conflict_proxy_provider(provider: &Item, proxy: &ProxyAddress) -> bool {
    let Some(url) = provider.get("base_url").and_then(Item::as_str) else {
        return false;
    };
    let Ok(parsed) = Url::parse(url) else {
        return false;
    };
    let Some(host) = parsed.host_str() else {
        return false;
    };
    // A wildcard IPv4 listener does not prove ownership of an IPv6 endpoint,
    // nor does a local port prove ownership of an HTTPS or remote endpoint.
    parsed.scheme() == "http"
        && matches!(host, "127.0.0.1" | "localhost" | "::1" | "[::1]")
        && (host.eq_ignore_ascii_case(&proxy.host)
            || (proxy.host == "0.0.0.0" && host == "127.0.0.1")
            || (matches!(proxy.host.as_str(), "::" | "[::]") && matches!(host, "::1" | "[::1]")))
        && proxy.matches(url)
}

#[cfg(windows)]
fn known_folder(id: &windows_sys::core::GUID) -> Result<PathBuf, &'static str> {
    use std::os::windows::ffi::OsStringExt;
    use windows_sys::Win32::{System::Com::CoTaskMemFree, UI::Shell::SHGetKnownFolderPath};
    let mut raw = std::ptr::null_mut();
    // SAFETY: Windows allocates the terminated UTF-16 result for the current
    // desktop user. No service-account environment variables are consulted.
    if unsafe { SHGetKnownFolderPath(id, 0, std::ptr::null_mut(), &mut raw) } < 0 {
        return Err(INVALID);
    }
    if raw.is_null() {
        return Err(INVALID);
    }
    let result = unsafe {
        let len = (0..32768).find(|&i| *raw.add(i) == 0);
        let path = len.map(|len| {
            PathBuf::from(std::ffi::OsString::from_wide(std::slice::from_raw_parts(
                raw, len,
            )))
        });
        CoTaskMemFree(raw.cast());
        path.ok_or(INVALID)
    };
    result
}

struct ProxyAddress {
    host: String,
    port: u16,
    takeover: bool,
}

impl ProxyAddress {
    fn matches(&self, text: &str) -> bool {
        let Ok(url) = Url::parse(text) else {
            return false;
        };
        let local = |host: &str| {
            matches!(
                host,
                "127.0.0.1" | "localhost" | "[::1]" | "::1" | "0.0.0.0" | "::" | "[::]"
            )
        };
        let Some(host) = url.host_str() else {
            return false;
        };
        matches!(url.scheme(), "http" | "https")
            && url.username().is_empty()
            && url.password().is_none()
            && url.query().is_none()
            && url.fragment().is_none()
            && matches!(url.path().trim_end_matches('/'), "" | "/v1")
            && url.port_or_known_default() == Some(self.port)
            && (host.eq_ignore_ascii_case(&self.host) || (local(host) && local(&self.host)))
    }
}

fn proxy_provider(item: &Item, proxy: &ProxyAddress) -> bool {
    item.get("base_url")
        .and_then(Item::as_str)
        .is_some_and(|url| proxy.matches(url))
}

fn owned_provider(item: &Item, proxy: &ProxyAddress) -> bool {
    proxy_provider(item, proxy) && (proxy.takeover || contains_placeholder(item))
}

fn placeholder(value: &str) -> bool {
    value == "PROXY_MANAGED"
}

fn restore_at(profile: &Path) -> Result<RestoreOutcome, &'static str> {
    let codex = profile.join(".codex");
    let config_path = codex.join("config.toml");
    let Some(before_config) = read_optional(&config_path)? else {
        return Ok(RestoreOutcome::Unchanged);
    };
    let mut live = parse_config(&before_config)?;
    let cc_home = profile.join(".cc-switch");
    let db_path = cc_home.join("cc-switch.db");
    if !db_path.exists() {
        return Ok(RestoreOutcome::Unchanged);
    }
    validate_path(&db_path)?;
    let settings = read_optional(&cc_home.join("settings.json"))?
        .map(|bytes| serde_json::from_slice::<Json>(&bytes).map_err(|_| NO_BACKUP))
        .transpose()?
        .unwrap_or(Json::Null);
    if settings
        .get("codexConfigDir")
        .and_then(Json::as_str)
        .is_some_and(|path| !path.is_empty() && Path::new(path) != codex)
    {
        return Err(CUSTOM_PATH);
    }
    let db = Connection::open_with_flags(
        &db_path,
        OpenFlags::SQLITE_OPEN_READ_ONLY | OpenFlags::SQLITE_OPEN_NO_MUTEX,
    )
    .map_err(|_| NO_BACKUP)?;
    db.busy_timeout(Duration::from_millis(250))
        .map_err(|_| NO_BACKUP)?;
    let proxy = db.query_row(
        "SELECT listen_address, listen_port, live_takeover_active FROM proxy_config WHERE app_type = 'codex'",
        [], |row| Ok(ProxyAddress { host: row.get(0)?, port: row.get(1)?, takeover: row.get(2)? }),
    ).optional().map_err(|_| NO_BACKUP)?;
    let Some(proxy) = proxy else {
        return Ok(RestoreOutcome::Unchanged);
    };
    let affected: Vec<String> = live
        .get("model_providers")
        .and_then(Item::as_table_like)
        .map(|providers| {
            providers
                .iter()
                .filter(|(_, item)| owned_provider(item, &proxy))
                .map(|(name, _)| name.to_owned())
                .collect()
        })
        .unwrap_or_default();
    let root_proxy = proxy.takeover
        && live
            .get("openai_base_url")
            .and_then(Item::as_str)
            .is_some_and(|url| proxy.matches(url));
    let profile_proxy = live
        .get("profiles")
        .and_then(Item::as_table_like)
        .is_some_and(|profiles| {
            proxy.takeover
                && profiles.iter().any(|(_, profile)| {
                    profile
                        .get("openai_base_url")
                        .and_then(Item::as_str)
                        .is_some_and(|url| proxy.matches(url))
                })
        });
    if affected.is_empty() && !root_proxy && !profile_proxy {
        return Ok(RestoreOutcome::Unchanged);
    }

    let original = recovery_source(&db, &settings)?;
    let source_text = original
        .get("config")
        .and_then(Json::as_str)
        .ok_or(NO_BACKUP)?;
    let source = source_text.parse::<DocumentMut>().map_err(|_| NO_BACKUP)?;
    if source
        .get("model_provider")
        .is_some_and(|item| item.as_str().is_none())
    {
        return Err(NO_BACKUP);
    }
    // A backup of a proxy placeholder is not a recoverable upstream.
    let selected = source
        .get("model_provider")
        .and_then(Item::as_str)
        .unwrap_or("openai");
    let official = selected == "openai";
    let upstream = source.get("model_providers").and_then(|p| p.get(selected));
    // A legacy built-in provider with a custom endpoint is ambiguous: never
    // turn it into an OAuth mirror or send a current login to a different host.
    if official
        && (source.get("openai_base_url").is_some()
            || upstream.and_then(|p| p.get("base_url")).is_some())
    {
        return Err(NO_BACKUP);
    }
    if let Some(item) = upstream {
        validate_provider(item)?;
        if proxy_provider(item, &proxy) || contains_placeholder(item) {
            return Err(NO_BACKUP);
        }
    } else if !official {
        return Err(NO_BACKUP);
    }
    if source
        .get("openai_base_url")
        .and_then(Item::as_str)
        .is_some_and(|url| proxy.matches(url))
    {
        return Err(NO_BACKUP);
    }

    // Keep the provider ID: Codex stores it in existing sessions. CC Switch's
    // own v4.0.6 official_mirror_table uses these four fields to resume them:
    // https://github.com/farion1231/cc-switch/blob/v4.0.6/src-tauri/src/live/project/codex.rs
    for name in &affected {
        let replacement = source
            .get("model_providers")
            .and_then(|p| p.get(name))
            .filter(|p| !proxy_provider(p, &proxy) && !contains_placeholder(p))
            .or(upstream);
        let mut replacement = match replacement {
            Some(item) => item.clone(),
            None if official => official_mirror(),
            None => return Err(NO_BACKUP),
        };
        validate_provider(&replacement)?;
        // Recent Codex versions obtain custom-provider API credentials from
        // the provider table. Older CC backups stored them only in auth.json.
        if !official {
            project_api_key(&mut replacement, original.get("auth"));
        }
        install_provider(&mut live, name, replacement, &affected)?;
    }
    // Only restore selectors which actually referred to the terminated proxy.
    // Other providers, models, MCP servers, permissions and UI settings survive.
    let selected_affected = live
        .get("model_provider")
        .and_then(Item::as_str)
        .is_some_and(|selected| affected.iter().any(|name| name == selected));
    if selected_affected || root_proxy {
        if let Some(item) = source.get("model_provider") {
            live["model_provider"] = item.clone();
            if let Some(table) = upstream {
                let mut table = table.clone();
                if !official {
                    project_api_key(&mut table, original.get("auth"));
                }
                install_provider(&mut live, selected, table, &affected)?;
            }
        } else {
            live.remove("model_provider");
        }
    }
    if root_proxy {
        restore_root_key(&mut live, &source, "openai_base_url");
    }
    let mut profile_providers = Vec::new();
    if let Some(profiles) = live.get_mut("profiles").and_then(Item::as_table_like_mut) {
        for (name, item) in profiles.iter_mut() {
            let Some(table) = item.as_table_like_mut() else {
                return Err(INVALID);
            };
            let prior = source.get("profiles").and_then(|p| p.get(name.get()));
            if proxy.takeover
                && table
                    .get("openai_base_url")
                    .and_then(Item::as_str)
                    .is_some_and(|url| proxy.matches(url))
            {
                match prior.and_then(|p| p.get("openai_base_url")) {
                    Some(url) if !url.as_str().is_some_and(|url| proxy.matches(url)) => {
                        table.insert("openai_base_url", url.clone());
                    }
                    _ => {
                        table.remove("openai_base_url");
                    }
                }
                if table
                    .get("model_provider")
                    .and_then(Item::as_str)
                    .is_some_and(|selected| affected.iter().any(|name| name == selected))
                {
                    // This profile already names an alias repaired above; keep
                    // that ID for existing sessions instead of switching it.
                    continue;
                }
                let restored = prior.and_then(|p| p.get("model_provider"));
                if restored.is_some_and(|item| item.as_str().is_none()) {
                    return Err(NO_BACKUP);
                }
                let restored = restored.and_then(Item::as_str).unwrap_or(selected);
                table.insert("model_provider", value(restored));
                if restored != "openai" {
                    let mut provider = source
                        .get("model_providers")
                        .and_then(|p| p.get(restored))
                        .ok_or(NO_BACKUP)?
                        .clone();
                    validate_provider(&provider)?;
                    if proxy_provider(&provider, &proxy) || contains_placeholder(&provider) {
                        return Err(NO_BACKUP);
                    }
                    project_api_key(&mut provider, original.get("auth"));
                    profile_providers.push((restored.to_owned(), provider));
                }
            }
            // Profile selectors keep their IDs; the matching table above now
            // points directly at the recovered upstream, including old sessions.
        }
    }
    for (name, table) in profile_providers {
        install_provider(&mut live, &name, table, &affected)?;
    }
    let auth_path = codex.join("auth.json");
    let before_auth = read_optional(&auth_path)?;
    let after_auth = plan_auth(before_auth.as_deref(), original.get("auth"), official)?;
    let after_config = live.to_string().into_bytes();
    commit_pair(
        &config_path,
        &before_config,
        &after_config,
        &auth_path,
        before_auth.as_deref(),
        after_auth.as_deref(),
        replace_checked,
    )?;
    Ok(RestoreOutcome::Restored)
}

fn contains_placeholder(item: &Item) -> bool {
    item.as_table_like().is_some_and(|table| {
        table.iter().any(|(_, value)| {
            value.as_str().is_some_and(placeholder) || contains_placeholder(value)
        })
    })
}

fn install_provider(
    live: &mut DocumentMut,
    name: &str,
    table: Item,
    affected: &[String],
) -> Result<(), &'static str> {
    if live.get("model_providers").is_none() {
        live["model_providers"] = Item::Table(Table::new());
    }
    if let Some(current) = live.get("model_providers").and_then(|p| p.get(name)) {
        if !affected.iter().any(|affected| affected == name)
            && current.to_string() != table.to_string()
        {
            // An unrelated provider may be used by other profiles or have been
            // edited since the snapshot. Do not overwrite it with an old table.
            return Err(CHANGED);
        }
    }
    live.get_mut("model_providers")
        .and_then(Item::as_table_like_mut)
        .ok_or(INVALID)?
        .insert(name, table);
    Ok(())
}

fn official_mirror() -> Item {
    let mut table = Table::new();
    table.insert("name", value("OpenAI"));
    table.insert("requires_openai_auth", value(true));
    table.insert("supports_websockets", value(true));
    table.insert("wire_api", value("responses"));
    Item::Table(table)
}

fn project_api_key(item: &mut Item, auth: Option<&Json>) {
    let Some(key) = auth
        .and_then(|auth| auth.get("OPENAI_API_KEY"))
        .and_then(Json::as_str)
        .filter(|key| !key.is_empty() && !placeholder(key))
    else {
        return;
    };
    let Some(table) = item.as_table_like_mut() else {
        return;
    };
    let header_auth = ["http_headers", "env_http_headers"].iter().any(|key| {
        table
            .get(key)
            .and_then(Item::as_table_like)
            .is_some_and(|headers| {
                headers.iter().any(|(name, _)| {
                    name.eq_ignore_ascii_case("authorization")
                        || name.eq_ignore_ascii_case("x-api-key")
                })
            })
    });
    if !header_auth
        && ["env_key", "experimental_bearer_token"]
            .iter()
            .all(|key| !table.contains_key(key))
    {
        table.insert("experimental_bearer_token", value(key));
    }
}

fn validate_provider(item: &Item) -> Result<(), &'static str> {
    let table = item.as_table_like().ok_or(NO_BACKUP)?;
    for key in [
        "name",
        "base_url",
        "env_key",
        "experimental_bearer_token",
        "wire_api",
    ] {
        if table.get(key).is_some_and(|value| value.as_str().is_none()) {
            return Err(NO_BACKUP);
        }
    }
    for key in ["requires_openai_auth", "supports_websockets"] {
        if table
            .get(key)
            .is_some_and(|value| value.as_bool().is_none())
        {
            return Err(NO_BACKUP);
        }
    }
    for key in ["http_headers", "env_http_headers"] {
        if let Some(item) = table.get(key) {
            let headers = item.as_table_like().ok_or(NO_BACKUP)?;
            if headers.iter().any(|(_, item)| item.as_str().is_none()) {
                return Err(NO_BACKUP);
            }
        }
    }
    Ok(())
}

fn restore_root_key(live: &mut DocumentMut, source: &DocumentMut, key: &str) {
    match source.get(key) {
        Some(item) => {
            live[key] = item.clone();
        }
        None => {
            live.remove(key);
        }
    }
}

fn recovery_source(db: &Connection, settings: &Json) -> Result<Json, &'static str> {
    let backup: Option<String> = db.query_row(
        "SELECT original_config FROM proxy_live_backup WHERE app_type = 'codex' AND length(original_config) <= ?1",
        [MAX_FILE], |row| row.get(0),
    ).optional().map_err(|_| NO_BACKUP)?;
    if let Some(raw) = backup {
        return serde_json::from_str(&raw).map_err(|_| NO_BACKUP);
    }
    // Match CC Switch's current-provider precedence, never select an arbitrary
    // first provider or infer a URL/API key from the failed proxy URL.
    if let Some(id) = settings.get("currentProviderCodex").and_then(Json::as_str) {
        let raw: Option<String> = db.query_row(
            "SELECT settings_config FROM providers WHERE app_type='codex' AND id=?1 AND length(settings_config)<=?2",
            rusqlite::params![id, MAX_FILE], |row| row.get(0),
        ).optional().map_err(|_| NO_BACKUP)?;
        if let Some(raw) = raw {
            return serde_json::from_str(&raw).map_err(|_| NO_BACKUP);
        }
    }
    let mut statement = db.prepare("SELECT settings_config FROM providers WHERE app_type='codex' AND is_current=1 AND length(settings_config)<=?1 LIMIT 2")
        .map_err(|_| NO_BACKUP)?;
    let rows = statement
        .query_map([MAX_FILE], |row| row.get::<_, String>(0))
        .map_err(|_| NO_BACKUP)?;
    let values: Vec<String> = rows.collect::<Result<_, _>>().map_err(|_| NO_BACKUP)?;
    if values.len() != 1 {
        return Err(NO_BACKUP);
    }
    serde_json::from_str(&values[0]).map_err(|_| NO_BACKUP)
}

fn plan_auth(
    current: Option<&[u8]>,
    backup: Option<&Json>,
    official: bool,
) -> Result<Option<Vec<u8>>, &'static str> {
    let Some(bytes) = current else {
        // Do not resurrect old OAuth refresh tokens or fabricate a login.
        let key = backup
            .and_then(|auth| auth.get("OPENAI_API_KEY"))
            .and_then(Json::as_str)
            .filter(|key| !key.is_empty() && !placeholder(key))
            .ok_or(NO_BACKUP)?;
        let saved = serde_json::json!({"OPENAI_API_KEY": key, "auth_mode": "apikey"});
        return Ok(Some(
            serde_json::to_vec_pretty(&saved).map_err(|_| INVALID)?,
        ));
    };
    let mut auth: Json = serde_json::from_slice(bytes).map_err(|_| INVALID)?;
    if !auth.is_object() {
        return Err(INVALID);
    }
    let is_placeholder = auth
        .get("OPENAI_API_KEY")
        .and_then(Json::as_str)
        .is_some_and(placeholder);
    if !is_placeholder {
        return Ok(None);
    }
    if official {
        // New CC Switch backups deliberately omit official auth. Keep current
        // OAuth tokens, which may have refreshed after the backup was made.
        if let Some(key) = backup
            .and_then(|auth| auth.get("OPENAI_API_KEY"))
            .and_then(Json::as_str)
            .filter(|key| !key.is_empty() && !placeholder(key))
        {
            auth["OPENAI_API_KEY"] = Json::String(key.to_owned());
            auth["auth_mode"] = Json::String("apikey".into());
        } else {
            if !auth
                .get("tokens")
                .and_then(|tokens| tokens.get("access_token"))
                .and_then(Json::as_str)
                .is_some_and(|token| !token.is_empty())
            {
                return Err(NO_BACKUP);
            }
            auth["OPENAI_API_KEY"] = Json::Null;
            auth["auth_mode"] = Json::String("chatgpt".into());
        }
    } else {
        let key = backup
            .and_then(|auth| auth.get("OPENAI_API_KEY"))
            .and_then(Json::as_str)
            .filter(|key| !key.is_empty() && !placeholder(key))
            .ok_or(NO_BACKUP)?;
        auth["OPENAI_API_KEY"] = Json::String(key.to_owned());
        // Preserve tokens and unrelated fields even when the current mode is API.
        auth["auth_mode"] = Json::String("apikey".into());
    }
    Ok(Some(serde_json::to_vec_pretty(&auth).map_err(|_| INVALID)?))
}

fn parse_config(bytes: &[u8]) -> Result<DocumentMut, &'static str> {
    std::str::from_utf8(bytes)
        .map_err(|_| INVALID)?
        .parse()
        .map_err(|_| INVALID)
}

fn validate_path(path: &Path) -> Result<(), &'static str> {
    if !path.is_absolute() {
        return Err(INVALID);
    }
    for ancestor in path.ancestors() {
        match fs::symlink_metadata(ancestor) {
            Ok(metadata) => {
                #[cfg(windows)]
                {
                    use std::os::windows::fs::MetadataExt;
                    if metadata.file_attributes() & 0x400 != 0 {
                        return Err(INVALID);
                    }
                }
                if metadata.file_type().is_symlink() {
                    return Err(INVALID);
                }
            }
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => {}
            Err(_) => return Err(INVALID),
        }
    }
    Ok(())
}

fn read_optional(path: &Path) -> Result<Option<Vec<u8>>, &'static str> {
    validate_path(path)?;
    let mut options = OpenOptions::new();
    options.read(true);
    #[cfg(windows)]
    {
        use std::os::windows::fs::OpenOptionsExt;
        use windows_sys::Win32::Storage::FileSystem::FILE_FLAG_OPEN_REPARSE_POINT;
        options.custom_flags(FILE_FLAG_OPEN_REPARSE_POINT);
    }
    let input = match options.open(path) {
        Ok(input) => input,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(None),
        Err(_) => return Err(INVALID),
    };
    let metadata = input.metadata().map_err(|_| INVALID)?;
    if !metadata.is_file() || metadata.len() > MAX_FILE {
        return Err(INVALID);
    }
    let mut bytes = Vec::new();
    input
        .take(MAX_FILE + 1)
        .read_to_end(&mut bytes)
        .map_err(|_| INVALID)?;
    if bytes.len() as u64 > MAX_FILE {
        return Err(INVALID);
    }
    Ok(Some(bytes))
}

fn commit_pair<F>(
    config: &Path,
    before_config: &[u8],
    after_config: &[u8],
    auth: &Path,
    before_auth: Option<&[u8]>,
    after_auth: Option<&[u8]>,
    mut replace: F,
) -> Result<(), &'static str>
where
    F: FnMut(&Path, Option<&[u8]>, &[u8]) -> Result<(), &'static str>,
{
    if read_optional(config)?.as_deref() != Some(before_config)
        || read_optional(auth)?.as_deref() != before_auth
    {
        return Err(CHANGED);
    }
    if let Some(bytes) = after_auth {
        replace(auth, before_auth, bytes)?;
    }
    if let Err(error) = replace(config, Some(before_config), after_config) {
        if let Some(written) = after_auth {
            match before_auth {
                Some(original) if replace(auth, Some(written), original).is_ok() => {}
                None if read_optional(auth)?.as_deref() == Some(written)
                    && fs::remove_file(auth).is_ok() => {}
                _ => return Err(ROLLBACK_FAILED),
            }
        }
        return Err(error);
    }
    Ok(())
}

fn replace_checked(path: &Path, expected: Option<&[u8]>, bytes: &[u8]) -> Result<(), &'static str> {
    if read_optional(path)?.as_deref() != expected {
        return Err(CHANGED);
    }
    let temporary =
        path.with_file_name(format!(".quota-guard-restore-{}.tmp", uuid::Uuid::new_v4()));
    let result = (|| {
        let mut output = OpenOptions::new()
            .write(true)
            .create_new(true)
            .open(&temporary)
            .map_err(|_| WRITE_FAILED)?;
        output.write_all(bytes).map_err(|_| WRITE_FAILED)?;
        output.sync_all().map_err(|_| WRITE_FAILED)?;
        drop(output);
        if read_optional(path)?.as_deref() != expected {
            return Err(CHANGED);
        }
        #[cfg(windows)]
        if expected.is_some() {
            use std::os::windows::ffi::OsStrExt;
            use windows_sys::Win32::Storage::FileSystem::ReplaceFileW;
            let target: Vec<u16> = path.as_os_str().encode_wide().chain(Some(0)).collect();
            let source: Vec<u16> = temporary.as_os_str().encode_wide().chain(Some(0)).collect();
            // SAFETY: Both paths are our checked files. ReplaceFile preserves
            // the target's ACL and atomically publishes the complete document.
            if unsafe {
                ReplaceFileW(
                    target.as_ptr(),
                    source.as_ptr(),
                    std::ptr::null(),
                    0,
                    std::ptr::null(),
                    std::ptr::null(),
                )
            } == 0
            {
                return Err(WRITE_FAILED);
            }
            return Ok(());
        }
        if expected.is_none() {
            // Atomic create-if-absent; never overwrite a newly created login.
            fs::hard_link(&temporary, path).map_err(|_| CHANGED)?;
        } else {
            fs::rename(&temporary, path).map_err(|_| WRITE_FAILED)?;
        }
        Ok(())
    })();
    let _ = fs::remove_file(&temporary);
    result
}

#[cfg(test)]
mod tests;
