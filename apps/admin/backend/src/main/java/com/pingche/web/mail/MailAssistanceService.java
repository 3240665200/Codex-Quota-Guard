package com.pingche.web.mail;

import com.pingche.web.auth.DomainException;
import com.pingche.web.auth.SystemLogoutEvent;
import com.pingche.web.quota.OfficialAccount;
import com.pingche.web.quota.OfficialAccountRepository;
import com.pingche.web.team.Team;
import com.pingche.web.team.TeamRepository;
import com.pingche.web.user.User;
import com.pingche.web.user.UserRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class MailAssistanceService implements DisposableBean {
    private static final Logger log = LoggerFactory.getLogger(MailAssistanceService.class);
    private final UserRepository users;
    private final TeamRepository teams;
    private final OfficialAccountRepository accounts;
    private final MailboxConfigRepository mailboxes;
    private final MailListenerRepository listeners;
    private final MailSecretCipher cipher;
    private final MailTransport transport;
    private final MailOAuthClient oauth;
    private final MailProviderCatalog providers;
    private final MailTargetPolicy targets;
    private final LoginMailMatcher matcher;
    private final Clock clock;
    private final TransactionTemplate transaction;
    private final LoginAssistanceAccess assistance;
    private final ThreadPoolExecutor workers;
    private final ScheduledExecutorService cleaner;
    private final Semaphore testConnections = new Semaphore(8);
    // Only connection cleanup lives in memory. Authorization, exclusivity, state and codes live in the DB.
    private final ConcurrentHashMap<UUID, MailTransport.Connection> connections = new ConcurrentHashMap<>();

    public MailAssistanceService(UserRepository users, TeamRepository teams, OfficialAccountRepository accounts,
            MailboxConfigRepository mailboxes, MailListenerRepository listeners, MailSecretCipher cipher,
            MailTransport transport, MailOAuthClient oauth, MailProviderCatalog providers, MailTargetPolicy targets,
            LoginMailMatcher matcher, Clock clock, PlatformTransactionManager transactionManager, LoginAssistanceAccess assistance) {
        this.users = users; this.teams = teams; this.accounts = accounts; this.mailboxes = mailboxes;
        this.listeners = listeners; this.cipher = cipher; this.transport = transport; this.oauth = oauth;
        this.providers = providers; this.targets = targets; this.matcher = matcher; this.clock = clock;
        transaction = new TransactionTemplate(transactionManager);
        this.assistance = assistance;
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "mail-assistance"); thread.setDaemon(true); return thread;
        };
        workers = new ThreadPoolExecutor(8, 8, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(8), factory,
                new ThreadPoolExecutor.AbortPolicy());
        cleaner = Executors.newSingleThreadScheduledExecutor(factory);
        cleaner.scheduleWithFixedDelay(this::safeCleanup, 10, 10, TimeUnit.SECONDS);
    }

    public MailboxResponse readConfig(UUID accountId) {
        return inTransaction(() -> { requireAccount(accountId, false); return configResponse(mailboxes.findById(accountId).orElse(null)); });
    }

    public MailboxResponse saveConfig(UUID accountId, MailboxRequest request) {
        cipher.encrypt("readiness", "readiness");
        validate(request);
        String sender;
        try { sender = MailSenderPolicy.normalize(request.senderEmail()); }
        catch (IllegalArgumentException ignored) {
            throw error("MAIL_CONFIG_INVALID", "Mailbox sender addresses are invalid", HttpStatus.BAD_REQUEST);
        }
        try {
            MailboxResponse response = inTransaction(() -> {
                requireAccount(accountId, true);
                String host = request.host().trim().toLowerCase(Locale.ROOT);
                String username = request.username().trim();
                String recipient = request.recipientEmail().trim().toLowerCase(Locale.ROOT);
                String key = mailboxKey(host, request.port(), username);
                if (mailboxes.existsByMailboxKeyAndOfficialAccountIdNot(key, accountId)
                        || mailboxes.existsByRecipientEmailAndOfficialAccountIdNot(recipient, accountId)) throw duplicateMailbox();
                MailboxConfig config = mailboxes.findById(accountId).orElseGet(() -> new MailboxConfig(accountId));
                boolean identityChanged = config.authMode != request.authMode()
                        || !Objects.equals(config.provider, request.provider()) || !Objects.equals(config.host, host)
                        || config.port != request.port() || !Objects.equals(config.username, username)
                        || (request.authMode() != MailboxConfig.AuthMode.PASSWORD
                            && (!Objects.equals(config.oauthClientId, emptyToNull(request.oauthClientId()))
                                || !Objects.equals(config.oauthTenant, emptyToNull(request.oauthTenant()))));
                // A blank secret preserves authorization only for the same target and OAuth application.
                if (identityChanged) { config.passwordCipher = null; config.clientSecretCipher = null; config.refreshTokenCipher = null; }
                config.enabled = request.enabled(); config.provider = request.provider(); config.host = host;
                config.port = request.port(); config.username = username; config.recipientEmail = recipient;
                config.folder = request.folder().trim(); config.senderEmail = sender;
                config.authMode = request.authMode(); config.oauthClientId = emptyToNull(request.oauthClientId());
                config.oauthTenant = emptyToNull(request.oauthTenant()); config.mailboxKey = key; config.revision = UUID.randomUUID();
                config.connectionTestUntil = null;
                if (config.authMode == MailboxConfig.AuthMode.PASSWORD) {
                    config.passwordCipher = secret(request.password(), config.passwordCipher, config.aad("password"));
                    config.clientSecretCipher = null; config.refreshTokenCipher = null; config.oauthClientId = null; config.oauthTenant = null;
                } else {
                    config.passwordCipher = null;
                    config.clientSecretCipher = secret(request.oauthClientSecret(), config.clientSecretCipher, config.aad("client-secret"));
                    config.refreshTokenCipher = secret(request.oauthRefreshToken(), config.refreshTokenCipher, config.aad("refresh-token"));
                }
                if (config.enabled && !config.hasCredentials()) throw error("MAIL_CREDENTIALS_MISSING",
                        "Mailbox authorization is incomplete", HttpStatus.BAD_REQUEST);
                invalidateAccountListeners(accountId);
                mailboxes.saveAndFlush(config);
                return configResponse(config);
            });
            closeInvalidConnections();
            return response;
        } catch (DataIntegrityViolationException ignored) { throw duplicateMailbox(); }
    }

    public void deleteConfig(UUID accountId) {
        inTransaction(() -> {
            requireAccount(accountId, true); invalidateAccountListeners(accountId); mailboxes.deleteById(accountId); return null;
        });
        closeInvalidConnections();
    }

    public TestResult testConfig(UUID accountId) {
        MailboxConfig config = inTransaction(() -> {
            requireAccount(accountId, true);
            if (listeners.findByActiveAccountId(accountId).isPresent()) throw error("MAIL_LISTENER_BUSY",
                    "A listener is already using this mailbox", HttpStatus.CONFLICT);
            MailboxConfig saved = requireConfig(accountId);
            if (saved.connectionTestUntil != null && saved.connectionTestUntil.isAfter(clock.instant()))
                throw error("MAIL_LISTENER_BUSY", "A connection test is already using this mailbox", HttpStatus.CONFLICT);
            saved.connectionTestUntil = clock.instant().plusSeconds(90);
            return saved;
        });
        boolean reserved = testConnections.tryAcquire();
        try {
            if (!reserved) throw error("MAIL_QUEUE_FULL", "Mailbox connection capacity is full", HttpStatus.SERVICE_UNAVAILABLE);
            try (MailTransport.Connection connection = transport.open(settings(config))) {
                connection.baseline();
                return new TestResult(true, "连接成功，已验证只读 IMAPS 文件夹；未读取邮件正文。");
            } catch (RuntimeException failure) {
                String message = connectionFailureMessage(failure);
                // Only fixed classification text; never log the provider exception or credentials.
                log.warn("mailbox_connection_test_failed result={}", message);
                return new TestResult(false, message);
            }
        } finally {
            if (reserved) testConnections.release();
            inTransaction(() -> {
                requireAccount(accountId, true);
                mailboxes.findById(accountId).ifPresent(saved -> {
                    if (saved.revision.equals(config.revision)) saved.connectionTestUntil = null;
                }); return null;
            });
        }
    }

    static String connectionFailureMessage(RuntimeException failure) {
        String code = failure instanceof DomainException domain && domain.getCode() != null ? domain.getCode() : "";
        return switch (code) {
            case "MAIL_DNS_FAILED" -> "无法解析邮箱服务器，请检查代理与 DNS 网络。（MAIL_DNS_FAILED）";
            case "MAIL_HOST_BLOCKED" -> "邮箱服务器未解析到允许的公网地址，请检查主机与代理设置。（MAIL_HOST_BLOCKED）";
            case "MAIL_AUTH_FAILED" -> "邮箱认证未通过，请检查完整邮箱地址、客户端授权码、IMAP 开关及服务商安全设置。（MAIL_AUTH_FAILED）";
            case "MAIL_TLS_FAILED" -> "邮箱服务器的 TLS 加密连接失败，请检查代理、网络与证书设置。（MAIL_TLS_FAILED）";
            case "MAIL_CONNECTION_TIMEOUT" -> "连接邮箱超时，请稍后重试并检查代理网络。（MAIL_CONNECTION_TIMEOUT）";
            case "MAIL_FOLDER_FAILED" -> "已登录邮箱，但无法打开邮件文件夹，请确认 INBOX 存在且允许访问。（MAIL_FOLDER_FAILED）";
            case "MAIL_CURSOR_UNAVAILABLE" -> "邮箱未提供可靠的新邮件游标，暂时无法安全监听新邮件。（MAIL_CURSOR_UNAVAILABLE）";
            case "MAIL_OAUTH_FAILED" -> "邮箱 OAuth 授权未通过，请更新对应的邮箱授权配置。（MAIL_OAUTH_FAILED）";
            default -> "无法建立邮箱连接，请检查 IMAPS 主机、端口和网络。（MAIL_CONNECTION_FAILED）";
        };
    }

    public LoginAssistanceResponse availability(UUID userId) {
        return availability(userId, null);
    }
    public LoginAssistanceResponse availability(UUID userId, String deviceId) {
        return inTransaction(() -> {
            var grant = assistance.require(userId, deviceId);
            Access access = access(userId, false);
            if (access != null && !access.account().getId().equals(grant.accountId())) throw LoginAssistanceAccess.denied();
            String reason = access == null ? "TEAM_UNAVAILABLE" : reason(access.config());
            return new LoginAssistanceResponse(reason == null, reason, reason == null ? access.account().getId() : null,
                    reason == null ? access.account().getLabel() : null, reason == null ? access.config().recipientEmail : null);
        });
    }

    public MailListenerResponse start(UUID userId) {
        return start(userId, null);
    }
    public MailListenerResponse start(UUID userId, String deviceId) {
        MailListenerResponse response;
        try {
            response = inTransaction(() -> {
                var grant = assistance.require(userId, deviceId);
                Access access = access(userId, false);
                if (access == null) throw error("MAIL_ACCESS_UNAVAILABLE", "Active team mailbox is unavailable", HttpStatus.FORBIDDEN);
                if (!access.account().getId().equals(grant.accountId())) throw LoginAssistanceAccess.denied();
                String reason = reason(access.config());
                if (reason != null) throw error(reason, "Mailbox assistance is unavailable", HttpStatus.FORBIDDEN);
                Instant now = clock.instant();
                if (access.config().connectionTestUntil != null && access.config().connectionTestUntil.isAfter(now))
                    throw error("MAIL_LISTENER_BUSY", "A connection test is already using this mailbox", HttpStatus.CONFLICT);
                listeners.findByActiveAccountId(access.account().getId()).ifPresent(active -> {
                    if (!validateListener(active)) return;
                    if (!active.expiresAt.isAfter(now)) active.stop(MailListener.State.TIMED_OUT, "MAIL_LISTENER_TIMEOUT");
                    else throw error("MAIL_LISTENER_BUSY", "A listener is already using this mailbox", HttpStatus.CONFLICT);
                });
                listeners.flush();
                if (listeners.countByOwnerUserIdAndStartedAtAfter(userId, now.minusSeconds(60)) >= 6)
                    throw error("MAIL_RATE_LIMITED", "Too many mailbox listener requests", HttpStatus.TOO_MANY_REQUESTS);
                MailListener listener = new MailListener(userId, access.team().getId(), access.account().getId(), access.config().revision, now);
                listener.bindingAttemptId = grant.attemptId();
                if (listener.expiresAt.isAfter(grant.expiresAt())) listener.expiresAt = grant.expiresAt();
                listeners.saveAndFlush(listener);
                return response(listener);
            });
        } catch (DataIntegrityViolationException ignored) {
            throw error("MAIL_LISTENER_BUSY", "A listener is already using this mailbox", HttpStatus.CONFLICT);
        }
        try { workers.execute(() -> runListener(response.id())); }
        catch (RejectedExecutionException ignored) {
            finish(response.id(), MailListener.State.FAILED, "MAIL_QUEUE_FULL");
            throw error("MAIL_QUEUE_FULL", "Mailbox listener capacity is full", HttpStatus.SERVICE_UNAVAILABLE);
        }
        return response;
    }

    public MailListenerResponse poll(UUID userId, UUID id) {
        return poll(userId, id, null);
    }
    public MailListenerResponse poll(UUID userId, UUID id, String deviceId) {
        MailListenerResponse result = inTransaction(() -> {
            var grant = assistance.require(userId, deviceId);
            MailListener listener = listeners.findByIdForUpdate(id).orElseThrow(this::notFound);
            if (!listener.ownerUserId.equals(userId)) throw notFound();
            if (!Objects.equals(listener.bindingAttemptId, grant.attemptId())) {
                listener.stop(MailListener.State.CANCELLED, "MAIL_ACCESS_REVOKED");
                return response(listener);
            }
            validateListener(listener);
            return response(listener);
        });
        if (result.state() != MailListener.State.CONNECTING && result.state() != MailListener.State.LISTENING) closeConnection(id);
        return result;
    }

    public MailListenerCurrentResponse current(UUID userId) {
        return current(userId, null);
    }
    public MailListenerCurrentResponse current(UUID userId, String deviceId) {
        MailListenerCurrentResponse result = inTransaction(() -> {
            var grant = assistance.require(userId, deviceId);
            List<MailListener> candidates = resumableListeners(userId);
            candidates.forEach(listener -> {
                if (!Objects.equals(listener.bindingAttemptId, grant.attemptId()))
                    listener.stop(MailListener.State.CANCELLED, "MAIL_ACCESS_REVOKED");
            });
            candidates = candidates.stream().filter(listener -> Objects.equals(listener.bindingAttemptId, grant.attemptId())).toList();
            // An active listener takes precedence over a previous, still readable result.
            MailListener selected = candidates.stream().filter(MailListener::active).findFirst()
                    .orElse(candidates.isEmpty() ? null : candidates.get(0));
            if (selected == null || !validateListener(selected)) return new MailListenerCurrentResponse(null);
            return new MailListenerCurrentResponse(response(selected));
        });
        closeInvalidConnections();
        return result;
    }

    @EventListener
    public void systemLogout(SystemLogoutEvent event) {
        inTransaction(() -> {
            for (MailListener listener : resumableListeners(event.userId()))
                listener.stop(MailListener.State.CANCELLED, "MAIL_LISTENER_STOPPED");
            return null;
        });
        closeInvalidConnections();
    }

    /** Called only while the binding transaction owns and locks the official account claim. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void endBindingClaim(UUID userId, UUID accountId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive())
            throw new IllegalStateException("MAIL_BINDING_TRANSACTION_REQUIRED");
        List<UUID> stopped = listeners.findByOwnerUserIdAndOfficialAccountIdAndStateIn(userId, accountId,
                List.of(MailListener.State.CONNECTING, MailListener.State.LISTENING, MailListener.State.RECEIVED))
                .stream().filter(listener -> listener.active() || (listener.resultExpiresAt != null
                        && listener.resultExpiresAt.isAfter(clock.instant())))
                .map(listener -> {
                    listener.stop(MailListener.State.CANCELLED, "MAIL_BINDING_ENDED");
                    return listener.id;
                }).toList();
        listeners.flush();
        if (!stopped.isEmpty()) TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { stopped.forEach(MailAssistanceService.this::closeConnection); }
        });
    }

    private List<MailListener> resumableListeners(UUID userId) {
        return listeners.findByOwnerUserIdAndStateInOrderByStartedAtDescIdDesc(userId,
                List.of(MailListener.State.CONNECTING, MailListener.State.LISTENING, MailListener.State.RECEIVED));
    }

    public MailListenerResponse cancel(UUID userId, UUID id) {
        return cancel(userId, id, null);
    }
    public MailListenerResponse cancel(UUID userId, UUID id, String deviceId) {
        MailListenerResponse result = inTransaction(() -> {
            MailListener listener = listeners.findByIdForUpdate(id).orElseThrow(this::notFound);
            if (!listener.ownerUserId.equals(userId)) throw notFound();
            if (!assistance.ownsDevice(userId, listener.bindingAttemptId, deviceId)) throw LoginAssistanceAccess.denied();
            validateListener(listener);
            if (listener.active() || listener.state == MailListener.State.RECEIVED) listener.stop(MailListener.State.CANCELLED, null);
            return response(listener);
        });
        closeConnection(id);
        return result;
    }

    private void runListener(UUID id) {
        try {
            MailboxConfig config = inTransaction(() -> {
                MailListener listener = listeners.findByIdForUpdate(id).orElse(null);
                if (listener == null || !validateListener(listener) || !listener.active()) return null;
                return requireConfig(listener.officialAccountId);
            });
            if (config == null) return;
            MailTransport.Settings prepared = settings(config, id);
            boolean stillActive = inTransaction(() -> {
                MailListener current = listeners.findByIdForUpdate(id).orElse(null);
                return current != null && validateListener(current) && current.active();
            });
            if (!stillActive) return;
            try (MailTransport.Connection connection = transport.open(prepared)) {
                connections.put(id, connection);
                MailTransport.Cursor cursor = connection.baseline();
                boolean armed = inTransaction(() -> {
                    MailListener listener = listeners.findByIdForUpdate(id).orElse(null);
                    if (listener == null || !validateListener(listener) || !listener.active()) return false;
                    if (cursor.uidValidity() <= 0 || cursor.nextUid() <= 0) {
                        listener.stop(MailListener.State.FAILED, "MAIL_CURSOR_UNAVAILABLE"); return false;
                    }
                    listener.uidValidity = cursor.uidValidity(); listener.nextUid = cursor.nextUid();
                    listener.listeningAt = clock.instant(); listener.state = MailListener.State.LISTENING;
                    return true;
                });
                if (!armed) return;
                while (!Thread.currentThread().isInterrupted()) {
                    long next = inTransaction(() -> {
                        MailListener listener = listeners.findByIdForUpdate(id).orElse(null);
                        return listener != null && validateListener(listener) && listener.active() ? listener.nextUid : -1L;
                    });
                    if (next < 0) break;
                    MailTransport.Batch batch = connection.poll(next);
                    boolean keepListening = processBatch(id, next, batch);
                    if (!keepListening) break;
                    Thread.sleep(2000);
                }
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt(); finish(id, MailListener.State.CANCELLED, "MAIL_LISTENER_STOPPED");
        } catch (DomainException exception) {
            finish(id, MailListener.State.FAILED, safeFailureCode(exception.getCode()));
        } catch (RuntimeException ignored) {
            finish(id, MailListener.State.FAILED, "MAIL_CONNECTION_FAILED");
        } finally { connections.remove(id); }
    }

    private boolean processBatch(UUID id, long requestedNext, MailTransport.Batch batch) {
        // Lock the account before the listener so configuration changes cannot race a result publication.
        UUID accountId = inTransaction(() -> listeners.findById(id).map(l -> l.officialAccountId).orElse(null));
        if (accountId == null) return false;
        return inTransaction(() -> {
            requireAccount(accountId, true);
            MailListener listener = listeners.findByIdForUpdate(id).orElse(null);
            if (listener == null || !validateListener(listener) || !listener.active()) return false;
            if (batch == null || batch.uidValidity() != listener.uidValidity || batch.nextUid() < requestedNext
                    || batch.messages() == null || batch.messages().size() > 50) {
                listener.stop(MailListener.State.FAILED, "MAIL_CURSOR_CHANGED"); return false;
            }
            MailboxConfig config = requireConfig(accountId);
            for (MailTransport.Candidate candidate : batch.messages()) {
                if (candidate == null || candidate.uid() < requestedNext || candidate.uid() >= batch.nextUid()) continue;
                // The UID baseline excludes existing mail. Use request time so mail arriving between
                // that snapshot and publishing LISTENING is not lost to the later readiness timestamp.
                var code = matcher.findCode(candidate.message(), config.senderEmail, config.recipientEmail, listener.startedAt);
                if (code.isPresent()) {
                    var grant = assistance.currentGrant(listener.ownerUserId, listener.bindingAttemptId, listener.officialAccountId);
                    if (grant == null) { listener.stop(MailListener.State.CANCELLED, "MAIL_ACCESS_REVOKED"); return false; }
                    listener.codeCipher = cipher.encrypt(code.get(), listener.aad()); listener.state = MailListener.State.RECEIVED;
                    listener.activeAccountId = null; listener.receivedAt = clock.instant();
                    listener.resultExpiresAt = listener.receivedAt.plusSeconds(60);
                    if (listener.resultExpiresAt.isAfter(grant.expiresAt())) listener.resultExpiresAt = grant.expiresAt();
                    listener.expiresAt = listener.resultExpiresAt;
                    return false;
                }
            }
            listener.nextUid = batch.nextUid();
            return true;
        });
    }

    private MailTransport.Settings settings(MailboxConfig config) {
        return settings(config, null);
    }

    private MailTransport.Settings settings(MailboxConfig config, UUID listenerId) {
        if (config.authMode == MailboxConfig.AuthMode.PASSWORD) return new MailTransport.Settings(config.host, config.port,
                config.username, config.folder, config.authMode, cipher.decrypt(config.passwordCipher, config.aad("password")));
        // Refresh rotation must be serial across instances. Only this bounded (10 s) OAuth phase holds the row lock.
        return inTransaction(() -> {
            requireAccount(config.officialAccountId, true);
            MailboxConfig current = requireConfig(config.officialAccountId);
            if (!current.revision.equals(config.revision)) throw error("MAIL_CONFIG_CHANGED", "Mailbox settings changed", HttpStatus.CONFLICT);
            if (listenerId != null) {
                MailListener listener = listeners.findById(listenerId).orElse(null);
                Access access = listener == null ? null : access(listener.ownerUserId, false);
                if (listener == null || !listener.active() || !listener.expiresAt.isAfter(clock.instant()) || access == null
                        || !assistance.current(listener.ownerUserId, listener.bindingAttemptId, listener.officialAccountId)
                        || !access.team().getId().equals(listener.teamId) || !access.account().getId().equals(listener.officialAccountId)
                        || !current.revision.equals(listener.configRevision))
                    throw error("MAIL_LISTENER_STOPPED", "Mailbox listener stopped", HttpStatus.CONFLICT);
            }
            MailOAuthClient.Token token = oauth.refresh(current.authMode, current.oauthClientId,
                    current.clientSecretCipher == null ? null : cipher.decrypt(current.clientSecretCipher, current.aad("client-secret")),
                    cipher.decrypt(current.refreshTokenCipher, current.aad("refresh-token")), current.oauthTenant);
            if (token.refreshToken() != null) current.refreshTokenCipher = cipher.encrypt(token.refreshToken(), current.aad("refresh-token"));
            return new MailTransport.Settings(current.host, current.port, current.username, current.folder, current.authMode, token.accessToken());
        });
    }

    private boolean validateListener(MailListener listener) {
        if (!assistance.current(listener.ownerUserId, listener.bindingAttemptId, listener.officialAccountId)) {
            listener.stop(MailListener.State.CANCELLED, "MAIL_ACCESS_REVOKED"); return false;
        }
        Access current = access(listener.ownerUserId, false);
        if (current == null || !current.team().getId().equals(listener.teamId)
                || !current.account().getId().equals(listener.officialAccountId)
                || current.config() == null || !current.config().revision.equals(listener.configRevision)
                || reason(current.config()) != null) {
            listener.stop(MailListener.State.FAILED, "MAIL_ACCESS_REVOKED"); return false;
        }
        Instant now = clock.instant();
        if (listener.state == MailListener.State.RECEIVED && listener.resultExpiresAt != null && !listener.resultExpiresAt.isAfter(now)) {
            listener.stop(MailListener.State.TIMED_OUT, "CODE_EXPIRED"); return false;
        }
        if (listener.active() && !listener.expiresAt.isAfter(now)) {
            listener.stop(MailListener.State.TIMED_OUT, "MAIL_LISTENER_TIMEOUT"); return false;
        }
        return true;
    }

    private Access access(UUID userId, boolean lock) {
        User user = (lock ? users.findByIdForUpdate(userId) : users.findById(userId)).orElse(null);
        if (user == null || user.getStatus() != User.Status.ACTIVE || user.getTeamId() == null) return null;
        Team team = (lock ? teams.findByIdForUpdate(user.getTeamId()) : teams.findById(user.getTeamId())).orElse(null);
        if (team == null || team.getStatus() != Team.Status.ACTIVE || team.getOfficialAccountId() == null) return null;
        OfficialAccount account = (lock ? accounts.findByIdForUpdate(team.getOfficialAccountId())
                : accounts.findById(team.getOfficialAccountId())).orElse(null);
        return account == null ? null : new Access(user, team, account, mailboxes.findById(account.getId()).orElse(null));
    }

    private String reason(MailboxConfig config) {
        if (!cipher.isReady()) return "ENCRYPTION_UNAVAILABLE";
        if (config == null) return "MAILBOX_UNCONFIGURED";
        if (!config.enabled) return "MAILBOX_DISABLED";
        return config.hasCredentials() ? null : "MAILBOX_CREDENTIALS_MISSING";
    }

    private void validate(MailboxRequest request) {
        targets.validateHost(request.host().trim().toLowerCase(Locale.ROOT));
        if (!providers.supports(request.provider(), request.authMode())
                || request.folder().isBlank() || request.username().isBlank()
                || request.username().chars().anyMatch(Character::isISOControl)
                || request.folder().chars().anyMatch(Character::isISOControl))
            throw error("MAIL_CONFIG_INVALID", "Mailbox settings are invalid", HttpStatus.BAD_REQUEST);
        if (request.authMode() == MailboxConfig.AuthMode.GOOGLE_OAUTH2 && !request.host().trim().equalsIgnoreCase("imap.gmail.com"))
            throw error("MAIL_CONFIG_INVALID", "Google OAuth requires the official Gmail IMAPS server", HttpStatus.BAD_REQUEST);
        if (request.authMode() == MailboxConfig.AuthMode.MICROSOFT_OAUTH2) {
            if (!request.host().trim().equalsIgnoreCase("outlook.office365.com"))
                throw error("MAIL_CONFIG_INVALID", "Microsoft OAuth requires the official Outlook IMAPS server", HttpStatus.BAD_REQUEST);
            oauth.endpoint(request.authMode(), request.oauthTenant());
        }
    }

    private void invalidateAccountListeners(UUID accountId) {
        for (MailListener listener : listeners.findByOfficialAccountId(accountId))
            if (listener.active() || listener.codeCipher != null) listener.stop(MailListener.State.CANCELLED, "MAIL_CONFIG_CHANGED");
    }
    private String secret(String supplied, String existing, String context) {
        return supplied == null || supplied.isBlank() ? existing : cipher.encrypt(supplied, context);
    }
    private String emptyToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private String mailboxKey(String host, int port, String username) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                    (host + "\n" + port + "\n" + username.toLowerCase(Locale.ROOT)).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ignored) { throw error("MAIL_CONFIG_INVALID", "Mailbox settings are invalid", HttpStatus.BAD_REQUEST); }
    }
    private MailboxResponse configResponse(MailboxConfig config) {
        return config == null ? new MailboxResponse(false, false, null, null, null, null, null, null, null,
                null, null, null, false, false, false, cipher.isReady())
                : new MailboxResponse(true, config.enabled, config.provider, config.host, config.port, config.username,
                    config.recipientEmail, config.folder, config.senderEmail, config.authMode, config.oauthClientId,
                    config.oauthTenant, config.passwordCipher != null, config.clientSecretCipher != null,
                    config.refreshTokenCipher != null, cipher.isReady());
    }
    private MailListenerResponse response(MailListener listener) {
        String code = listener.state == MailListener.State.RECEIVED && listener.codeCipher != null
                ? cipher.decrypt(listener.codeCipher, listener.aad()) : null;
        return new MailListenerResponse(listener.id, listener.state, listener.startedAt, listener.expiresAt,
                listener.listeningAt, listener.receivedAt, code, listener.errorCode);
    }
    private OfficialAccount requireAccount(UUID id, boolean lock) {
        return (lock ? accounts.findByIdForUpdate(id) : accounts.findById(id)).orElseThrow(() ->
                error("OFFICIAL_ACCOUNT_NOT_FOUND", "Official account not found", HttpStatus.NOT_FOUND));
    }
    private MailboxConfig requireConfig(UUID id) {
        MailboxConfig config = mailboxes.findById(id).orElseThrow(() -> error("MAILBOX_UNCONFIGURED", "Mailbox is unconfigured", HttpStatus.CONFLICT));
        if (!cipher.isReady() || !config.hasCredentials()) throw error("MAIL_CREDENTIALS_MISSING", "Mailbox authorization is incomplete", HttpStatus.CONFLICT);
        return config;
    }
    private void finish(UUID id, MailListener.State state, String code) {
        inTransaction(() -> { listeners.findByIdForUpdate(id).ifPresent(l -> { if (l.active()) l.stop(state, code); }); return null; });
    }
    private String safeFailureCode(String code) {
        return switch (code == null ? "" : code) {
            case "MAIL_DNS_FAILED", "MAIL_HOST_BLOCKED", "MAIL_AUTH_FAILED", "MAIL_TLS_FAILED",
                    "MAIL_CONNECTION_TIMEOUT", "MAIL_FOLDER_FAILED", "MAIL_CURSOR_CHANGED", "MAIL_CURSOR_UNAVAILABLE",
                    "MAIL_OAUTH_FAILED", "MAIL_CREDENTIALS_UNAVAILABLE", "MAIL_ENCRYPTION_UNAVAILABLE" -> code;
            default -> "MAIL_CONNECTION_FAILED";
        };
    }
    private <T> T inTransaction(Supplier<T> work) { return transaction.execute(status -> work.get()); }
    private DomainException notFound() { return error("MAIL_LISTENER_NOT_FOUND", "Mailbox listener not found", HttpStatus.NOT_FOUND); }
    private DomainException duplicateMailbox() { return error("MAILBOX_ALREADY_ASSIGNED", "Mailbox or recipient is already assigned", HttpStatus.CONFLICT); }
    private DomainException error(String code, String message, HttpStatus status) { return new DomainException(code, message, status); }
    private void closeConnection(UUID id) {
        MailTransport.Connection connection = connections.remove(id);
        if (connection != null) try { connection.close(); } catch (RuntimeException ignored) { }
    }
    private void closeInvalidConnections() {
        for (UUID id : connections.keySet()) {
            boolean active = inTransaction(() -> listeners.findById(id).map(MailListener::active).orElse(false));
            if (!active) closeConnection(id);
        }
    }
    private void safeCleanup() {
        try {
            inTransaction(() -> {
                Instant now = clock.instant();
                for (MailListener listener : listeners.findByStateInAndExpiresAtLessThan(
                        List.of(MailListener.State.CONNECTING, MailListener.State.LISTENING), now))
                    listener.stop(MailListener.State.TIMED_OUT, "MAIL_LISTENER_TIMEOUT");
                for (MailListener listener : listeners.findByResultExpiresAtLessThan(now))
                    listener.stop(MailListener.State.TIMED_OUT, "CODE_EXPIRED");
                listeners.deleteByStartedAtLessThanAndActiveAccountIdIsNull(now.minusSeconds(3600)); return null;
            });
            closeInvalidConnections();
        } catch (RuntimeException ignored) { /* Next cleanup retries without exposing any message or credentials. */ }
    }
    @Override public void destroy() {
        cleaner.shutdownNow(); workers.shutdownNow();
        for (UUID id : connections.keySet()) closeConnection(id);
    }
    private record Access(User user, Team team, OfficialAccount account, MailboxConfig config) { }
    public record TestResult(boolean success, String message) { }
}
