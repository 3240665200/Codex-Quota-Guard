package com.pingche.web.quota;

import com.pingche.web.auth.DomainException;
import com.pingche.web.user.UserRepository;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UsageEventAuditService {
    private final UserRepository users;
    private final CodexUsageEventRepository events;
    private final UsageBillingService billing;

    public UsageEventAuditService(UserRepository users, CodexUsageEventRepository events,
            UsageBillingService billing) {
        this.users = users;
        this.events = events;
        this.billing = billing;
    }

    @Transactional(readOnly = true)
    public UsageEventAuditPage list(UUID userId, int page, int size) {
        if (!users.existsById(userId)) {
            throw new DomainException("USER_NOT_FOUND", "User not found", HttpStatus.NOT_FOUND);
        }
        var pageData = events.findByUserIdOrderByReceivedAtDescIdDesc(userId, PageRequest.of(page,size));
        return UsageEventAuditPage.from(pageData, billing.entries(pageData.getContent().stream().map(CodexUsageEvent::getId).toList()));
    }
}
