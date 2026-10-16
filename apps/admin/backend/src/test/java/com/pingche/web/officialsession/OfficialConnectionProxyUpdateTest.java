package com.pingche.web.officialsession;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

import com.pingche.web.auth.DomainException;
import com.pingche.web.quota.OfficialAccount;
import com.pingche.web.quota.OfficialAccountRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import tools.jackson.databind.ObjectMapper;

class OfficialConnectionProxyUpdateTest {
    private final UUID account=UUID.randomUUID(),front=UUID.randomUUID(),exit=UUID.randomUUID();
    private final OfficialAccountRepository accounts=mock(OfficialAccountRepository.class);
    private final OfficialAccountConnectionRepository connections=mock(OfficialAccountConnectionRepository.class);
    private final OfficialSessionCipher cipher=mock(OfficialSessionCipher.class);
    private final OfficialSessionClient client=mock(OfficialSessionClient.class);
    private SingleConnectionDataSource source;
    private JdbcTemplate jdbc;
    private OfficialConnectionService service;
    private OfficialAccountConnection saved;

    @BeforeEach void setup() {
        source=new SingleConnectionDataSource("jdbc:h2:mem:chain-update-"+UUID.randomUUID()+";MODE=PostgreSQL","sa","",true);
        jdbc=new JdbcTemplate(source);
        jdbc.execute("create table managed_proxy_nodes(id uuid primary key,enabled boolean)");
        jdbc.execute("create table official_session_logout_plans(official_account_id uuid,status varchar(32),lease_until timestamp with time zone)");
        jdbc.update("insert into managed_proxy_nodes values (?,true),(?,true)",front,exit);
        when(accounts.findByIdForUpdate(account)).thenReturn(Optional.of(new OfficialAccount("Synthetic",1000,null,Instant.EPOCH)));
        saved=new OfficialAccountConnection(account);
        saved.version=7; saved.revision=UUID.randomUUID(); saved.requestedAccountId="selected-account";
        saved.authorizationCipher="original-authorization-cipher";saved.cookieCipher="original-cookie-cipher";
        saved.boundAccountId="bound-account";saved.boundUserId="bound-user";saved.importedAt=Instant.EPOCH;
        saved.snapshotCipher="stale-snapshot";saved.refreshAttempt=UUID.randomUUID();saved.refreshLeaseUntil=Instant.MAX;
        saved.lastSuccessAt=Instant.EPOCH;saved.lastAttemptAt=Instant.EPOCH;saved.lastErrorCode="old-error";saved.status="CONNECTED";
        when(connections.findByIdForUpdate(account)).thenReturn(Optional.of(saved));
        when(connections.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        service=new OfficialConnectionService(accounts,connections,cipher,client,new ObjectMapper(),jdbc,Clock.systemUTC(),new DataSourceTransactionManager(source));
    }
    @AfterEach void close() {source.destroy();}

    @Test void proxyOnlyUpdatePreservesSecretsAndIdentityAndInvalidatesInflightResults() {
        UUID revision=saved.revision;
        var response=service.updateProxy(account,new OfficialConnectionProxyRequest(exit,front,7L));
        assertThat(response.frontProxyNodeId()).isEqualTo(front);
        assertThat(response.proxyNodeId()).isEqualTo(exit);
        assertThat(saved.authorizationCipher).isEqualTo("original-authorization-cipher");
        assertThat(saved.cookieCipher).isEqualTo("original-cookie-cipher");
        assertThat(saved.requestedAccountId).isEqualTo("selected-account");
        assertThat(saved.boundAccountId).isEqualTo("bound-account");
        assertThat(saved.boundUserId).isEqualTo("bound-user");
        assertThat(saved.importedAt).isEqualTo(Instant.EPOCH);
        assertThat(saved.revision).isNotEqualTo(revision);
        assertThat(saved.status).isEqualTo("UNVERIFIED");
        assertThat(saved.snapshotCipher).isNull();assertThat(saved.refreshAttempt).isNull();assertThat(saved.refreshLeaseUntil).isNull();
        assertThat(saved.lastSuccessAt).isNull();assertThat(saved.lastAttemptAt).isNull();assertThat(saved.lastErrorCode).isNull();
        verify(cipher,never()).decrypt(any(),any());verify(cipher,never()).encrypt(any(),any());verifyNoInteractions(client);
    }

    @Test void staleVersionDuplicateDisabledFrontAndLogoutFencePreventUpdates() {
        assertCode(() -> service.updateProxy(account,new OfficialConnectionProxyRequest(exit,front,6L)),"OFFICIAL_CONNECTION_VERSION_CONFLICT");
        assertCode(() -> service.updateProxy(account,new OfficialConnectionProxyRequest(exit,exit,7L)),"PROXY_CHAIN_INVALID");
        jdbc.update("update managed_proxy_nodes set enabled=false where id=?",front);
        assertCode(() -> service.updateProxy(account,new OfficialConnectionProxyRequest(exit,front,7L)),"OFFICIAL_PROXY_UNAVAILABLE");
        jdbc.update("insert into official_session_logout_plans values (?,'UNKNOWN',null)",account);
        assertCode(() -> service.updateProxy(account,new OfficialConnectionProxyRequest(exit,null,7L)),"OFFICIAL_SESSION_LOGOUT_BUSY");
        verify(connections,never()).saveAndFlush(any());verifyNoInteractions(cipher,client);
    }
    private void assertCode(Runnable work,String code) {
        assertThatThrownBy(work::run).isInstanceOfSatisfying(DomainException.class,error -> assertThat(error.getCode()).isEqualTo(code));
    }
}
