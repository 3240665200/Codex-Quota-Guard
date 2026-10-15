package com.pingche.web.officialsession;

import java.time.Instant;
import java.util.List;

public record OfficialSessionInfo(String renderId, String sessionId, String platform, String displayName,
        String deviceModel, String humanReadableDescription, String osVersion, Boolean currentDevice, Boolean trustedDevice,
        Instant lastSignedInAt, Instant sessionCreatedAt, String city, String regionCode, String country, List<String> applications) {
    public OfficialSessionInfo { applications = List.copyOf(applications); }
}
