package com.pingche.web.auth;

import java.util.UUID;

/** Published after the system logout transaction has committed. Contains no refresh token. */
public record SystemLogoutEvent(UUID userId) { }
