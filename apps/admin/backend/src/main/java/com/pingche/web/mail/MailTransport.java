package com.pingche.web.mail;

import java.util.List;

public interface MailTransport {
    Connection open(Settings settings);
    record Settings(String host, int port, String username, String folder, MailboxConfig.AuthMode authMode,
            String credential) {
        @Override public String toString() { return "MailTransport.Settings[redacted]"; }
    }
    record Cursor(long uidValidity, long nextUid) { }
    record Candidate(long uid, LoginMailMessage message) {
        @Override public String toString() { return "Candidate[uid=" + uid + "]"; }
    }
    record Batch(long uidValidity, long nextUid, List<Candidate> messages) {
        @Override public String toString() { return "Batch[redacted]"; }
    }
    interface Connection extends AutoCloseable {
        Cursor baseline();
        Batch poll(long nextUid);
        @Override void close();
    }
}
