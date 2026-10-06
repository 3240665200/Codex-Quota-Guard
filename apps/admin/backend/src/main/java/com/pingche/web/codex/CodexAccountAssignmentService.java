package com.pingche.web.codex;

import com.pingche.web.auth.DomainException;
import com.pingche.web.auth.RefreshSessionRepository;
import com.pingche.web.quota.UserQuotaUsageRepository;
import com.pingche.web.user.User;
import com.pingche.web.user.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Keeps product-user access separate from the centrally managed Codex account.
 * The service only ever returns public account metadata; the credential cipher is
 * not read by this code path.
 */
@Service
public class CodexAccountAssignmentService {

    private final UserRepository users;
    private final CodexAccountRepository accounts;
    private final RefreshSessionRepository sessions;
    private final UserQuotaUsageRepository quotaUsage;
    private final Clock clock;

    public CodexAccountAssignmentService(
            UserRepository users,
            CodexAccountRepository accounts,
            RefreshSessionRepository sessions,
            UserQuotaUsageRepository quotaUsage,
            Clock clock
    ) {
        this.users = users;
        this.accounts = accounts;
        this.sessions = sessions;
        this.quotaUsage = quotaUsage;
        this.clock = clock;
    }

    @Transactional
    public ManagedCodexAccountResponse assign(UUID userId, UUID codexAccountId) {
        // Account deletion/update locks the account first. Preserve that order
        // before taking the user lock to avoid an account-versus-user cycle.
        CodexAccount account = accounts.findByIdForUpdate(codexAccountId)
                .orElseThrow(this::codexAccountNotFound);
        if (account.getStatus() != CodexAccount.Status.ACTIVE) {
            throw new DomainException(
                    "CODEX_ACCOUNT_UNAVAILABLE",
                    "Only an active Codex account can be assigned",
                    HttpStatus.CONFLICT
            );
        }
        User user = users.findByIdForUpdate(userId).orElseThrow(this::userNotFound);

        if (user.assignCodexAccount(account.getId())) {
            // A snapshot belongs to the previously assigned upstream account;
            // never let it influence a new assignment.
            quotaUsage.deleteByUserId(userId);
            invalidateSessions(user);
        }
        return ManagedCodexAccountResponse.from(account);
    }

    @Transactional
    public void unassign(UUID userId) {
        User user = users.findByIdForUpdate(userId).orElseThrow(this::userNotFound);
        if (user.unassignCodexAccount()) {
            quotaUsage.deleteByUserId(userId);
            invalidateSessions(user);
        }
    }

    @Transactional(readOnly = true)
    public ClientAccountOverviewResponse currentOverview(UUID userId) {
        User user = users.findById(userId).orElseThrow(this::userNotFound);
        UUID assignedCodexAccountId = user.getAssignedCodexAccountId();
        if (assignedCodexAccountId == null) {
            return ClientAccountOverviewResponse.unassigned(user);
        }

        return accounts.findById(assignedCodexAccountId)
                .map(account -> ClientAccountOverviewResponse.assigned(user, account))
                .orElseGet(() -> ClientAccountOverviewResponse.unassigned(user));
    }

    private void invalidateSessions(User user) {
        user.invalidateSessions();
        users.saveAndFlush(user);
        sessions.revokeAllForUser(user.getId(), Instant.now(clock));
    }

    private DomainException userNotFound() {
        return new DomainException(
                "USER_NOT_FOUND",
                "User not found",
                HttpStatus.NOT_FOUND
        );
    }

    private DomainException codexAccountNotFound() {
        return new DomainException(
                "CODEX_ACCOUNT_NOT_FOUND",
                "Codex account was not found",
                HttpStatus.NOT_FOUND
        );
    }
}
