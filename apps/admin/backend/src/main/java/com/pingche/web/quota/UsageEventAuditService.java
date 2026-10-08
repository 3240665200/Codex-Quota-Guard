package com.pingche.web.quota;

import com.pingche.web.auth.DomainException;
import com.pingche.web.user.UserRepository;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UsageEventAuditService {
    private final UserRepository users;
    private final CodexUsageEventRepository events;

    public UsageEventAuditService(UserRepository users, CodexUsageEventRepository events) {
        this.users = users;
        this.events = events;
    }

    @Transactional(readOnly = true)
    public UsageEventAuditPage list(UUID userId, int page, int size) {
        if (!users.existsById(userId)) {
            throw new DomainException("USER_NOT_FOUND", "User not found", HttpStatus.NOT_FOUND);
        }
        return UsageEventAuditPage.from(events.findByUserIdOrderByReceivedAtDescIdDesc(
                userId, PageRequest.of(page, size)));
    }
}
