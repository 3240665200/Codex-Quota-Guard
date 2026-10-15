package com.pingche.web.officialsession;

import java.time.Instant;

public record OfficialConnectionRefreshResponse(OfficialConnectionResponse connection, OfficialAccountInfo account,
        OfficialSessions sessions, Instant fetchedAt) { }
