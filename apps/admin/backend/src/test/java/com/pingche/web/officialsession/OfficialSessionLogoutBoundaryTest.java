package com.pingche.web.officialsession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.pingche.web.auth.DomainException;
import com.pingche.web.auth.RefreshSessionRepository;
import com.pingche.web.quota.*;
import com.pingche.web.sessionbinding.SessionBindingService;
import com.pingche.web.team.TeamRepository;
import com.pingche.web.user.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import tools.jackson.databind.ObjectMapper;

/** Synthetic local database only: no official service or saved credentials are used. */
class OfficialSessionLogoutBoundaryTest {
    private final UUID accountId = UUID.randomUUID(), ownerId = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-10-15T00:00:00Z");
    private JdbcTemplate jdbc;
    private OfficialAccountRepository accounts;
    private OfficialAccountConnectionRepository connections;
    private OfficialSessionClient client;
    private OfficialConnectionService official;
    private BrowserLoginService browser;
    private SessionBindingService bindings;
    private OfficialAccountService accountService;
    private SingleConnectionDataSource source;

    @BeforeEach void setup() {
        source = new SingleConnectionDataSource("jdbc:h2:mem:logout-boundary-" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "", true);
        jdbc = new JdbcTemplate(source);
        jdbc.execute("create table users(id uuid primary key)");
        jdbc.execute("create table official_accounts(id uuid primary key,label varchar(120))");
        jdbc.execute("create table managed_proxy_nodes(id uuid primary key)");
        jdbc.execute("create table official_account_connections(official_account_id uuid primary key,revision uuid)");
        jdbc.execute("create table official_session_logout_plans(official_account_id uuid,status varchar(32),lease_until timestamp with time zone)");
        jdbc.update("insert into users values (?)", ownerId);
        jdbc.update("insert into official_accounts values (?,?)", accountId, "Synthetic account");
        jdbc.update("insert into official_account_connections values (?,?)", accountId, UUID.randomUUID());
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V24__add_session_binding_queue.sql"),
                new ClassPathResource("db/migration/V25__add_official_browser_login_jobs.sql")).execute(source);
        var manager = new DataSourceTransactionManager(source);
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        ObjectMapper mapper = new ObjectMapper();
        accounts = mock(OfficialAccountRepository.class);
        var account = new OfficialAccount("Synthetic account", 1000, null, now);
        when(accounts.findById(accountId)).thenReturn(Optional.of(account));
        when(accounts.findByIdForUpdate(accountId)).thenReturn(Optional.of(account));
        connections = mock(OfficialAccountConnectionRepository.class);
        var connection = new OfficialAccountConnection(accountId);
        connection.revision = UUID.randomUUID();
        connection.requestedAccountId = "synthetic-account";
        connection.authorizationCipher = "synthetic-ciphertext";
        connection.status = "UNVERIFIED";
        when(connections.findByIdForUpdate(accountId)).thenReturn(Optional.of(connection));
        when(connections.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        OfficialSessionCipher cipher = mock(OfficialSessionCipher.class);
        when(cipher.isReady()).thenReturn(true);
        when(cipher.encrypt(any(), any())).thenReturn("synthetic-ciphertext");
        when(cipher.decrypt(any(), any())).thenReturn("Bearer synthetic-token");
        client = mock(OfficialSessionClient.class);
        official = new OfficialConnectionService(accounts, connections, cipher, client, mapper, jdbc, clock, manager);
        OfficialConnectionService browserConnections = mock(OfficialConnectionService.class);
        when(browserConnections.read(accountId)).thenReturn(new OfficialConnectionResponse(false, true, "NOT_CONFIGURED",
                null, null, false, false, null, null, null, null, 0));
        browser = new BrowserLoginService(jdbc, browserConnections, clock, manager);
        bindings = new SessionBindingService(jdbc, browserConnections, clock, mapper, manager);
        accountService = new OfficialAccountService(accounts, mock(OfficialQuotaSnapshotService.class),
                mock(TeamRepository.class), mock(UserRepository.class), mock(RefreshSessionRepository.class), clock,
                new OfficialSessionLogoutFence(jdbc));
    }

    @AfterEach void close() { source.destroy(); }

    @ParameterizedTest @ValueSource(strings = {"EXECUTING", "UNKNOWN"})
    void authorizationReplacementIsBlocked(String state) {
        block(state);
        assertBusy(() -> official.save(accountId,
                new OfficialConnectionRequest("Bearer synthetic-token", null, "synthetic-account", null, 0L)));
        verify(accounts).findByIdForUpdate(accountId);
        verify(connections, never()).saveAndFlush(any());
    }

    @ParameterizedTest @ValueSource(strings = {"EXECUTING", "UNKNOWN"})
    void authorizationDeletionIsBlocked(String state) {
        block(state);
        assertBusy(() -> official.delete(accountId));
        verify(accounts).findByIdForUpdate(accountId);
        verify(connections, never()).delete(any());
    }

    @ParameterizedTest @ValueSource(strings = {"EXECUTING", "UNKNOWN"})
    void refreshCannotStartEvenAfterLogoutLeaseExpired(String state) {
        block(state);
        assertBusy(() -> official.refresh(accountId));
        verifyNoInteractions(client);
    }

    @ParameterizedTest @ValueSource(strings = {"EXECUTING", "UNKNOWN"})
    void browserLoginCannotStart(String state) {
        block(state);
        assertBusy(() -> browser.create(ownerId, accountId, new BrowserLoginService.StartRequest(0L, null)));
        assertThat(jdbc.queryForObject("select count(*) from official_browser_login_jobs", Integer.class)).isZero();
    }

    @ParameterizedTest @ValueSource(strings = {"EXECUTING", "UNKNOWN"})
    void bindingRecoveryCannotClearLogoutFence(String state) {
        block(state);
        assertBusy(() -> bindings.recover(accountId, true));
        assertThat(jdbc.queryForObject("select count(*) from session_binding_accounts", Integer.class)).isZero();
    }

    @ParameterizedTest @ValueSource(strings = {"EXECUTING", "UNKNOWN"})
    void accountDeletionCannotCascadeAwayLogoutEvidence(String state) {
        block(state);
        assertBusy(() -> accountService.delete(accountId));
        verify(accounts).findByIdForUpdate(accountId);
        verify(accounts, never()).delete(any());
    }

    @ParameterizedTest @ValueSource(strings = {"ACCEPTED", "RESOLVED_UNKNOWN", "PREVIEW"})
    void inactivePlansAllowBrowserLogin(String state) {
        block(state);
        assertThat(browser.create(ownerId, accountId, new BrowserLoginService.StartRequest(0L, null)).state())
                .isEqualTo("WAITING_FOR_HELPER");
    }

    private void block(String state) {
        // Expiry is deliberately in the past: an unresolved external result cannot release the fence.
        jdbc.update("insert into official_session_logout_plans values (?,?,?)", accountId, state,
                now.minusSeconds(1).atOffset(ZoneOffset.UTC));
    }

    private void assertBusy(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(DomainException.class,
                failure -> assertThat(failure.getCode()).isEqualTo("OFFICIAL_SESSION_LOGOUT_BUSY"));
    }
}
