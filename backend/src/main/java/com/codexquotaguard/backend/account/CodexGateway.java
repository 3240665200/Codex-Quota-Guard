package com.codexquotaguard.backend.account;

import com.codexquotaguard.backend.codex.DeviceLogin;
import com.codexquotaguard.backend.quota.AccountState;
import com.codexquotaguard.backend.quota.RawRateLimitBucket;

import java.util.Map;

/** Minimal boundary around the locally running official Codex app-server. */
public interface CodexGateway {

    AccountState readAccount();

    Map<String, RawRateLimitBucket> readRateLimits();

    DeviceLogin startDeviceLogin();

    void logout();
}
