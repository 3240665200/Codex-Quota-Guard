package com.codexquotaguard.backend.account;

import com.codexquotaguard.backend.codex.DeviceLogin;
import com.codexquotaguard.backend.quota.AccountState;
import com.codexquotaguard.backend.quota.QuotaSnapshot;
import com.codexquotaguard.backend.quota.QuotaSnapshotFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.function.Supplier;

@Service
public class CodexAccountService {

    private final CodexGateway gateway;
    private final String roomId;
    private final String memberId;
    private final String displayName;
    private final Supplier<Instant> clock;

    @Autowired
    public CodexAccountService(
            CodexGateway gateway,
            @Value("${quota-guard.local-room-id:local_room}") String roomId,
            @Value("${quota-guard.local-member-id:local_member}") String memberId,
            @Value("${quota-guard.local-display-name:This device}") String displayName
    ) {
        this(gateway, roomId, memberId, displayName, Instant::now);
    }

    public CodexAccountService(
            CodexGateway gateway,
            String roomId,
            String memberId,
            String displayName,
            Supplier<Instant> clock
    ) {
        if (gateway == null || clock == null) {
            throw new IllegalArgumentException("Codex gateway and clock are required.");
        }
        this.gateway = gateway;
        this.roomId = roomId;
        this.memberId = memberId;
        this.displayName = displayName;
        this.clock = clock;
    }

    public AccountOverview readOverview() {
        AccountState account = gateway.readAccount();
        Map<String, com.codexquotaguard.backend.quota.RawRateLimitBucket> rateLimits = account.authenticated()
                ? gateway.readRateLimits()
                : Map.of();
        QuotaSnapshot snapshot = QuotaSnapshotFactory.create(
                roomId,
                memberId,
                displayName,
                account,
                rateLimits,
                clock.get()
        );
        return new AccountOverview(account.authenticated(), account.planType(), snapshot);
    }

    public DeviceLogin startDeviceLogin() {
        return gateway.startDeviceLogin();
    }

    public void logout() {
        gateway.logout();
    }
}
