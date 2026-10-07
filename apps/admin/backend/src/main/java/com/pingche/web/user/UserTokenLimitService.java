package com.pingche.web.user;

import com.pingche.web.auth.DomainException;
import com.pingche.web.auth.RefreshSessionRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserTokenLimitService {

    private final UserRepository users;
    private final RefreshSessionRepository sessions;
    private final Clock clock;

    public UserTokenLimitService(UserRepository users, RefreshSessionRepository sessions, Clock clock) {
        this.users = users;
        this.sessions = sessions;
        this.clock = clock;
    }

    @Transactional
    public UserResponse update(UUID userId, UserTokenLimitRequest request) {
        User user = users.findByIdForUpdate(userId).orElseThrow(() -> new DomainException(
                "USER_NOT_FOUND", "User not found", HttpStatus.NOT_FOUND));
        Instant now = clock.instant();
        if (user.updateTokenLimits(request.fiveHourTokenLimit(), request.weeklyTokenLimit(), now)) {
            user.invalidateSessions();
            sessions.revokeAllForUser(userId, now);
            users.saveAndFlush(user);
        }
        return UserResponse.from(user);
    }
}
