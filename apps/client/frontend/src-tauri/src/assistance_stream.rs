use std::sync::Mutex;
use std::time::Duration;

use reqwest::{header, redirect, Client};
use serde::Serialize;
use tauri::ipc::Channel;
use uuid::Uuid;

#[derive(Clone, Copy, Debug, Eq, PartialEq, Serialize)]
#[serde(tag = "type", rename_all = "snake_case")]
pub(crate) enum AssistanceSignal {
    Connected,
    StateChanged,
    Disconnected,
}

#[derive(Default)]
pub(crate) struct EventDecoder {
    line: Vec<u8>,
    event: Option<String>,
    data: String,
    frame_bytes: usize,
}

impl EventDecoder {
    pub(crate) fn feed(&mut self, bytes: &[u8]) -> Result<Vec<AssistanceSignal>, ()> {
        let mut signals = Vec::new();
        for byte in bytes {
            self.frame_bytes += 1;
            if self.frame_bytes > 4096 || self.line.len() > 1024 || signals.len() >= 64 {
                return Err(());
            }
            if *byte != b'\n' {
                self.line.push(*byte);
                continue;
            }
            let line = std::str::from_utf8(&self.line)
                .map_err(|_| ())?
                .trim_end_matches('\r');
            if line.is_empty() {
                if let Some(event) = self.event.take() {
                    let data: serde_json::Value =
                        serde_json::from_str(&self.data).map_err(|_| ())?;
                    if !data.as_object().is_some_and(|object| object.is_empty()) {
                        return Err(());
                    }
                    match event.as_str() {
                        "connected" => signals.push(AssistanceSignal::Connected),
                        "state_changed" => signals.push(AssistanceSignal::StateChanged),
                        "heartbeat" => {}
                        _ => return Err(()),
                    }
                } else if !self.data.is_empty() {
                    return Err(());
                }
                self.data.clear();
                self.frame_bytes = 0;
            } else if line.starts_with(':') {
                // Bounded SSE keepalive comments carry no state or user data.
            } else if let Some(value) = line.strip_prefix("event:") {
                if self.event.is_some() {
                    return Err(());
                }
                self.event = Some(value.trim().to_owned());
            } else if let Some(value) = line.strip_prefix("data:") {
                if !self.data.is_empty() {
                    self.data.push('\n');
                }
                self.data.push_str(value.trim());
            } else {
                return Err(());
            }
            self.line.clear();
        }
        Ok(signals)
    }
}

type Cancel = Box<dyn FnOnce() + Send>;

#[derive(Default)]
pub(crate) struct Subscriptions {
    active: Mutex<Option<(Uuid, Cancel)>>,
}

impl Subscriptions {
    pub(crate) fn replace(&self, id: Uuid, cancel: Cancel) -> Result<(), ()> {
        let previous = match self.active.lock() {
            Ok(mut active) => active.replace((id, cancel)),
            Err(_) => {
                cancel();
                return Err(());
            }
        };
        if let Some((_, cancel)) = previous {
            cancel();
        }
        Ok(())
    }

    pub(crate) fn stop(&self, id: Uuid) {
        let previous = self.active.lock().ok().and_then(|mut active| {
            if active.as_ref().is_some_and(|(current, _)| *current == id) {
                active.take()
            } else {
                None
            }
        });
        if let Some((_, cancel)) = previous {
            cancel();
        }
    }

    pub(crate) fn clear(&self) {
        let previous = self.active.lock().ok().and_then(|mut active| active.take());
        if let Some((_, cancel)) = previous {
            cancel();
        }
    }

    pub(crate) fn start(
        &self,
        id: Uuid,
        url: String,
        token: String,
        channel: Channel<AssistanceSignal>,
    ) -> Result<(), ()> {
        let disconnect = channel.clone();
        let task = tauri::async_runtime::spawn(async move {
            let _ = receive(url, token, channel.clone()).await;
            let _ = channel.send(AssistanceSignal::Disconnected);
        });
        self.replace(
            id,
            Box::new(move || {
                task.abort();
                let _ = disconnect.send(AssistanceSignal::Disconnected);
            }),
        )
    }
}

impl Drop for Subscriptions {
    fn drop(&mut self) {
        self.clear();
    }
}

async fn receive(url: String, token: String, channel: Channel<AssistanceSignal>) -> Result<(), ()> {
    let client = Client::builder()
        .no_proxy()
        .connect_timeout(Duration::from_secs(5))
        .read_timeout(Duration::from_secs(45))
        .redirect(redirect::Policy::none())
        .build()
        .map_err(|_| ())?;
    let mut response = client
        .get(url)
        .bearer_auth(token)
        .header(header::ACCEPT, "text/event-stream")
        .send()
        .await
        .map_err(|_| ())?;
    if response.status() != reqwest::StatusCode::OK
        || !response
            .headers()
            .get(header::CONTENT_TYPE)
            .and_then(|value| value.to_str().ok())
            .is_some_and(|value| {
                value
                    .split(';')
                    .next()
                    .unwrap_or("")
                    .trim()
                    .eq_ignore_ascii_case("text/event-stream")
            })
    {
        return Err(());
    }
    channel.send(AssistanceSignal::Connected).map_err(|_| ())?;
    let mut decoder = EventDecoder::default();
    while let Some(bytes) = response.chunk().await.map_err(|_| ())? {
        for signal in decoder.feed(&bytes)? {
            if signal == AssistanceSignal::StateChanged {
                channel.send(signal).map_err(|_| ())?;
            }
        }
    }
    Ok(())
}
