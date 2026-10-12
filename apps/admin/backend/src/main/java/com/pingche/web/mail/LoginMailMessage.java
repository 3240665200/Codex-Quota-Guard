package com.pingche.web.mail;

import java.time.Instant;
import java.util.List;

public record LoginMailMessage(List<String> senders, List<String> recipients, String subject,
        String body, Instant receivedAt) {
    @Override public String toString() { return "LoginMailMessage[redacted]"; }
}
