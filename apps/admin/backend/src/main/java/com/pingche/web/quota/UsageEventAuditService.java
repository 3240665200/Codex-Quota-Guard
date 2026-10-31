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
    private final UsageBillingOverrides overrides;

    public UsageEventAuditService(UserRepository users, CodexUsageEventRepository events,
            UsageBillingService billing, UsageBillingOverrides overrides) {
        this.users = users;
        this.events = events;
        this.billing = billing;
        this.overrides = overrides;
    }

    @Transactional(readOnly = true)
    public UsageEventAuditPage list(UUID userId, int page, int size) {
        if (!users.existsById(userId)) {
            throw new DomainException("USER_NOT_FOUND", "User not found", HttpStatus.NOT_FOUND);
        }
        var pageData = events.findByUserIdOrderByReceivedAtDescIdDesc(userId, PageRequest.of(page,size));
        var corrected=overrides.effective(pageData.getContent()).stream().collect(Collectors.toMap(CodexUsageEvent::getId,event -> event));
        return UsageEventAuditPage.from(pageData.map(event -> corrected.get(event.getId())), billing.entries(pageData.getContent().stream().map(CodexUsageEvent::getId).toList()));
    }
}
