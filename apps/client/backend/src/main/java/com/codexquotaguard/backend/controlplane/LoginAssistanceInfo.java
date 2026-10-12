package com.codexquotaguard.backend.controlplane;

import java.util.UUID;

/** Only the current user's assigned mailbox identity, never connection credentials. */
public record LoginAssistanceInfo(boolean available, String reasonCode, UUID officialAccountId,
        String accountLabel, String recipientEmail) {
    public LoginAssistanceInfo {
        if (reasonCode != null && !reasonCode.matches("[A-Z_]{1,80}")) invalid();
        if (accountLabel != null && accountLabel.length() > 120) invalid();
        if (recipientEmail != null && (recipientEmail.length() > 320 || recipientEmail.contains("\n"))) invalid();
        if (available && (officialAccountId == null || accountLabel == null || recipientEmail == null)) invalid();
    }

    private static void invalid() { throw new IllegalArgumentException("Invalid login assistance information."); }
}
