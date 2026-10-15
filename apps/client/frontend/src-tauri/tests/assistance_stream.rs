#[path = "../src/assistance_stream.rs"]
mod assistance_stream;

use assistance_stream::{AssistanceSignal, EventDecoder, Subscriptions};
use std::sync::{Arc, Mutex};
use uuid::Uuid;

#[test]
fn split_sse_frames_forward_only_bounded_empty_state_signals() {
    let mut decoder = EventDecoder::default();
    assert!(decoder.feed(b"event: state_ch").unwrap().is_empty());
    assert_eq!(decoder.feed(b"anged\r\ndata: {}\r\n\r\n").unwrap(), vec![AssistanceSignal::StateChanged]);
    assert!(decoder.feed(b"event: heartbeat\ndata: {}\n\n: keepalive\n\n").unwrap().is_empty());
    assert_eq!(decoder.feed(b"event: connected\ndata: {}\n\n").unwrap(), vec![AssistanceSignal::Connected]);
}

#[test]
fn unexpected_payloads_and_oversized_frames_never_reach_the_renderer() {
    for bytes in [
        b"event: state_changed\ndata: {\"code\":\"synthetic-otp\"}\n\n".to_vec(),
        b"event: secret\ndata: {}\n\n".to_vec(),
        b"event: state_changed\ndata: []\n\n".to_vec(),
        vec![b'x'; 4097],
        vec![0xff, b'\n', b'\n'],
    ] {
        assert!(EventDecoder::default().feed(&bytes).is_err());
    }
    assert_eq!(serde_json::to_string(&AssistanceSignal::StateChanged).unwrap(), r#"{"type":"state_changed"}"#);
}

#[test]
fn stopping_an_old_subscription_cannot_abort_its_replacement() {
    let subscriptions = Subscriptions::default();
    let calls = Arc::new(Mutex::new(Vec::new()));
    let first = Uuid::new_v4(); let second = Uuid::new_v4();
    let first_calls=Arc::clone(&calls);
    subscriptions.replace(first, Box::new(move || first_calls.lock().unwrap().push(first))).unwrap();
    let second_calls=Arc::clone(&calls);
    subscriptions.replace(second, Box::new(move || second_calls.lock().unwrap().push(second))).unwrap();
    subscriptions.stop(first);
    assert_eq!(*calls.lock().unwrap(),vec![first]);
    subscriptions.stop(second); subscriptions.stop(second);
    assert_eq!(*calls.lock().unwrap(),vec![first,second]);
}

#[test]
fn dropping_native_subscription_state_closes_only_its_owned_task() {
    let calls=Arc::new(Mutex::new(0));
    {let subscriptions=Subscriptions::default();let calls=Arc::clone(&calls);subscriptions.replace(Uuid::new_v4(),Box::new(move || *calls.lock().unwrap()+=1)).unwrap();}
    assert_eq!(*calls.lock().unwrap(),1);
}
