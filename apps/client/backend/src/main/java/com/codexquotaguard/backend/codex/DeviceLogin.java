package com.codexquotaguard.backend.codex;

public record DeviceLogin(
        String type,
        String loginId,
        String verificationUrl,
        String userCode
) {
}
