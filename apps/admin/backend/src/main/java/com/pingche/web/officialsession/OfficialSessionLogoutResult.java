package com.pingche.web.officialsession;

import java.util.List;
import java.util.UUID;

public record OfficialSessionLogoutResult(UUID planId, String mode, String status, boolean requiresReauthorization,
        List<Item> results, String message) {
    public record Item(String sessionId, String status, String errorCode) { }
}
