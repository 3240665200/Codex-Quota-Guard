package com.pingche.web.mail;

import java.time.Instant;
import java.util.UUID;

public record MailListenerResponse(UUID id, MailListener.State state, Instant startedAt, Instant expiresAt,
        Instant listeningAt, Instant receivedAt, String code, String errorCode) {
    @Override public String toString() { return "MailListenerResponse[id=" + id + ", state=" + state + "]"; }
}
