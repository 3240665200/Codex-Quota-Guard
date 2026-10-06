package com.pingche.web.codex;

import static com.pingche.web.codex.CodexAuthorizationResponse.Status.*;

import com.pingche.web.auth.DomainException;
import com.pingche.web.user.User;
import com.pingche.web.user.UserRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class CodexAuthorizationService {

    private static final Logger LOGGER = LoggerFactory.getLogger(CodexAuthorizationService.class);
    private static final Duration TTL = Duration.ofMinutes(10);
    private final Map<UUID, Authorization> authorizations = new ConcurrentHashMap<>();
    private final CodexLoginGateway gateway;
    private final CodexAccountService accounts;
    private final CodexCredentialCipher cipher;
    private final UserRepository users;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final ScheduledExecutorService maintenance = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "codex-authorization-maintenance");
        thread.setDaemon(true);
        return thread;
    });

    public CodexAuthorizationService(CodexLoginGateway gateway, CodexAccountService accounts,
            CodexCredentialCipher cipher, UserRepository users, TransactionTemplate transactions, Clock clock) {
        this.gateway = gateway;
        this.accounts = accounts;
        this.cipher = cipher;
        this.users = users;
        this.transactions = transactions;
        this.clock = clock;
    }

    @PostConstruct
    void scheduleMaintenance() {
        maintenance.scheduleWithFixedDelay(this::maintain, 3, 3, TimeUnit.SECONDS);
    }

    public CodexAuthorizationResponse start(UUID ownerId, String displayName) {
        return start(ownerId, displayName, CodexLoginMode.DEVICE_CODE);
    }

    public CodexAuthorizationResponse start(UUID ownerId, String displayName, CodexLoginMode mode) {
        cipher.requireDedicatedKey();
        User owner = users.findById(ownerId).filter(this::isAdministrator)
                .orElseThrow(this::notFound);
        Authorization authorization;
        synchronized (authorizations) {
            Instant now = clock.instant();
            boolean recentAttempt = authorizations.values().stream().anyMatch(value ->
                    value.ownerId.equals(ownerId)
                            && (value.active() || value.createdAt.plusSeconds(15).isAfter(now)));
            long active = authorizations.values().stream().filter(value -> value.active() || value.login != null).count();
            if (mode == CodexLoginMode.BROWSER && authorizations.values().stream().anyMatch(value ->
                    value.mode == CodexLoginMode.BROWSER && (value.active() || value.login != null))) {
                throw new DomainException("CODEX_BROWSER_LOGIN_BUSY", "已有网页登录正在进行，请完成或取消后重试。",
                        HttpStatus.CONFLICT);
            }
            if (recentAttempt || active >= 4 || authorizations.size() >= 100) {
                throw new DomainException("CODEX_AUTHORIZATION_LIMIT", "请稍后再试；每位管理员只能同时授权一个账户。",
                        HttpStatus.TOO_MANY_REQUESTS);
            }
            authorization = new Authorization(owner, displayName.trim(), now, mode);
            authorizations.put(authorization.id, authorization);
        }
        synchronized (authorization) {
            try {
                authorization.login = gateway.open(mode);
                authorization.status = PENDING;
                advance(authorization);
            } catch (DomainException exception) {
                authorization.fail(exception.getCode(), exception.getMessage());
                release(authorization);
            } catch (Exception ignored) {
                authorization.fail("CODEX_AUTHORIZATION_UNAVAILABLE",
                        mode == CodexLoginMode.BROWSER
                                ? "无法启动官方网页登录。请检查服务器 Codex CLI，或改用设备码登录。"
                                : "无法启动官方授权。请检查服务器 Codex CLI、网络，以及账户是否开启设备码登录。" );
                release(authorization);
            }
            return authorization.response();
        }
    }

    public CodexAuthorizationResponse get(UUID ownerId, UUID id) {
        Authorization authorization = owned(ownerId, id);
        synchronized (authorization) {
            advance(authorization);
            return authorization.response();
        }
    }

    public void cancel(UUID ownerId, UUID id) {
        Authorization authorization = owned(ownerId, id);
        synchronized (authorization) {
            if (authorization.active()) authorization.status = CANCELLED;
            if (!release(authorization)) {
                throw new DomainException("CODEX_AUTHORIZATION_CLEANUP_FAILED",
                        "授权已停止，但临时文件清理失败，请联系服务器管理员。", HttpStatus.SERVICE_UNAVAILABLE);
            }
        }
    }

    private Authorization owned(UUID ownerId, UUID id) {
        Authorization authorization = authorizations.get(id);
        if (authorization == null || !authorization.ownerId.equals(ownerId)) throw notFound();
        return authorization;
    }

    private void advance(Authorization authorization) {
        if (!authorization.active() || authorization.login == null) return;
        try {
            if (!clock.instant().isBefore(authorization.expiresAt)) {
                authorization.status = EXPIRED;
                release(authorization);
                return;
            }
            if (!ownerValid(authorization, users.findById(authorization.ownerId).orElse(null))) {
                authorization.status = CANCELLED;
                release(authorization);
                return;
            }
            var authorized = authorization.login.poll();
            if (authorized.isEmpty()) return;
            // Close the official process and remove its plaintext files BEFORE persisting the account.
            if (!release(authorization)) return;
            CodexLoginGateway.AuthorizedAccount account = authorized.get();
            authorization.account = transactions.execute(ignored -> {
                User owner = users.findByIdForUpdate(authorization.ownerId).orElse(null);
                if (!ownerValid(authorization, owner)) throw notFound();
                if (!clock.instant().isBefore(authorization.expiresAt)) {
                    throw new DomainException("CODEX_AUTHORIZATION_EXPIRED", "授权已过期。", HttpStatus.GONE);
                }
                return accounts.create(new CodexAccountCreateRequest(
                        account.email(), authorization.displayName, CodexAccount.Status.ACTIVE, account.credential()));
            });
            authorization.status = SUCCEEDED;
        } catch (DomainException exception) {
            if ("CODEX_AUTHORIZATION_EXPIRED".equals(exception.getCode())) {
                authorization.status = EXPIRED;
            } else if ("CODEX_ACCOUNT_EMAIL_EXISTS".equals(exception.getCode())) {
                authorization.fail("CODEX_ACCOUNT_EMAIL_EXISTS", "此 Codex 账户已存在，请在列表中管理。" );
            } else {
                authorization.fail("CODEX_AUTHORIZATION_FAILED", "授权未保存，请重新登录管理端后重试。" );
            }
            release(authorization);
        } catch (Exception ignored) {
            authorization.fail("CODEX_AUTHORIZATION_FAILED", "授权未完成或已被拒绝，请重试。" );
            release(authorization);
        }
    }

    private boolean isAdministrator(User user) {
        return user.getRole() == User.Role.ADMIN && user.getStatus() == User.Status.ACTIVE;
    }

    private boolean ownerValid(Authorization authorization, User owner) {
        return owner != null && isAdministrator(owner) && owner.getSessionVersion() == authorization.ownerVersion;
    }

    private boolean release(Authorization authorization) {
        if (authorization.login == null) return true;
        try {
            authorization.login.close();
            authorization.login = null;
            return true;
        } catch (Exception ignored) {
            authorization.fail("CODEX_AUTHORIZATION_CLEANUP_FAILED", "临时凭据清理失败，请联系服务器管理员。" );
            LOGGER.error("Codex authorization cleanup failed; sessionId={}", authorization.id);
            return false;
        }
    }

    void maintain() {
        for (Authorization authorization : authorizations.values()) {
            synchronized (authorization) {
                advance(authorization);
                if (!authorization.active()) release(authorization);
                if (authorization.login == null && !clock.instant().isBefore(authorization.expiresAt.plusSeconds(300))) {
                    authorizations.remove(authorization.id, authorization);
                }
            }
        }
    }

    @PreDestroy
    public void close() {
        maintenance.shutdownNow();
        for (Authorization authorization : authorizations.values()) {
            synchronized (authorization) {
                authorization.status = CANCELLED;
                release(authorization);
            }
        }
        authorizations.clear();
    }

    private DomainException notFound() {
        return new DomainException("CODEX_AUTHORIZATION_NOT_FOUND", "授权会话不存在或已失效。", HttpStatus.NOT_FOUND);
    }

    private static final class Authorization {
        final UUID id = UUID.randomUUID();
        final UUID ownerId;
        final long ownerVersion;
        final String displayName;
        final Instant createdAt;
        final Instant expiresAt;
        final CodexLoginMode mode;
        volatile CodexAuthorizationResponse.Status status = STARTING;
        volatile CodexLoginGateway.Login login;
        CodexAccountResponse account;
        String errorCode;
        String message;

        Authorization(User owner, String displayName, Instant createdAt, CodexLoginMode mode) {
            ownerId = owner.getId();
            ownerVersion = owner.getSessionVersion();
            this.displayName = displayName;
            this.createdAt = createdAt;
            expiresAt = createdAt.plus(TTL);
            this.mode = mode;
        }

        boolean active() { return status == STARTING || status == PENDING; }

        void fail(String errorCode, String message) {
            status = FAILED;
            this.errorCode = errorCode;
            this.message = message;
        }

        CodexAuthorizationResponse response() {
            return new CodexAuthorizationResponse(id, status,
                    active() && login != null ? login.verificationUrl() : null,
                    active() && login != null ? login.userCode() : null,
                    expiresAt, account, errorCode, message, mode,
                    active() && login != null ? login.authUrl() : null);
        }
    }
}
