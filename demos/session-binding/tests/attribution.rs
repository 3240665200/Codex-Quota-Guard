use quota_session_binding_demo::{analyze, confirm_owner, parse_case};
use serde_json::{json, Value};
use std::collections::BTreeMap;

fn device(id: &str, client: &str) -> Value {
    json!({"session_id": id, "app_sessions": [{"client_name": client}]})
}

fn binding(account: &str, session: &str, user: &str, device: &str, profile: &str) -> Value {
    json!({
        "official_account_id": account,
        "session_id": session,
        "user_id": user,
        "device_id": device,
        "profile_id": profile,
        "evidence": "operator_confirmed",
        "confirmed_by": "operator-demo"
    })
}

fn case(before: Vec<Value>, after: Vec<Value>, bindings: Vec<Value>) -> Value {
    json!({
        "schema_version": 1,
        "context": {
            "user_id": "alice", "device_id": "desktop-a", "profile_id": "profile-a",
            "official_account_id": "official-a"
        },
        "before": {"show_session_manager": true, "devices": before},
        "after": {"show_session_manager": true, "devices": after},
        "bindings": bindings
    })
}

fn report(input: &Value) -> Value {
    let parsed = parse_case(&serde_json::to_vec(input).unwrap()).unwrap();
    let result = serde_json::to_value(analyze(&parsed).unwrap()).unwrap();
    assert_eq!(result["enforcement_allowed"], false);
    result
}

fn single_ref(input: &Value) -> String {
    report(input)["sessions"][0]["session_ref"]
        .as_str()
        .unwrap()
        .to_owned()
}

fn assert_rejected(input: &Value) {
    assert!(parse_case(&serde_json::to_vec(input).unwrap()).is_err());
}

#[test]
fn a_single_new_codex_session_is_a_candidate_without_an_owner() {
    let input = case(vec![], vec![device("new-session", "Codex")], vec![]);
    let output = report(&input);
    assert_eq!(output["sessions"][0]["status"], "CANDIDATE");
    assert!(output["sessions"][0]["owner"].is_null());
    assert_eq!(output["candidate_count"], 1);
}

#[test]
fn concurrent_new_sessions_remain_multiple_unowned_candidates() {
    let input = case(
        vec![],
        vec![device("new-a", "Codex"), device("new-b", "Codex")],
        vec![],
    );
    let output = report(&input);
    assert_eq!(output["candidate_count"], 2);
    for row in output["sessions"].as_array().unwrap() {
        assert_eq!(row["status"], "CANDIDATE");
        assert!(row["owner"].is_null());
    }
}

#[test]
fn historical_unknown_session_is_unbound_even_if_it_is_current() {
    let mut current = device("old-session", "Codex");
    current["is_current_device"] = json!(true);
    let output = report(&case(vec![current.clone()], vec![current], vec![]));
    assert_eq!(output["sessions"][0]["status"], "UNBOUND");
    assert!(output["sessions"][0]["owner"].is_null());
    assert_eq!(output["sessions"][0]["is_current_device"], true);
}

#[test]
fn exact_operator_record_is_reported_as_a_record_not_verified_identity() {
    let input = case(
        vec![],
        vec![device("registered-session", "Codex")],
        vec![binding(
            "official-a",
            "registered-session",
            "alice",
            "desktop-a",
            "profile-a",
        )],
    );
    let output = report(&input);
    let row = &output["sessions"][0];
    assert_eq!(row["status"], "RECORDED_BINDING");
    assert_eq!(row["owner"]["user_id"], "alice");
    assert_eq!(row["owner"]["device_id"], "desktop-a");
    assert_eq!(row["owner"]["profile_id"], "profile-a");
    assert!(!output["warnings"].as_array().unwrap().is_empty());
}

#[test]
fn same_session_id_from_another_account_cannot_supply_ownership() {
    let input = case(
        vec![],
        vec![device("shared-session-string", "Codex")],
        vec![binding(
            "official-b",
            "shared-session-string",
            "bob",
            "desktop-b",
            "profile-b",
        )],
    );
    let output = report(&input);
    assert_eq!(output["sessions"][0]["status"], "CANDIDATE");
    assert!(output["sessions"][0]["owner"].is_null());
    assert!(output["stale_bindings"].as_array().unwrap().is_empty());
    let mut other = input.clone();
    other["context"]["official_account_id"] = json!("official-b");
    assert_ne!(single_ref(&input), single_ref(&other));
}

#[test]
fn conflicting_users_devices_or_profiles_never_select_one_owner() {
    for (user, device_id, profile) in [
        ("bob", "desktop-a", "profile-a"),
        ("alice", "desktop-b", "profile-a"),
        ("alice", "desktop-a", "profile-b"),
    ] {
        let input = case(
            vec![],
            vec![device("same-session", "Codex")],
            vec![
                binding(
                    "official-a",
                    "same-session",
                    "alice",
                    "desktop-a",
                    "profile-a",
                ),
                binding("official-a", "same-session", user, device_id, profile),
            ],
        );
        let output = report(&input);
        assert_eq!(output["sessions"][0]["status"], "CONFLICT");
        assert!(output["sessions"][0]["owner"].is_null());
        assert_eq!(output["candidate_count"], 0);
    }
}

#[test]
fn repeated_identical_operator_records_do_not_invent_a_conflict() {
    let record = binding(
        "official-a",
        "same-session",
        "alice",
        "desktop-a",
        "profile-a",
    );
    let output = report(&case(
        vec![],
        vec![device("same-session", "Codex")],
        vec![record.clone(), record],
    ));
    assert_eq!(output["sessions"][0]["status"], "RECORDED_BINDING");
}

#[test]
fn duplicate_after_session_ids_are_conflicts_even_when_bound() {
    let input = case(
        vec![],
        vec![device("duplicate", "Codex"), device("duplicate", "ChatGPT")],
        vec![binding(
            "official-a",
            "duplicate",
            "alice",
            "desktop-a",
            "profile-a",
        )],
    );
    let output = report(&input);
    assert_eq!(output["sessions"].as_array().unwrap().len(), 2);
    for row in output["sessions"].as_array().unwrap() {
        assert_eq!(row["status"], "CONFLICT");
        assert!(row["owner"].is_null());
    }
}

#[test]
fn duplicate_nonempty_before_session_ids_are_invalid() {
    assert_rejected(&case(
        vec![device("duplicate", "Codex"), device("duplicate", "Codex")],
        vec![],
        vec![],
    ));
}

#[test]
fn absent_null_and_empty_ids_are_unavailable_without_fingerprints() {
    let output = report(&case(
        vec![],
        vec![
            json!({"app_sessions": [{"client_name": "Codex"}]}),
            json!({"session_id": null, "app_sessions": [{"client_name": "Codex"}]}),
            device("", "Codex"),
        ],
        vec![],
    ));
    for row in output["sessions"].as_array().unwrap() {
        assert_eq!(row["status"], "UNAVAILABLE");
        assert!(row["session_ref"].is_null());
        assert!(row["owner"].is_null());
    }
    assert_eq!(output["candidate_count"], 0);
}

#[test]
fn only_an_explicit_normalized_codex_client_name_is_a_candidate() {
    let output = report(&case(
        vec![],
        vec![
            device("a", " CodEX "),
            device("b", "ChatGPT"),
            device("c", "Codex CLI"),
            device("d", "not-codex"),
            json!({"session_id": "e", "platform": "Codex"}),
        ],
        vec![],
    ));
    assert_eq!(output["candidate_count"], 1);
    assert_eq!(output["sessions"][0]["status"], "CANDIDATE");
    for row in &output["sessions"].as_array().unwrap()[1..] {
        assert_eq!(row["status"], "UNBOUND");
    }
}

#[test]
fn missing_or_disabled_snapshots_are_rejected_instead_of_becoming_empty_sets() {
    for missing in ["before", "after"] {
        for invalid in [
            Value::Null,
            json!({}),
            json!({"show_session_manager": false, "devices": []}),
        ] {
            let mut input = case(vec![], vec![device("new", "Codex")], vec![]);
            input[missing] = invalid;
            assert_rejected(&input);
        }
        let mut input = case(vec![], vec![device("new", "Codex")], vec![]);
        input.as_object_mut().unwrap().remove(missing);
        assert_rejected(&input);
    }
}

#[test]
fn stale_records_only_say_absent_from_the_available_after_snapshot() {
    let input = case(
        vec![device("absent-session", "Codex")],
        vec![],
        vec![
            binding(
                "official-a",
                "absent-session",
                "alice",
                "desktop-a",
                "profile-a",
            ),
            binding(
                "official-b",
                "other-account-session",
                "bob",
                "desktop-b",
                "profile-b",
            ),
        ],
    );
    let output = report(&input);
    assert_eq!(output["stale_bindings"].as_array().unwrap().len(), 1);
    assert_eq!(output["stale_bindings"][0]["owner"]["user_id"], "alice");
    assert!(!serde_json::to_string(&output)
        .unwrap()
        .contains("absent-session"));
    let mut unavailable = input;
    unavailable["after"]["show_session_manager"] = json!(false);
    assert_rejected(&unavailable);
}

#[test]
fn irrelevant_official_fields_are_discarded_not_used_for_attribution() {
    let mut new = device("secret-session-id", "Codex");
    new["ip_address"] = json!("192.0.2.89");
    new["city"] = json!("private-city");
    new["device_name"] = json!("private-device-name");
    new["token"] = json!("secret-token-example");
    new["platform"] = json!("windows");
    let input = case(vec![], vec![new], vec![]);
    let parsed = parse_case(&serde_json::to_vec(&input).unwrap()).unwrap();
    let sanitized = serde_json::to_string(&parsed).unwrap();
    let output = serde_json::to_string(&report(&input)).unwrap();
    for secret in [
        "192.0.2.89",
        "private-city",
        "private-device-name",
        "secret-token-example",
    ] {
        assert!(!sanitized.contains(secret));
        assert!(!output.contains(secret));
    }
    assert!(!output.contains("secret-session-id"));
}

#[test]
fn malformed_json_and_invalid_fields_return_fixed_safe_error_codes() {
    for bytes in [
        br#"{"token":"super-secret-token", broken}"#.as_slice(),
        br#"{"schema_version":"super-secret-token"}"#.as_slice(),
    ] {
        let error = parse_case(bytes).unwrap_err();
        let display = error.to_string();
        assert!(!display.contains("super-secret-token"));
        assert!(display.starts_with("E_"));
        assert!(display.bytes().all(|b| b.is_ascii_uppercase() || b == b'_'));
        assert!(!format!("{error:?}").contains("super-secret-token"));
    }
}

#[test]
fn schema_and_identifier_validation_prevent_ambiguous_or_unbounded_values() {
    let base = case(vec![], vec![], vec![]);
    for invalid in [json!(0), json!(2), json!(-1), json!(null)] {
        let mut input = base.clone();
        input["schema_version"] = invalid;
        assert_rejected(&input);
    }
    for field in ["user_id", "device_id", "profile_id", "official_account_id"] {
        for bad in [
            String::new(),
            " ".to_owned(),
            "x\ny".to_owned(),
            "x".repeat(257),
        ] {
            let mut input = base.clone();
            input["context"][field] = json!(bad);
            assert_rejected(&input);
        }
    }
    for bad in [" ".to_owned(), "x\ny".to_owned(), "x".repeat(257)] {
        assert_rejected(&case(vec![], vec![device(&bad, "Codex")], vec![]));
    }
    let mut accepted = base;
    accepted["context"]["user_id"] = json!("x".repeat(256));
    assert!(parse_case(&serde_json::to_vec(&accepted).unwrap()).is_ok());
}

#[test]
fn bindings_require_operator_evidence_and_valid_nonempty_identifiers() {
    for field in [
        "official_account_id",
        "session_id",
        "user_id",
        "device_id",
        "profile_id",
        "confirmed_by",
    ] {
        let mut record = binding("official-a", "s", "alice", "desktop-a", "profile-a");
        record[field] = json!("");
        assert_rejected(&case(vec![], vec![], vec![record]));
    }
    let mut record = binding("official-a", "s", "alice", "desktop-a", "profile-a");
    record["evidence"] = json!("same_ip");
    assert_rejected(&case(vec![], vec![], vec![record]));
}

#[test]
fn input_byte_and_row_limits_are_enforced() {
    assert!(parse_case(&vec![b' '; 1024 * 1024 + 1]).is_err());
    for field in ["before", "after"] {
        let mut input = case(vec![], vec![], vec![]);
        input[field]["devices"] = Value::Array(
            (0..1001)
                .map(|i| device(&format!("s-{i}"), "Codex"))
                .collect(),
        );
        assert_rejected(&input);
    }
    let record = binding("official-a", "s", "alice", "desktop-a", "profile-a");
    assert_rejected(&case(vec![], vec![], vec![record; 1001]));
}

#[test]
fn exact_byte_and_row_limit_boundaries_are_accepted() {
    let input = case(
        vec![],
        (0..1000)
            .map(|i| device(&format!("s-{i}"), "Codex"))
            .collect(),
        vec![],
    );
    assert_eq!(report(&input)["candidate_count"], 1000);
    let mut bytes = serde_json::to_vec(&case(vec![], vec![], vec![])).unwrap();
    bytes.resize(1024 * 1024, b' ');
    assert!(parse_case(&bytes).is_ok());
}

#[test]
fn confirmation_appends_an_operator_record_and_reanalysis_reports_it() {
    let input = case(vec![], vec![device("confirm-me", "Codex")], vec![]);
    let parsed = parse_case(&serde_json::to_vec(&input).unwrap()).unwrap();
    let reference = single_ref(&input);
    let confirmed = confirm_owner(&parsed, &reference, "operator-jane").unwrap();
    let output = serde_json::to_value(analyze(&confirmed).unwrap()).unwrap();
    assert_eq!(output["sessions"][0]["status"], "RECORDED_BINDING");
    assert_eq!(output["sessions"][0]["owner"]["user_id"], "alice");
    assert_eq!(output["enforcement_allowed"], false);
    let persisted = serde_json::to_value(&confirmed).unwrap();
    assert_eq!(persisted["bindings"].as_array().unwrap().len(), 1);
    assert_eq!(persisted["bindings"][0]["evidence"], "operator_confirmed");
    assert_eq!(persisted["bindings"][0]["confirmed_by"], "operator-jane");
    let again = confirm_owner(&confirmed, &reference, "operator-jane").unwrap();
    assert_eq!(serde_json::to_value(again).unwrap(), persisted);
    assert!(serde_json::to_value(&parsed).unwrap()["bindings"]
        .as_array()
        .unwrap()
        .is_empty());
}

#[test]
fn an_existing_unbound_codex_session_can_be_manually_recorded() {
    let existing = device("historical", "Codex");
    let input = case(vec![existing.clone()], vec![existing], vec![]);
    let parsed = parse_case(&serde_json::to_vec(&input).unwrap()).unwrap();
    assert!(confirm_owner(&parsed, &single_ref(&input), "operator-jane").is_ok());
}

#[test]
fn confirmation_rejects_conflicts_other_owners_noncodex_and_invalid_operator() {
    let examples = [
        case(
            vec![],
            vec![device("s", "Codex"), device("s", "Codex")],
            vec![],
        ),
        case(
            vec![],
            vec![device("s", "Codex")],
            vec![binding("official-a", "s", "bob", "desktop-b", "profile-b")],
        ),
        case(vec![], vec![device("s", "ChatGPT")], vec![]),
    ];
    for input in examples {
        let parsed = parse_case(&serde_json::to_vec(&input).unwrap()).unwrap();
        assert!(confirm_owner(&parsed, &single_ref(&input), "operator-jane").is_err());
    }
    let input = case(vec![], vec![device("s", "Codex")], vec![]);
    let parsed = parse_case(&serde_json::to_vec(&input).unwrap()).unwrap();
    for bad in ["", " ", "operator\nname"] {
        assert!(confirm_owner(&parsed, &single_ref(&input), bad).is_err());
    }
    assert!(confirm_owner(&parsed, "unknown-ref", "operator-jane").is_err());
}

#[test]
fn confirmation_cannot_use_an_account_scoped_reference_from_another_account() {
    let mut input = case(vec![], vec![device("s", "Codex")], vec![]);
    let reference = single_ref(&input);
    input["context"]["official_account_id"] = json!("official-b");
    let parsed = parse_case(&serde_json::to_vec(&input).unwrap()).unwrap();
    assert!(confirm_owner(&parsed, &reference, "operator-jane").is_err());
}

#[test]
fn fingerprints_are_deterministic_full_hex_and_use_an_unambiguous_separator() {
    let input = case(vec![], vec![device("s", "Codex")], vec![]);
    let reference = single_ref(&input);
    assert_eq!(reference.len(), 64);
    assert!(reference
        .bytes()
        .all(|b| b.is_ascii_hexdigit() && !b.is_ascii_uppercase()));
    assert_eq!(reference, single_ref(&input));
    let mut left = case(vec![], vec![device("bc", "Codex")], vec![]);
    left["context"]["official_account_id"] = json!("a");
    let mut right = case(vec![], vec![device("c", "Codex")], vec![]);
    right["context"]["official_account_id"] = json!("ab");
    assert_ne!(single_ref(&left), single_ref(&right));
}

#[test]
fn attribution_does_not_depend_on_snapshot_or_binding_order() {
    let mut input = case(
        vec![device("old-a", "Codex"), device("old-b", "ChatGPT")],
        vec![
            device("old-a", "Codex"),
            device("new-a", "Codex"),
            device("new-b", "Codex"),
        ],
        vec![
            binding("official-a", "old-a", "alice", "desktop-a", "profile-a"),
            binding("official-a", "new-b", "bob", "desktop-b", "profile-b"),
        ],
    );
    let rows = |output: Value| -> BTreeMap<String, Value> {
        output["sessions"]
            .as_array()
            .unwrap()
            .iter()
            .map(|row| (row["session_ref"].as_str().unwrap().to_owned(), row.clone()))
            .collect()
    };
    let expected = rows(report(&input));
    input["before"]["devices"].as_array_mut().unwrap().reverse();
    input["after"]["devices"].as_array_mut().unwrap().reverse();
    input["bindings"].as_array_mut().unwrap().reverse();
    assert_eq!(expected, rows(report(&input)));
}
