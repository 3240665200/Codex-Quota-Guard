#![forbid(unsafe_code)]

mod probe;

use quota_session_binding_demo::{analyze, confirm_owner, parse_case};
use serde::Serialize;
use std::{fs::{File, OpenOptions}, io::{Read, Write}, process::ExitCode};

const LIMIT: u64 = 1_048_576;
const EXAMPLE: &[u8] = include_bytes!("../examples/multiple-users.json");

fn read_bounded(path: &str) -> Result<Vec<u8>, String> {
    let file = File::open(path).map_err(|_| "E_FILE_READ")?;
    if !file.metadata().map_err(|_| "E_FILE_READ")?.is_file() {
        return Err("E_NOT_REGULAR_FILE".into());
    }
    let mut bytes = Vec::new();
    file.take(LIMIT + 1).read_to_end(&mut bytes).map_err(|_| "E_FILE_READ")?;
    if bytes.len() as u64 > LIMIT { return Err("E_INPUT_TOO_LARGE".into()); }
    Ok(bytes)
}

fn print_json(value: &impl Serialize) -> Result<(), String> {
    let mut stdout = std::io::stdout().lock();
    serde_json::to_writer_pretty(&mut stdout, value).map_err(|_| "E_OUTPUT")?;
    writeln!(stdout).map_err(|_| "E_OUTPUT".to_owned())
}

fn run(args: &[String]) -> Result<(), String> {
    let args: Vec<_> = args.iter().map(String::as_str).collect();
    match args.as_slice() {
        [] | ["demo"] => {
            let case = parse_case(EXAMPLE).map_err(|e| e.to_string())?;
            print_json(&analyze(&case).map_err(|e| e.to_string())?)
        }
        ["analyze", path] => {
            let case = parse_case(&read_bounded(path)?).map_err(|e| e.to_string())?;
            print_json(&analyze(&case).map_err(|e| e.to_string())?)
        }
        ["confirm", path, "--session-ref", reference, "--confirmed-by", operator,
            "--acknowledge-owner", "--out", output] => {
            // Acknowledgement is an operator assertion, NOT authenticated identity.
            let case = parse_case(&read_bounded(path)?).map_err(|e| e.to_string())?;
            let confirmed = confirm_owner(&case, reference, operator).map_err(|e| e.to_string())?;
            let bytes = serde_json::to_vec_pretty(&confirmed).map_err(|_| "E_OUTPUT")?;
            if bytes.len() as u64 > LIMIT { return Err("E_INPUT_TOO_LARGE".into()); }
            // Never overwrite input, credentials, an existing binding, or a symlink.
            let mut options = OpenOptions::new();
            options.write(true).create_new(true);
            #[cfg(unix)] {
                use std::os::unix::fs::OpenOptionsExt;
                options.mode(0o600);
            }
            let mut file = options.open(output).map_err(|_| "E_OUTPUT_CREATE_NEW")?;
            file.write_all(&bytes).and_then(|_| file.sync_all()).map_err(|_| "E_OUTPUT_WRITE")?;
            print_json(&serde_json::json!({"saved":true,"evidence":"operator_confirmed","enforcement_allowed":false}))
        }
        ["probe", "--auth-file", auth, "--sessions-file", snapshot, "--allow-read-auth"] => {
            // Validate file paths and size, but never echo either path or contents.
            // This path has no HTTP client and never refreshes/logs out an account.
            let snapshot_bytes = read_bounded(snapshot)?;
            let auth_bytes = read_bounded(auth)?;
            let report = probe::compare(&auth_bytes, &snapshot_bytes).map_err(|e| e.to_string())?;
            print_json(&report)
        }
        ["--help"] | ["help"] => {
            println!("Offline session attribution demo (no logout or network commands).\n\
                demo\n\
                analyze <case.json>\n\
                confirm <case.json> --session-ref <hash> --confirmed-by <operator> --acknowledge-owner --out <NEW.local.json>\n\
                probe --auth-file <auth.json> --sessions-file <snapshot.json> --allow-read-auth\n\
                WARNING: confirm output contains session IDs; keep it in a private local directory.\n\
                All results are unverified evidence. Automatic enforcement is always disabled.");
            Ok(())
        }
        _ => Err("E_ARGUMENTS_USE_HELP".into()),
    }
}

fn main() -> ExitCode {
    match run(&std::env::args().skip(1).collect::<Vec<_>>()) {
        Ok(()) => ExitCode::SUCCESS,
        Err(code) => { eprintln!("{code}"); ExitCode::FAILURE }
    }
}
