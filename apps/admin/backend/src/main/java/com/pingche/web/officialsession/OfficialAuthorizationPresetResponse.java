package com.pingche.web.officialsession;

public record OfficialAuthorizationPresetResponse(boolean available, String requestedAccountId,
        String revision, String errorCode) {}
