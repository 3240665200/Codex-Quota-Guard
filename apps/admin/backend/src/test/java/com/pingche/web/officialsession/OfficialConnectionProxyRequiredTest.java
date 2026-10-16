package com.pingche.web.officialsession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.pingche.web.auth.DomainException;
import com.pingche.web.quota.OfficialAccount;
import com.pingche.web.quota.OfficialAccountRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import tools.jackson.databind.ObjectMapper;

class OfficialConnectionProxyRequiredTest {
    private final UUID accountId = UUID.randomUUID(), nodeId = UUID.randomUUID();
    private final OfficialAccountRepository accounts = mock(OfficialAccountRepository.class);
    private final OfficialAccountConnectionRepository connections = mock(OfficialAccountConnectionRepository.class);
    private final OfficialSessionCipher cipher = mock(OfficialSessionCipher.class);
    private final OfficialSessionClient client = mock(OfficialSessionClient.class);
    private SingleConnectionDataSource source;
    private JdbcTemplate jdbc;
    private OfficialConnectionService service;

    @BeforeEach void setup() {
        source = new SingleConnectionDataSource("jdbc:h2:mem:proxy-required-" + UUID.randomUUID()
                + ";MODE=PostgreSQL", "sa", "", true);
        jdbc = new JdbcTemplate(source);
        jdbc.execute("create table managed_proxy_nodes(id uuid primary key,enabled boolean)");
        jdbc.execute("create table official_session_logout_plans(official_account_id uuid,status varchar(32),lease_until timestamp with time zone)");
        jdbc.update("insert into managed_proxy_nodes values (?,true)", nodeId);
        when(accounts.findByIdForUpdate(accountId)).thenReturn(Optional.of(new OfficialAccount("Synthetic", 1000, null, Instant.EPOCH)));
        when(connections.findByIdForUpdate(accountId)).thenReturn(Optional.empty());
        when(connections.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        when(cipher.isReady()).thenReturn(true);
        when(cipher.encrypt(any(), any())).thenReturn("synthetic-cipher");
        service = new OfficialConnectionService(accounts, connections, cipher, client, new ObjectMapper(), jdbc,
                Clock.fixed(Instant.parse("2026-10-16T00:00:00Z"), ZoneOffset.UTC), new DataSourceTransactionManager(source));
    }

    @AfterEach void close() { source.destroy(); }

    @Test void saveRequiresAnEnabledExplicitNodeBeforePersistingCredentials() {
        assertCode(() -> service.save(accountId, request(null)), "OFFICIAL_PROXY_REQUIRED");
        jdbc.update("update managed_proxy_nodes set enabled=false where id=?", nodeId);
        assertCode(() -> service.save(accountId, request(nodeId)), "OFFICIAL_PROXY_UNAVAILABLE");
        verify(connections, never()).saveAndFlush(any());
        verifyNoInteractions(cipher, client);
    }

    @Test void refreshRechecksStoredNodeBeforeDecryptingOrSendingAnyCredentials() {
        var connection = new OfficialAccountConnection(accountId);
        connection.revision = UUID.randomUUID();
        connection.requestedAccountId = "synthetic-account";
        connection.authorizationCipher = "synthetic-cipher";
        when(connections.findByIdForUpdate(accountId)).thenReturn(Optional.of(connection));
        assertCode(() -> service.refresh(accountId), "OFFICIAL_PROXY_REQUIRED");
        connection.proxyNodeId = nodeId;
        jdbc.update("update managed_proxy_nodes set enabled=false where id=?", nodeId);
        assertCode(() -> service.refresh(accountId), "OFFICIAL_PROXY_UNAVAILABLE");
        verify(connections, never()).saveAndFlush(any());
        verify(cipher, never()).decrypt(any(), any());
        verifyNoInteractions(client);
    }

    @Test void saveRejectsDuplicateAndDisabledFrontAndPersistsTheIndependentFront() {
        UUID front=UUID.randomUUID();
        assertCode(() -> service.save(accountId,new OfficialConnectionRequest("Bearer synthetic-token",null,"synthetic-account",nodeId,0L,nodeId)),"PROXY_CHAIN_INVALID");
        jdbc.update("insert into managed_proxy_nodes values (?,false)",front);
        assertCode(() -> service.save(accountId,new OfficialConnectionRequest("Bearer synthetic-token",null,"synthetic-account",nodeId,0L,front)),"OFFICIAL_PROXY_UNAVAILABLE");
        verify(connections,never()).saveAndFlush(any());
        jdbc.update("update managed_proxy_nodes set enabled=true where id=?",front);
        var response=service.save(accountId,new OfficialConnectionRequest("Bearer synthetic-token",null,"synthetic-account",nodeId,0L,front));
        assertThat(response.proxyNodeId()).isEqualTo(nodeId);
        assertThat(response.frontProxyNodeId()).isEqualTo(front);
        verify(connections).saveAndFlush(argThat(connection -> front.equals(connection.frontProxyNodeId) && nodeId.equals(connection.proxyNodeId)));
    }

    @Test void refreshRechecksStoredFrontBeforeDecryptingCredentials() {
        UUID front=UUID.randomUUID();
        jdbc.update("insert into managed_proxy_nodes values (?,false)",front);
        var connection=new OfficialAccountConnection(accountId);
        connection.proxyNodeId=nodeId; connection.frontProxyNodeId=front;
        when(connections.findByIdForUpdate(accountId)).thenReturn(Optional.of(connection));
        assertCode(() -> service.refresh(accountId),"OFFICIAL_PROXY_UNAVAILABLE");
        verify(cipher,never()).decrypt(any(),any());
        verifyNoInteractions(client);
    }

    private OfficialConnectionRequest request(UUID proxy) {
        return new OfficialConnectionRequest("Bearer synthetic-token", null, "synthetic-account", proxy, 0L);
    }
    private void assertCode(Runnable action, String code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(DomainException.class,
                error -> assertThat(error.getCode()).isEqualTo(code));
    }
}
