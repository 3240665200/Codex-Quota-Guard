#[path = "../src/assistance_stream.rs"]
mod assistance_stream;

use assistance_stream::{AssistanceSignal, EventDecoder, Subscriptions};
use std::sync::{Arc, Mutex};
use uuid::Uuid;

#[test]
fn split_sse_frames_forward_only_bounded_empty_state_signals() {
    let mut decoder = EventDecoder::default();
    assert!(decoder.feed(b"event: state_ch").unwrap().is_empty());
    assert_eq!(
        decoder.feed(b"anged\r\ndata: {}\r\n\r\n").unwrap(),
        vec![AssistanceSignal::StateChanged]
    );
    assert!(decoder
        .feed(b"event: heartbeat\ndata: {}\n\n: keepalive\n\n")
        .unwrap()
        .is_empty());
    assert_eq!(
        decoder.feed(b"event: connected\ndata: {}\n\n").unwrap(),
        vec![AssistanceSignal::Connected]
    );
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
    assert_eq!(
        serde_json::to_string(&AssistanceSignal::StateChanged).unwrap(),
        r#"{"type":"state_changed"}"#
    );
}

#[test]
fn stopping_an_old_subscription_cannot_abort_its_replacement() {
    let subscriptions = Subscriptions::default();
    let calls = Arc::new(Mutex::new(Vec::new()));
    let first = Uuid::new_v4();
    let second = Uuid::new_v4();
    let first_calls = Arc::clone(&calls);
    subscriptions
        .replace(
            first,
            Box::new(move || first_calls.lock().unwrap().push(first)),
        )
        .unwrap();
    let second_calls = Arc::clone(&calls);
    subscriptions
        .replace(
            second,
            Box::new(move || second_calls.lock().unwrap().push(second)),
        )
        .unwrap();
    subscriptions.stop(first);
    assert_eq!(*calls.lock().unwrap(), vec![first]);
    subscriptions.stop(second);
    subscriptions.stop(second);
    assert_eq!(*calls.lock().unwrap(), vec![first, second]);
}

#[test]
fn dropping_native_subscription_state_closes_only_its_owned_task() {
    let calls = Arc::new(Mutex::new(0));
    {
        let subscriptions = Subscriptions::default();
        let calls = Arc::clone(&calls);
        subscriptions
            .replace(
                Uuid::new_v4(),
                Box::new(move || *calls.lock().unwrap() += 1),
            )
            .unwrap();
    }
    assert_eq!(*calls.lock().unwrap(), 1);
}

#[test]
fn native_http_bridge_authenticates_only_to_the_owned_loopback_and_forwards_no_body() {
    use std::io::{Read, Write};
    use std::net::TcpListener;
    use std::sync::mpsc;
    use std::time::Duration;
    use tauri::ipc::{Channel, InvokeResponseBody};
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    let port = listener.local_addr().unwrap().port();
    let server = std::thread::spawn(move || {
        let (mut socket, _) = listener.accept().unwrap();
        socket
            .set_read_timeout(Some(Duration::from_secs(3)))
            .unwrap();
        let mut request = Vec::new();
        while !request.ends_with(b"\r\n\r\n") {
            let mut bytes = [0; 1024];
            let length = socket.read(&mut bytes).unwrap();
            assert!(length > 0);
            request.extend_from_slice(&bytes[..length]);
            assert!(request.len() < 8192);
        }
        let request = String::from_utf8(request).unwrap().to_ascii_lowercase();
        assert!(request.starts_with("get /api/v1/login-assistance/events http/1.1\r\n"));
        assert!(request.contains("authorization: bearer synthetic-only\r\n"));
        let body="event: connected\ndata: {}\n\nevent: heartbeat\ndata: {}\n\nevent: state_changed\ndata: {}\n\n";
        write!(socket,"HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{}",body.len(),body).unwrap();
    });
    let (send, receive) = mpsc::channel();
    let channel = Channel::new(move |body| {
        if let InvokeResponseBody::Json(value) = body {
            send.send(value).unwrap();
        }
        Ok(())
    });
    let subscriptions = Subscriptions::default();
    subscriptions
        .start(
            Uuid::new_v4(),
            format!("http://127.0.0.1:{port}/api/v1/login-assistance/events"),
            "synthetic-only".into(),
            channel,
        )
        .unwrap();
    let frames = (0..3)
        .map(|_| receive.recv_timeout(Duration::from_secs(5)).unwrap())
        .collect::<Vec<_>>();
    assert_eq!(
        frames,
        vec![
            r#"{"type":"connected"}"#,
            r#"{"type":"state_changed"}"#,
            r#"{"type":"disconnected"}"#
        ]
    );
    server.join().unwrap();
}

#[test]
fn stopping_the_owned_native_subscription_closes_the_mock_stream_without_a_mail_request() {
    use std::io::{Read, Write};
    use std::net::TcpListener;
    use std::sync::mpsc;
    use std::time::Duration;
    use tauri::ipc::{Channel, InvokeResponseBody};
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    let port = listener.local_addr().unwrap().port();
    let server = std::thread::spawn(move || {
        let (mut socket, _) = listener.accept().unwrap();
        socket
            .set_read_timeout(Some(Duration::from_secs(5)))
            .unwrap();
        let mut request = Vec::new();
        while !request.ends_with(b"\r\n\r\n") {
            let mut byte = [0; 1];
            assert_eq!(socket.read(&mut byte).unwrap(), 1);
            request.extend_from_slice(&byte);
            assert!(request.len() < 8192);
        }
        assert!(request.starts_with(b"GET /api/v1/login-assistance/events HTTP/1.1\r\n"));
        socket.write_all(b"HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n: held-open\n\n").unwrap();
        let mut byte = [0; 1];
        assert_eq!(socket.read(&mut byte).unwrap(), 0);
    });
    let (send, receive) = mpsc::channel();
    let channel = Channel::new(move |body| {
        if let InvokeResponseBody::Json(value) = body {
            send.send(value).unwrap();
        }
        Ok(())
    });
    let subscriptions = Subscriptions::default();
    let id = Uuid::new_v4();
    subscriptions
        .start(
            id,
            format!("http://127.0.0.1:{port}/api/v1/login-assistance/events"),
            "synthetic-only".into(),
            channel,
        )
        .unwrap();
    assert_eq!(
        receive.recv_timeout(Duration::from_secs(5)).unwrap(),
        r#"{"type":"connected"}"#
    );
    subscriptions.stop(id);
    assert_eq!(
        receive.recv_timeout(Duration::from_secs(5)).unwrap(),
        r#"{"type":"disconnected"}"#
    );
    server.join().unwrap();
}
