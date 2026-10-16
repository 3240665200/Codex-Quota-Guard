package com.pingche.web.officialsession;

import com.pingche.web.auth.DomainException;
import com.pingche.web.quota.OfficialAccountRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@Service
public class OfficialConnectionService {
    private final OfficialAccountRepository accounts;
    private final OfficialAccountConnectionRepository connections;
    private final OfficialSessionCipher cipher;
    private final OfficialSessionClient client;
    private final ObjectMapper mapper;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final TransactionTemplate transaction;
    private final OfficialSessionLogoutFence logoutFence;

    public OfficialConnectionService(OfficialAccountRepository accounts, OfficialAccountConnectionRepository connections,
            OfficialSessionCipher cipher, OfficialSessionClient client, ObjectMapper mapper,
            JdbcTemplate jdbc, Clock clock, PlatformTransactionManager transactionManager) {
        this.accounts = accounts;
        this.connections = connections;
        this.cipher = cipher;
        this.client = client;
        this.mapper = mapper;
        this.jdbc = jdbc;
        this.clock = clock;
        this.transaction = new TransactionTemplate(transactionManager);
        this.logoutFence = new OfficialSessionLogoutFence(jdbc);
    }

    public OfficialConnectionResponse read(UUID accountId) {
        return inTransaction(() -> {
            requireAccount(accountId, false);
            return response(connections.findById(accountId).orElse(null));
        });
    }

    public OfficialConnectionResponse save(UUID accountId, OfficialConnectionRequest request) {
        new OfficialSessionCredentials(request.authorization(), request.cookie(), request.requestedAccountId()).validate();
        return inTransaction(() -> {
            requireAccount(accountId, true);
            OfficialAccountConnection current = connections.findByIdForUpdate(accountId).orElse(null);
            long version = current == null ? 0 : current.version;
            if (request.version() == null || request.version() != version) throw error("OFFICIAL_CONNECTION_VERSION_CONFLICT",
                    "Official connection changed; reload before saving", HttpStatus.CONFLICT);
            if (current != null && current.boundAccountId != null && !current.boundAccountId.equals(request.requestedAccountId()))
                throw error("OFFICIAL_IDENTITY_CHANGE_REQUIRES_DISCONNECT", "Disconnect the saved authorization before selecting another official account", HttpStatus.CONFLICT);
            requireProxy(request.frontProxyNodeId(), request.proxyNodeId());
            OfficialAccountConnection connection = current == null ? new OfficialAccountConnection(accountId) : current;
            connection.authorizationCipher = cipher.encrypt(request.authorization(), connection.aad("authorization"));
            connection.cookieCipher = request.cookie() == null || request.cookie().isBlank() ? null
                    : cipher.encrypt(request.cookie(), connection.aad("cookie"));
            connection.requestedAccountId = request.requestedAccountId();
            connection.proxyNodeId = request.proxyNodeId();
            connection.frontProxyNodeId = request.frontProxyNodeId();
            connection.status = "UNVERIFIED";
            connection.revision = UUID.randomUUID();
            connection.importedAt = clock.instant();
            connection.snapshotCipher = null;
            connection.lastAttemptAt = null;
            connection.lastSuccessAt = null;
            connection.lastErrorCode = null;
            connection.refreshAttempt = null;
            connection.refreshLeaseUntil = null;
            return response(connections.saveAndFlush(connection));
        });
    }

    public void delete(UUID accountId) {
        inTransaction(() -> {
            requireAccount(accountId, true);
            connections.findByIdForUpdate(accountId).ifPresent(connections::delete);
            connections.flush();
            return null;
        });
    }

    /** Publish a browser candidate only after its identity and sessions have been verified. */
    OfficialConnectionResponse saveVerified(UUID accountId, OfficialConnectionRequest request, OfficialSessionSnapshot snapshot) {
        new OfficialSessionCredentials(request.authorization(), request.cookie(), request.requestedAccountId()).validate();
        if (snapshot == null || snapshot.account() == null || snapshot.sessions() == null
                || !request.requestedAccountId().equals(snapshot.account().accountId())
                || snapshot.account().userId() == null || snapshot.account().userId().isBlank())
            throw error("OFFICIAL_ACCOUNT_MISMATCH", "Official account identity could not be verified", HttpStatus.BAD_GATEWAY);
        return inTransaction(() -> {
            requireAccount(accountId, true);
            OfficialAccountConnection current = connections.findByIdForUpdate(accountId).orElse(null);
            if (current != null && ((current.boundAccountId != null && !current.boundAccountId.equals(snapshot.account().accountId()))
                    || (current.boundUserId != null && !current.boundUserId.equals(snapshot.account().userId()))))
                throw error("OFFICIAL_ACCOUNT_MISMATCH", "Official account identity could not be verified", HttpStatus.BAD_GATEWAY);
            // The normal save retains optimistic version checks and rotates the binding revision.
            // Both writes join this transaction, so no unverified candidate is visible on failure.
            save(accountId, request);
            OfficialAccountConnection saved = connections.findByIdForUpdate(accountId).orElseThrow(this::changed);
            saved.snapshotCipher = cipher.encrypt(mapper.writeValueAsString(snapshot), saved.aad("snapshot"));
            saved.boundAccountId = snapshot.account().accountId();
            saved.boundUserId = snapshot.account().userId();
            saved.status = "CONNECTED";
            saved.lastAttemptAt = clock.instant();
            saved.lastSuccessAt = clock.instant();
            saved.lastErrorCode = null;
            return response(connections.saveAndFlush(saved));
        });
    }

    public OfficialConnectionRefreshResponse sessions(UUID accountId) {
        return inTransaction(() -> {
            requireAccount(accountId, false);
            OfficialAccountConnection connection = connections.findById(accountId).orElseThrow(this::noSnapshot);
            if (!"CONNECTED".equals(connection.status) || connection.snapshotCipher == null || connection.lastSuccessAt == null) throw noSnapshot();
            OfficialSessionSnapshot snapshot;
            try {
                snapshot = mapper.readValue(cipher.decrypt(connection.snapshotCipher, connection.aad("snapshot")), OfficialSessionSnapshot.class);
            } catch (RuntimeException ignored) {
                throw error("OFFICIAL_CREDENTIALS_UNAVAILABLE", "Official authorization is unavailable", HttpStatus.SERVICE_UNAVAILABLE);
            }
            return new OfficialConnectionRefreshResponse(response(connection), snapshot.account(), snapshot.sessions(), connection.lastSuccessAt);
        });
    }

    public OfficialConnectionRefreshResponse refresh(UUID accountId) {
        RefreshWork work = reserve(accountId);
        try {
            OfficialSessionCredentials credentials = new OfficialSessionCredentials(
                    cipher.decrypt(work.authorizationCipher(), work.aad("authorization")),
                    work.cookieCipher() == null ? null : cipher.decrypt(work.cookieCipher(), work.aad("cookie")), work.requestedAccountId());
            OfficialSessionSnapshot snapshot = work.frontProxyNodeId() == null ? client.fetch(credentials, work.proxyNodeId())
                    : client.fetch(credentials, work.frontProxyNodeId(), work.proxyNodeId());
            if (snapshot == null || snapshot.account() == null || snapshot.sessions() == null
                    || !work.requestedAccountId().equals(snapshot.account().accountId())
                    || snapshot.account().userId() == null || snapshot.account().userId().isBlank())
                throw error("OFFICIAL_ACCOUNT_MISMATCH", "Official account identity could not be verified", HttpStatus.BAD_GATEWAY);
            return complete(work, snapshot);
        } catch (DomainException failure) {
            if ("OFFICIAL_CONNECTION_CHANGED".equals(failure.getCode())) throw failure;
            DomainException safe = safeFailure(failure.getCode());
            fail(work, safe.getCode());
            throw safe;
        } catch (RuntimeException ignored) {
            DomainException safe = safeFailure("OFFICIAL_UNAVAILABLE");
            fail(work, safe.getCode());
            throw safe;
        }
    }

    private RefreshWork reserve(UUID accountId) {
        return inTransaction(() -> {
            requireAccount(accountId, true);
            OfficialAccountConnection connection = connections.findByIdForUpdate(accountId)
                    .orElseThrow(() -> error("OFFICIAL_CONNECTION_NOT_CONFIGURED", "Import official authorization first", HttpStatus.CONFLICT));
            requireProxy(connection.frontProxyNodeId, connection.proxyNodeId);
            if (!cipher.isReady()) throw error("OFFICIAL_ENCRYPTION_UNAVAILABLE", "Official authorization encryption is unavailable", HttpStatus.SERVICE_UNAVAILABLE);
            Instant now = clock.instant();
            if (connection.refreshLeaseUntil != null && connection.refreshLeaseUntil.isAfter(now))
                throw error("OFFICIAL_REFRESH_BUSY", "An official connection refresh is already running", HttpStatus.CONFLICT);
            if (connection.lastAttemptAt != null && connection.lastAttemptAt.plusSeconds(10).isAfter(now))
                throw error("OFFICIAL_REFRESH_RATE_LIMITED", "Wait before refreshing official sessions again", HttpStatus.TOO_MANY_REQUESTS);
            connection.refreshAttempt = UUID.randomUUID();
            connection.refreshLeaseUntil = now.plusSeconds(90);
            connection.lastAttemptAt = now;
            connections.saveAndFlush(connection);
            return new RefreshWork(accountId, connection.revision, connection.refreshAttempt, connection.requestedAccountId,
                    connection.proxyNodeId, connection.authorizationCipher, connection.cookieCipher, connection.frontProxyNodeId);
        });
    }

    private OfficialConnectionRefreshResponse complete(RefreshWork work, OfficialSessionSnapshot snapshot) {
        return inTransaction(() -> {
            OfficialAccountConnection connection = connections.findByIdForUpdate(work.accountId()).orElseThrow(this::changed);
            if (!current(connection, work)) throw changed();
            if ((connection.boundAccountId != null && !connection.boundAccountId.equals(snapshot.account().accountId()))
                    || (connection.boundUserId != null && !connection.boundUserId.equals(snapshot.account().userId())))
                throw error("OFFICIAL_ACCOUNT_MISMATCH", "Official account identity could not be verified", HttpStatus.BAD_GATEWAY);
            connection.snapshotCipher = cipher.encrypt(mapper.writeValueAsString(snapshot), connection.aad("snapshot"));
            connection.boundAccountId = snapshot.account().accountId();
            connection.boundUserId = snapshot.account().userId();
            connection.status = "CONNECTED";
            connection.lastSuccessAt = clock.instant();
            connection.lastErrorCode = null;
            connection.refreshAttempt = null;
            connection.refreshLeaseUntil = null;
            connections.saveAndFlush(connection);
            return new OfficialConnectionRefreshResponse(response(connection), snapshot.account(), snapshot.sessions(), connection.lastSuccessAt);
        });
    }

    private void fail(RefreshWork work, String code) {
        inTransaction(() -> {
            OfficialAccountConnection connection = connections.findByIdForUpdate(work.accountId()).orElse(null);
            if (connection == null || !current(connection, work)) return null;
            connection.status = switch (code) {
                case "OFFICIAL_AUTH_REQUIRED" -> "AUTH_REQUIRED";
                case "OFFICIAL_ACCESS_BLOCKED", "OFFICIAL_CHALLENGE_REQUIRED" -> "ACCESS_BLOCKED";
                case "OFFICIAL_RATE_LIMITED" -> "RATE_LIMITED";
                case "OFFICIAL_RESPONSE_INVALID", "OFFICIAL_ACCOUNT_MISMATCH" -> "INVALID_RESPONSE";
                default -> "UNAVAILABLE";
            };
            connection.lastErrorCode = code;
            connection.snapshotCipher = null;
            connection.refreshAttempt = null;
            connection.refreshLeaseUntil = null;
            connections.saveAndFlush(connection);
            return null;
        });
    }

    private boolean current(OfficialAccountConnection connection, RefreshWork work) {
        return connection.revision.equals(work.revision()) && Objects.equals(connection.refreshAttempt, work.attempt());
    }

    private OfficialConnectionResponse response(OfficialAccountConnection connection) {
        if (connection == null) return new OfficialConnectionResponse(false, cipher.isReady(), "NOT_CONFIGURED", null, null,
                false, false, null, null, null, null, 0);
        return new OfficialConnectionResponse(true, cipher.isReady(), connection.status, connection.requestedAccountId,
                connection.proxyNodeId, connection.authorizationCipher != null, connection.cookieCipher != null, connection.importedAt,
                connection.lastAttemptAt, connection.lastSuccessAt, connection.lastErrorCode, connection.version, connection.frontProxyNodeId);
    }

    private void requireAccount(UUID accountId, boolean lock) {
        if ((lock ? accounts.findByIdForUpdate(accountId) : accounts.findById(accountId)).isEmpty())
            throw error("OFFICIAL_ACCOUNT_NOT_FOUND", "Official account not found", HttpStatus.NOT_FOUND);
        if (lock) logoutFence.assertAvailable(accountId);
    }

    private DomainException safeFailure(String code) {
        return switch (code) {
            case "OFFICIAL_PROXY_REQUIRED" -> error(code, "请先为官方账户选择已启用的代理节点；禁止直连。", HttpStatus.BAD_REQUEST);
            case "OFFICIAL_PROXY_UNAVAILABLE" -> error(code, "绑定的代理节点不可用，请启用或更换节点；未回退直连。", HttpStatus.SERVICE_UNAVAILABLE);
            case "PROXY_CHAIN_INVALID" -> error(code, "前置节点与出口节点必须不同。", HttpStatus.BAD_REQUEST);
            case "PROXY_NODE_DISABLED", "PROXY_NODE_UNAVAILABLE", "PROXY_ENDPOINT_BLOCKED", "PROXY_CAPACITY_EXCEEDED",
                    "PROXY_KERNEL_UNAVAILABLE", "PROXY_CREDENTIALS_UNAVAILABLE", "PROXY_ENCRYPTION_UNAVAILABLE" ->
                    error(code, "绑定的代理节点不可用，请检查节点及服务器代理配置；未回退直连。", HttpStatus.SERVICE_UNAVAILABLE);
            case "PROXY_CONNECT_TIMEOUT" -> error(code, "代理连接超时，请检查绑定节点；未回退直连。", HttpStatus.GATEWAY_TIMEOUT);
            case "PROXY_CONNECTION_FAILED" -> error(code, "代理连接失败，请检查绑定节点；未回退直连。", HttpStatus.BAD_GATEWAY);
            case "OFFICIAL_AUTH_REQUIRED" -> error(code, "Official authorization must be renewed", HttpStatus.BAD_GATEWAY);
            case "OFFICIAL_CHALLENGE_REQUIRED" -> error(code, "The official website requires browser verification; this HTTP connection cannot read account sessions", HttpStatus.BAD_GATEWAY);
            case "OFFICIAL_ACCESS_BLOCKED" -> error(code, "Official access is blocked; complete any required verification on the official website", HttpStatus.BAD_GATEWAY);
            case "OFFICIAL_RATE_LIMITED" -> error(code, "Official service rate limit reached", HttpStatus.BAD_GATEWAY);
            case "OFFICIAL_ACCOUNT_MISMATCH" -> error(code, "Official account identity could not be verified", HttpStatus.BAD_GATEWAY);
            case "OFFICIAL_RESPONSE_INVALID" -> error(code, "Official response could not be verified", HttpStatus.BAD_GATEWAY);
            case "OFFICIAL_CREDENTIALS_UNAVAILABLE" -> error(code, "Official authorization is unavailable", HttpStatus.SERVICE_UNAVAILABLE);
            default -> error("OFFICIAL_UNAVAILABLE", "Official service is unavailable", HttpStatus.BAD_GATEWAY);
        };
    }

    public OfficialConnectionResponse updateProxy(UUID accountId, OfficialConnectionProxyRequest request) {
        return inTransaction(() -> {
            requireAccount(accountId, true);
            OfficialAccountConnection connection = connections.findByIdForUpdate(accountId)
                    .orElseThrow(() -> error("OFFICIAL_CONNECTION_NOT_CONFIGURED", "Import official authorization first", HttpStatus.CONFLICT));
            if (request.version() == null || request.version() != connection.version)
                throw error("OFFICIAL_CONNECTION_VERSION_CONFLICT", "Official connection changed; reload before saving", HttpStatus.CONFLICT);
            requireProxy(request.frontProxyNodeId(), request.proxyNodeId());
            connection.proxyNodeId = request.proxyNodeId();
            connection.frontProxyNodeId = request.frontProxyNodeId();
            connection.revision = UUID.randomUUID();
            connection.status = "UNVERIFIED";
            connection.snapshotCipher = null;
            connection.lastAttemptAt = null;
            connection.lastSuccessAt = null;
            connection.lastErrorCode = null;
            connection.refreshAttempt = null;
            connection.refreshLeaseUntil = null;
            return response(connections.saveAndFlush(connection));
        });
    }

    private void requireProxy(UUID frontNodeId, UUID nodeId) {
        if (nodeId == null) throw error("OFFICIAL_PROXY_REQUIRED", "请先为官方账户选择已启用的代理节点；禁止直连。", HttpStatus.BAD_REQUEST);
        if (nodeId.equals(frontNodeId)) throw error("PROXY_CHAIN_INVALID", "前置节点与出口节点必须不同。", HttpStatus.BAD_REQUEST);
        requireEnabledProxy(nodeId);
        if (frontNodeId != null) requireEnabledProxy(frontNodeId);
    }

    private void requireEnabledProxy(UUID nodeId) {
        if (!Boolean.TRUE.equals(jdbc.queryForObject(
                "select count(*) > 0 from managed_proxy_nodes where id = ? and enabled = true", Boolean.class, nodeId)))
            throw error("OFFICIAL_PROXY_UNAVAILABLE", "绑定的代理节点不存在或已停用，请启用或更换节点；禁止直连。", HttpStatus.BAD_REQUEST);
    }

    private DomainException noSnapshot() { return error("OFFICIAL_SNAPSHOT_UNAVAILABLE", "Refresh the official connection to obtain a verified session snapshot", HttpStatus.CONFLICT); }
    private DomainException changed() { return error("OFFICIAL_CONNECTION_CHANGED", "Official authorization changed during refresh", HttpStatus.CONFLICT); }
    private DomainException error(String code, String message, HttpStatus status) { return new DomainException(code, message, status); }
    private <T> T inTransaction(Supplier<T> work) { return transaction.execute(ignored -> work.get()); }
    private record RefreshWork(UUID accountId, UUID revision, UUID attempt, String requestedAccountId, UUID proxyNodeId,
            String authorizationCipher, String cookieCipher, UUID frontProxyNodeId) {
        String aad(String field) { return "official-session:v1:" + accountId + ":" + field; }
        @Override public String toString() { return "OfficialRefreshWork[redacted]"; }
    }
}
