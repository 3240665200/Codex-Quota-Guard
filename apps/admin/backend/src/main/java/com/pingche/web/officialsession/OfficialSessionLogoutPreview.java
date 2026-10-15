package com.pingche.web.officialsession;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record OfficialSessionLogoutPreview(UUID planId, UUID officialAccountId, String mode, Instant expiresAt,
        List<Target> targets, List<Exclusion> excluded, List<String> warnings) {
    public record Target(String rowId, String sessionId, String displayName, String platform, Boolean trustedDevice, Boolean current) { }
    public record Exclusion(Target session, String reason) { }
}
