use super::*;
use std::collections::BTreeSet;

pub(super) fn send(
    client: &reqwest::blocking::Client,
    config: &MeterConfig,
    credential: &MeterCredential,
    events: &[UsageEvent],
    ensure_allowed: impl FnMut() -> Result<(), String>,
) -> Result<(), String> {
    with_device_recovery(client, config, credential, ensure_allowed, || {
        let mut response = client
            .post(meter_endpoint(config, "/api/v1/meter/usage/reviews")?)
            .header("X-Quota-Guard-Meter", &credential.device_token)
            .timeout(Duration::from_secs(3))
            .json(&serde_json::json!({"events":events}))
            .send()
            .map_err(|_| "METER_REVIEW_UPLOAD_FAILED")?;
        match response.status().as_u16() {
            200..=299 => {}
            401 | 403 => return Err("METER_PAIRING_EXPIRED".into()),
            404 | 405 => return Err("METER_REVIEW_SERVER_UPGRADE_REQUIRED".into()),
            _ => return Err("METER_REVIEW_UPLOAD_FAILED".into()),
        }
        let mut body = Vec::new();
        response
            .by_ref()
            .take(32769)
            .read_to_end(&mut body)
            .map_err(|_| "METER_REVIEW_RECEIPT_INVALID")?;
        if body.len() > 32768 {
            return Err("METER_REVIEW_RECEIPT_INVALID".into());
        }
        validate_receipt(&body, events)
    })
}

fn validate_receipt(body: &[u8], events: &[UsageEvent]) -> Result<(), String> {
    #[derive(Deserialize)]
    #[serde(rename_all = "camelCase")]
    struct Receipt {
        quarantined_event_ids: Vec<String>,
    }
    let receipt: Receipt =
        serde_json::from_slice(body).map_err(|_| "METER_REVIEW_RECEIPT_INVALID")?;
    let expected: BTreeSet<_> = events.iter().map(|event| event.event_id.as_str()).collect();
    let received: BTreeSet<_> = receipt
        .quarantined_event_ids
        .iter()
        .map(String::as_str)
        .collect();
    if expected != received || receipt.quarantined_event_ids.len() != events.len() {
        return Err("METER_REVIEW_RECEIPT_INVALID".into());
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn only_complete_matching_receipts_allow_queue_acknowledgement() {
        let event = UsageEvent {
            event_id: "codex-local-usage:v1:".to_owned() + &"a".repeat(64),
            occurred_at: "2026-01-01T00:00:00Z".into(),
            total_tokens: 1,
            source: "TOKEN_USAGE_RECORD".into(),
            model: None,
            reasoning_effort: None,
            input_tokens: None,
            cached_input_tokens: None,
            cache_write_input_tokens: None,
            output_tokens: None,
            reasoning_output_tokens: None,
            service_tier: None,
        };
        let receipt =
            serde_json::to_vec(&serde_json::json!({"quarantinedEventIds":[event.event_id]}))
                .unwrap();
        assert!(validate_receipt(&receipt, std::slice::from_ref(&event)).is_ok());
        for bad in [
            b"{}".as_slice(),
            b"{\"quarantinedEventIds\":[]}",
            b"{\"quarantinedEventIds\":[\"other\"]}",
        ] {
            assert_eq!(
                validate_receipt(bad, std::slice::from_ref(&event)).unwrap_err(),
                "METER_REVIEW_RECEIPT_INVALID"
            );
        }
    }
}
