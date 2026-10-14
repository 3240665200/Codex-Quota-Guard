//! Inspect the compiled context without creating a runtime or touching user state.

use serde::Serialize;
use std::{collections::BTreeMap, ffi::OsString, io::Write};

const VERIFY_ARGUMENT: &str = "--verify-package";

#[derive(Debug, PartialEq, Eq)]
enum LaunchMode {
    Normal,
    Verify,
    Invalid,
}

fn launch_mode(arguments: &[OsString]) -> LaunchMode {
    if !arguments.iter().any(|argument| {
        argument == VERIFY_ARGUMENT || argument.to_string_lossy().starts_with("--verify-package=")
    }) {
        return LaunchMode::Normal;
    }
    if arguments.len() == 1 && arguments[0] == VERIFY_ARGUMENT {
        LaunchMode::Verify
    } else {
        LaunchMode::Invalid
    }
}

#[derive(Debug, Serialize)]
struct AssetReport {
    path: String,
    bytes: usize,
}

#[derive(Debug, Serialize)]
struct PackageReport {
    probe: &'static str,
    ok: bool,
    version: String,
    dev: bool,
    index: AssetReport,
    scripts: Vec<AssetReport>,
    stylesheets: Vec<AssetReport>,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum CheckError {
    InvalidArguments,
    DevelopmentBuild,
    MissingIndex,
    InvalidHtml,
    MissingRoot,
    InvalidReference,
    MissingScript,
    MissingStylesheet,
    MissingAsset,
}

impl CheckError {
    fn details(self) -> (&'static str, &'static str) {
        match self {
            Self::InvalidArguments => (
                "invalid_arguments",
                "Package verification accepts only --verify-package.",
            ),
            Self::DevelopmentBuild => (
                "development_build",
                "The executable was compiled in Tauri development mode.",
            ),
            Self::MissingIndex => (
                "missing_index",
                "The embedded index page is missing or empty.",
            ),
            Self::InvalidHtml => (
                "invalid_html",
                "The embedded index page is not valid static HTML.",
            ),
            Self::MissingRoot => (
                "missing_root",
                "The embedded index page has no application root.",
            ),
            Self::InvalidReference => (
                "invalid_reference",
                "The embedded index page contains an unsupported asset reference.",
            ),
            Self::MissingScript => (
                "missing_script",
                "The embedded index page has no script entry.",
            ),
            Self::MissingStylesheet => (
                "missing_stylesheet",
                "The embedded index page has no stylesheet entry.",
            ),
            Self::MissingAsset => (
                "missing_asset",
                "A referenced embedded asset is missing, empty, or cannot be decompressed.",
            ),
        }
    }
}

#[derive(Serialize)]
struct ErrorDetails {
    code: &'static str,
    message: &'static str,
}

#[derive(Serialize)]
struct FailureReport<'a> {
    probe: &'static str,
    ok: bool,
    version: &'a str,
    dev: bool,
    error: ErrorDetails,
}

/// This runs before autostart, service setup, and all application callbacks.
pub fn handle_launch_mode<R: tauri::Runtime>(context: &tauri::Context<R>) -> Option<i32> {
    let mode = launch_mode(&std::env::args_os().skip(1).collect::<Vec<_>>());
    if mode == LaunchMode::Normal {
        return None;
    }

    let version = context.package_info().version.to_string();
    let dev = tauri::is_dev();
    let result = if mode == LaunchMode::Invalid {
        Err(CheckError::InvalidArguments)
    } else {
        verify_assets(&version, dev, |path| {
            context
                .assets()
                .get(&path.into())
                .map(|data| data.into_owned())
        })
    };

    let stdout = std::io::stdout();
    let mut output = stdout.lock();
    let (exit_code, written) = match result {
        Ok(report) => (0, serde_json::to_writer(&mut output, &report)),
        Err(error) => {
            let (code, message) = error.details();
            let report = FailureReport {
                probe: "CQG_PACKAGE_PROBE_V1",
                ok: false,
                version: &version,
                dev,
                error: ErrorDetails { code, message },
            };
            (1, serde_json::to_writer(&mut output, &report))
        }
    };
    if written.is_err() || output.write_all(b"\n").is_err() || output.flush().is_err() {
        return Some(1);
    }
    Some(exit_code)
}

fn verify_assets(
    version: &str,
    dev: bool,
    mut read: impl FnMut(&str) -> Option<Vec<u8>>,
) -> Result<PackageReport, CheckError> {
    if dev {
        return Err(CheckError::DevelopmentBuild);
    }
    let index = read("index.html")
        .filter(|data| !data.is_empty())
        .ok_or(CheckError::MissingIndex)?;
    let html = std::str::from_utf8(&index).map_err(|_| CheckError::InvalidHtml)?;
    let tags = opening_tags(html)?;
    if !tags.iter().any(|tag| tag.name == "html") {
        return Err(CheckError::InvalidHtml);
    }
    if !tags
        .iter()
        .any(|tag| tag.name == "div" && tag.attributes.get("id") == Some(&"root"))
    {
        return Err(CheckError::MissingRoot);
    }

    let mut scripts = BTreeMap::new();
    let mut stylesheets = BTreeMap::new();
    let mut script_entries = 0;
    for tag in tags {
        let (reference, extension, destination) = if tag.name == "script" {
            script_entries += 1;
            (tag.attributes.get("src"), ".js", &mut scripts)
        } else if tag.name == "link" {
            let relation = tag.attributes.get("rel").copied().unwrap_or_default();
            if relation
                .split_ascii_whitespace()
                .any(|part| part.eq_ignore_ascii_case("stylesheet"))
            {
                (tag.attributes.get("href"), ".css", &mut stylesheets)
            } else if relation
                .split_ascii_whitespace()
                .any(|part| part.eq_ignore_ascii_case("modulepreload"))
            {
                (tag.attributes.get("href"), ".js", &mut scripts)
            } else if relation
                .split_ascii_whitespace()
                .any(|part| part.eq_ignore_ascii_case("preload"))
            {
                match tag.attributes.get("as").copied() {
                    Some("script") => (tag.attributes.get("href"), ".js", &mut scripts),
                    Some("style") => (tag.attributes.get("href"), ".css", &mut stylesheets),
                    _ => continue,
                }
            } else {
                continue;
            }
        } else {
            continue;
        };
        let path = local_asset_path(
            reference.copied().ok_or(CheckError::InvalidReference)?,
            extension,
        )?;
        let bytes = read(path)
            .filter(|data| !data.is_empty())
            .ok_or(CheckError::MissingAsset)?
            .len();
        destination.insert(path.to_owned(), bytes);
    }
    if script_entries == 0 {
        return Err(CheckError::MissingScript);
    }
    if stylesheets.is_empty() {
        return Err(CheckError::MissingStylesheet);
    }
    let reports = |assets: BTreeMap<String, usize>| {
        assets
            .into_iter()
            .map(|(path, bytes)| AssetReport { path, bytes })
            .collect()
    };
    Ok(PackageReport {
        probe: "CQG_PACKAGE_PROBE_V1",
        ok: true,
        version: version.to_owned(),
        dev,
        index: AssetReport {
            path: "index.html".to_owned(),
            bytes: index.len(),
        },
        scripts: reports(scripts),
        stylesheets: reports(stylesheets),
    })
}

fn local_asset_path<'a>(reference: &'a str, extension: &str) -> Result<&'a str, CheckError> {
    let path = reference
        .strip_prefix("./")
        .or_else(|| reference.strip_prefix('/'))
        .unwrap_or(reference);
    if !path.starts_with("assets/")
        || !path.ends_with(extension)
        || !path
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || b"/._-".contains(&byte))
        || path
            .split('/')
            .any(|part| part.is_empty() || part == "." || part == "..")
    {
        return Err(CheckError::InvalidReference);
    }
    Ok(path)
}

#[derive(Debug)]
struct Tag<'a> {
    name: String,
    attributes: BTreeMap<String, &'a str>,
}

// Accept the static HTML emitted by Vite/Tauri, respecting quoted '>' characters
// and comments so that references cannot be manufactured from comment text.
fn opening_tags(mut html: &str) -> Result<Vec<Tag<'_>>, CheckError> {
    let mut tags = Vec::new();
    while let Some(start) = html.find('<') {
        html = &html[start + 1..];
        if let Some(comment) = html.strip_prefix("!--") {
            let end = comment.find("-->").ok_or(CheckError::InvalidHtml)?;
            html = &comment[end + 3..];
            continue;
        }
        let mut quote = None;
        let end = html
            .bytes()
            .position(|byte| match (quote, byte) {
                (Some(mark), value) if mark == value => {
                    quote = None;
                    false
                }
                (None, b'\'' | b'"') => {
                    quote = Some(byte);
                    false
                }
                (None, b'>') => true,
                _ => false,
            })
            .ok_or(CheckError::InvalidHtml)?;
        let body = &html[..end];
        html = &html[end + 1..];
        if body.starts_with('/') || body.starts_with('!') {
            continue;
        }
        let name_end = body
            .bytes()
            .position(|byte| !byte.is_ascii_alphanumeric() && byte != b'-')
            .unwrap_or(body.len());
        if name_end == 0 {
            return Err(CheckError::InvalidHtml);
        }
        tags.push(Tag {
            name: body[..name_end].to_ascii_lowercase(),
            attributes: attributes(&body[name_end..])?,
        });
    }
    Ok(tags)
}

fn attributes(mut input: &str) -> Result<BTreeMap<String, &str>, CheckError> {
    let mut result = BTreeMap::new();
    loop {
        input = input.trim_start_matches(|character: char| character.is_ascii_whitespace());
        if input.is_empty() || input == "/" {
            return Ok(result);
        }
        let end = input
            .bytes()
            .position(|byte| !byte.is_ascii_alphanumeric() && !b"-_:.".contains(&byte))
            .unwrap_or(input.len());
        if end == 0 {
            return Err(CheckError::InvalidHtml);
        }
        let name = input[..end].to_ascii_lowercase();
        input = input[end..].trim_start_matches(|character: char| character.is_ascii_whitespace());
        let mut value = "";
        if let Some(rest) = input.strip_prefix('=') {
            input = rest.trim_start_matches(|character: char| character.is_ascii_whitespace());
            let quote = input.bytes().next().ok_or(CheckError::InvalidHtml)?;
            if quote != b'\'' && quote != b'"' {
                return Err(CheckError::InvalidHtml);
            }
            input = &input[1..];
            let end = input
                .find(char::from(quote))
                .ok_or(CheckError::InvalidHtml)?;
            value = &input[..end];
            input = &input[end + 1..];
        }
        if result.insert(name, value).is_some() {
            return Err(CheckError::InvalidHtml);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const HTML: &str = r#"<!doctype html><html><head>
        <script type="module" crossorigin src="./assets/main.js"></script>
        <link rel="modulepreload" href="./assets/vendor.js">
        <link rel="stylesheet" href="./assets/main.css">
        </head><body><div id="root"></div></body></html>"#;

    fn check(html: &str) -> Result<PackageReport, CheckError> {
        verify_assets("1.2.3", false, |path| {
            Some(if path == "index.html" {
                html.as_bytes().to_vec()
            } else {
                b"embedded asset".to_vec()
            })
        })
    }

    #[test]
    fn valid_package_reports_embedded_assets() {
        let report = check(HTML).unwrap();
        assert!(report.ok);
        assert!(!report.dev);
        assert_eq!(report.version, "1.2.3");
        assert_eq!(report.index.bytes, HTML.len());
        assert_eq!(report.scripts.len(), 2);
        assert_eq!(report.stylesheets.len(), 1);
        assert_eq!(report.scripts[0].path, "assets/main.js");
    }

    #[test]
    fn developer_mode_is_rejected_without_reading_assets() {
        let result = verify_assets("1.2.3", true, |_| panic!("must not read assets"));
        assert_eq!(result.unwrap_err(), CheckError::DevelopmentBuild);
    }

    #[test]
    fn absent_and_empty_embedded_files_fail() {
        for contents in [None, Some(Vec::new())] {
            assert_eq!(
                verify_assets("1", false, |_| contents.clone()).unwrap_err(),
                CheckError::MissingIndex
            );
            assert_eq!(
                verify_assets("1", false, |path| {
                    if path == "index.html" {
                        Some(HTML.as_bytes().to_vec())
                    } else {
                        contents.clone()
                    }
                })
                .unwrap_err(),
                CheckError::MissingAsset
            );
        }
    }

    #[test]
    fn remote_inline_and_unknown_script_references_fail() {
        for reference in [
            "https://example.com/main.js",
            "//example.com/main.js",
            "data:text/javascript,x",
            "../assets/main.js",
            "./assets/../main.js",
            "./assets/main.js?x=1",
            "./other/main.js",
            "./assets/main.txt",
        ] {
            assert_eq!(
                check(&HTML.replace("./assets/main.js", reference)).unwrap_err(),
                CheckError::InvalidReference
            );
        }
        assert_eq!(
            check(&HTML.replace("src=\"./assets/main.js\"", "")).unwrap_err(),
            CheckError::InvalidReference
        );
    }

    #[test]
    fn static_entry_requires_root_script_and_stylesheet() {
        assert_eq!(
            check(&HTML.replace("id=\"root\"", "id=\"other\"")).unwrap_err(),
            CheckError::MissingRoot
        );
        assert_eq!(
            check(&HTML.replace(
                "<script type=\"module\" crossorigin src=\"./assets/main.js\"></script>",
                ""
            ))
            .unwrap_err(),
            CheckError::MissingScript
        );
        assert_eq!(
            check(&HTML.replace("<link rel=\"stylesheet\" href=\"./assets/main.css\">", ""))
                .unwrap_err(),
            CheckError::MissingStylesheet
        );
    }

    #[test]
    fn comments_do_not_add_assets_and_quoted_greater_than_is_allowed() {
        let html = HTML.replace("<head>", "<head><!-- <script src=\"https://invalid/main.js\"></script> --><meta content=\"a > b\">");
        assert_eq!(check(&html).unwrap().scripts.len(), 2);
        assert_eq!(
            check(&HTML.replace("src=\"./assets/main.js\"", "SRC='./assets/main.js'"))
                .unwrap()
                .scripts
                .len(),
            2
        );
    }

    #[test]
    fn malformed_or_non_utf8_html_fails() {
        assert_eq!(
            check(&HTML.replace("src=\"./assets/main.js\"", "src=./assets/main.js")).unwrap_err(),
            CheckError::InvalidHtml
        );
        assert_eq!(
            check(&HTML.replace(
                "src=\"./assets/main.js\"",
                "src=\"./assets/main.js\" src=\"./assets/other.js\""
            ))
            .unwrap_err(),
            CheckError::InvalidHtml
        );
        assert_eq!(
            verify_assets("1", false, |_| Some(vec![0xff])).unwrap_err(),
            CheckError::InvalidHtml
        );
    }

    #[test]
    fn verification_arguments_cannot_fall_through_to_normal_startup() {
        assert_eq!(launch_mode(&[]), LaunchMode::Normal);
        assert_eq!(
            launch_mode(&["--verify-package".into()]),
            LaunchMode::Verify
        );
        for arguments in [
            vec!["--verify-package", "out.json"],
            vec!["--autostart", "--verify-package"],
            vec!["--verify-package=out.json"],
            vec!["--verify-package", "--verify-package"],
        ] {
            assert_eq!(
                launch_mode(
                    &arguments
                        .into_iter()
                        .map(OsString::from)
                        .collect::<Vec<_>>()
                ),
                LaunchMode::Invalid
            );
        }
    }
}
