package com.pingche.web.mail;

import java.util.UUID;

public record LoginAssistanceResponse(boolean available, String reasonCode, UUID officialAccountId,
        String accountLabel, String recipientEmail) { }
