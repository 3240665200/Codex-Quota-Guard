package com.pingche.web.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.pingche.web.auth.JwtService;
import com.pingche.web.authenticator.AuthenticatorService;
import com.pingche.web.quota.OfficialAccount;
import com.pingche.web.quota.OfficialAccountRepository;
import com.pingche.web.team.Team;
import com.pingche.web.team.TeamRepository;
import com.pingche.web.user.User;
import com.pingche.web.user.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties={
    "spring.datasource.url=jdbc:h2:mem:assistance-access;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "OFFICIAL_SESSION_ENCRYPTION_KEY=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
    "MAIL_ASSISTANCE_ENCRYPTION_KEY=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="})
@AutoConfigureMockMvc @ActiveProfiles("test")
class LoginAssistanceAccessIntegrationTest {
    static final Instant NOW=Instant.parse("2026-10-27T04:00:01Z");
    static final String SECRET="GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired JwtService jwt;
    @Autowired UserRepository users;
    @Autowired TeamRepository teams;
    @Autowired OfficialAccountRepository accounts;
    @Autowired AuthenticatorService totp;
    @Autowired MailAssistanceService mail;
    @Autowired MailListenerRepository listeners;
    @Autowired MailboxConfigRepository mailboxes;
    @Autowired MailSecretCipher cipher;
    @Autowired PlatformTransactionManager manager;
    @Autowired LoginAssistanceState eventState;
    @MockitoBean Clock clock;
    final AtomicReference<Instant> now=new AtomicReference<>(NOW);
    User owner, other;
    OfficialAccount account;
    Team team;
    UUID device, attempt;

    @BeforeEach void setup() {
        now.set(NOW); when(clock.instant()).thenAnswer(call -> now.get());
        listeners.deleteAll(); jdbc.update("delete from session_bindings");
        jdbc.update("delete from session_binding_attempts"); jdbc.update("delete from session_binding_accounts");
        users.deleteAll();teams.deleteAll();accounts.deleteAll();
        account=accounts.saveAndFlush(new OfficialAccount("Assistance account",0,null,NOW));
        team=new Team("Assistance team","",Team.Status.ACTIVE,100,100,NOW);
        team.updateTokenLimits(null,null,null,account.getId(),NOW);team=teams.saveAndFlush(team);
        owner=member("owner@example.com");other=member("other@example.com");
        device=AssistanceGrantFixture.grant(jdbc,owner.getId(),NOW);
        attempt=jdbc.queryForObject("select id from session_binding_attempts where device_id=?",UUID.class,device);
        totp.save(account.getId(),new AuthenticatorService.Request(true,SECRET,0L));
        mail.saveConfig(account.getId(),new MailboxRequest(true,"QQ","imap.qq.com",993,"owner@example.com","owner@example.com",
                "INBOX","sender@example.com",MailboxConfig.AuthMode.PASSWORD,null,null,"synthetic-password",null,null));
    }

    @Test void contextContainsOnlySwitchesAndTheVerifiedGrant() throws Exception {
        mvc.perform(get(base()+"/context").header("Authorization",token(owner)).header(LoginAssistanceAccess.DEVICE_HEADER,device))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(jsonPath("$.allowed").value(true)).andExpect(jsonPath("$.bindingAttemptId").value(attempt.toString()))
                .andExpect(jsonPath("$.mailEnabled").value(true)).andExpect(jsonPath("$.authenticatorEnabled").value(true))
                .andExpect(jsonPath("$.code").doesNotExist()).andExpect(jsonPath("$.secret").doesNotExist())
                .andExpect(jsonPath("$.recipientEmail").doesNotExist());
        mvc.perform(get(base()+"/context").header("Authorization",token(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.allowed").value(false)).andExpect(jsonPath("$.bindingAttemptId").isEmpty());
    }

    @Test void onlyTheClaimOwnerAndItsEnrolledDeviceCanFetchCodes() throws Exception {
        mvc.perform(post(base()+"/authenticator/code").header("Authorization",token(owner)).header(LoginAssistanceAccess.DEVICE_HEADER,device))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").isString());
        denied(owner,UUID.randomUUID());denied(other,device);
        mvc.perform(post(base()+"/authenticator/code").header("Authorization",token(owner)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("LOGIN_ASSISTANCE_NOT_ALLOWED"));
        mvc.perform(post(base()+"/authenticator/code")).andExpect(status().isUnauthorized());
    }

    @ParameterizedTest @ValueSource(strings={"QUEUED","PREPARING","CANCELLED","EXPIRED","REJECTED","BOUND"})
    void aTicketAloneOrATerminatedAttemptCannotAuthorizeAssistance(String state) throws Exception {
        jdbc.update("update session_binding_attempts set status=? where id=?",state,attempt);
        denied(owner,device);
        mvc.perform(get(base()).header("Authorization",token(owner)).header(LoginAssistanceAccess.DEVICE_HEADER,device)).andExpect(status().isForbidden());
    }

    @ParameterizedTest @ValueSource(strings={"generation","quarantine","recovery","expiry","device","team","revision","baseline","head"})
    void changedOrUnverifiedQueueContextFailsClosed(String change) throws Exception {
        switch(change) {
            case "generation" -> jdbc.update("update session_binding_accounts set generation=generation+1 where official_account_id=?",account.getId());
            case "quarantine" -> jdbc.update("update session_binding_accounts set quarantined=true where official_account_id=?",account.getId());
            case "recovery" -> jdbc.update("update session_binding_accounts set recovery_id=? where official_account_id=?",UUID.randomUUID(),account.getId());
            case "expiry" -> jdbc.update("update session_binding_attempts set expires_at=? where id=?",NOW.minusSeconds(1),attempt);
            case "device" -> jdbc.update("update meter_devices set status='REVOKED' where id=?",device);
            case "team" -> jdbc.update("update teams set version=version+1 where id=?",team.getId());
            case "revision" -> jdbc.update("update official_account_connections set revision=? where official_account_id=?",UUID.randomUUID(),account.getId());
            case "baseline" -> jdbc.update("update session_binding_attempts set before_session_ids=null where id=?",attempt);
            case "head" -> jdbc.update("update session_binding_accounts set active_attempt_id=? where official_account_id=?",UUID.randomUUID(),account.getId());
        }
        denied(owner,device);
    }

    @Test void successfulBindingAtomicallyWipesMailAndDisablesBothChannelsUntilANewGrant() throws Exception {
        MailListener listener=received();
        assertThat(mail.poll(owner.getId(),listener.id,device.toString()).code()).isEqualTo("123456");
        UUID binding=UUID.randomUUID();
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            jdbc.queryForObject("select id from official_accounts where id=? for update",UUID.class,account.getId());
            jdbc.update("update session_binding_attempts set status='BOUND' where id=?",attempt);
            jdbc.update("update session_binding_accounts set active_attempt_id=null where official_account_id=?",account.getId());
            jdbc.update("insert into session_bindings(id,attempt_id,user_id,device_id,enrollment_id,team_id,team_version,official_account_id,connection_revision,session_id,active_device_id,active_session_id,status,match_method,verification_level,created_at) "
                    + "select ?,id,user_id,device_id,enrollment_id,team_id,team_version,official_account_id,connection_revision,'fixture-session',device_id,'fixture-session','ACTIVE','SERIAL_LOGIN_DIFF','USER_CONFIRMED_INFERENCE',? from session_binding_attempts where id=?",binding,NOW,attempt);
            mail.endBindingClaim(owner.getId(),account.getId());
        });
        assertThat(listeners.findById(listener.id).orElseThrow().codeCipher).isNull();
        denied(owner,device);
        mvc.perform(get(base()+"/listeners/"+listener.id).header("Authorization",token(owner)).header(LoginAssistanceAccess.DEVICE_HEADER,device)).andExpect(status().isForbidden());
        jdbc.update("update session_bindings set status='ENDED',active_device_id=null,active_session_id=null,ended_at=? where id=?",NOW,binding);
        denied(owner,device);
        device=AssistanceGrantFixture.grant(jdbc,owner.getId(),NOW);
        mvc.perform(post(base()+"/authenticator/code").header("Authorization",token(owner)).header(LoginAssistanceAccess.DEVICE_HEADER,device)).andExpect(status().isOk());
    }

    @Test void aNewGrantCannotResumeMailReceivedDuringAPreviousGrant() {
        MailListener old=received();device=AssistanceGrantFixture.grant(jdbc,owner.getId(),NOW);
        assertThat(mail.current(owner.getId(),device.toString()).listener()).isNull();
        assertThat(listeners.findById(old.id).orElseThrow().codeCipher).isNull();
    }

    @Test void settingChangesInvalidateTheStreamFingerprintAndTheServerStillEnforcesTheSwitch() throws Exception {
        String previous=eventState.fingerprint(owner.getId());
        totp.save(account.getId(),new AuthenticatorService.Request(false,null,1L));
        assertThat(eventState.fingerprint(owner.getId())).isNotEqualTo(previous);
        mvc.perform(get(base()+"/context").header("Authorization",token(owner)).header(LoginAssistanceAccess.DEVICE_HEADER,device))
                .andExpect(jsonPath("$.authenticatorEnabled").value(false)).andExpect(jsonPath("$.mailEnabled").value(true));
        mvc.perform(post(base()+"/authenticator/code").header("Authorization",token(owner)).header(LoginAssistanceAccess.DEVICE_HEADER,device)).andExpect(status().isConflict());
    }

    @Test void aRequestWaitingBehindABindingCommitCannotIssueAnotherCode() throws Exception {
        CountDownLatch changed=new CountDownLatch(1),commit=new CountDownLatch(1);
        var binding=CompletableFuture.runAsync(() -> new TransactionTemplate(manager).executeWithoutResult(status -> {
            jdbc.queryForObject("select id from official_accounts where id=? for update",UUID.class,account.getId());
            jdbc.update("update session_binding_attempts set status='BOUND' where id=?",attempt);changed.countDown();
            try { if(!commit.await(5,TimeUnit.SECONDS))throw new IllegalStateException("Timed out"); }
            catch(InterruptedException failure) { Thread.currentThread().interrupt();throw new IllegalStateException(failure); }
        }));
        try {
            assertThat(changed.await(5,TimeUnit.SECONDS)).isTrue();
            var read=CompletableFuture.supplyAsync(() -> {
                try { return mvc.perform(post(base()+"/authenticator/code").header("Authorization",token(owner)).header(LoginAssistanceAccess.DEVICE_HEADER,device)).andReturn().getResponse().getStatus(); }
                catch(Exception failure) { throw new IllegalStateException(failure); }
            });
            commit.countDown();binding.get(5,TimeUnit.SECONDS);assertThat(read.get(5,TimeUnit.SECONDS)).isEqualTo(403);
        } finally { commit.countDown(); }
    }

    private void denied(User user,UUID candidateDevice) throws Exception {
        mvc.perform(post(base()+"/authenticator/code").header("Authorization",token(user)).header(LoginAssistanceAccess.DEVICE_HEADER,candidateDevice))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("LOGIN_ASSISTANCE_NOT_ALLOWED"));
        mvc.perform(post(base()+"/listeners").header("Authorization",token(user)).header(LoginAssistanceAccess.DEVICE_HEADER,candidateDevice))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("LOGIN_ASSISTANCE_NOT_ALLOWED"));
    }
    private MailListener received() {
        MailListener l=new MailListener(owner.getId(),team.getId(),account.getId(),mailboxes.findById(account.getId()).orElseThrow().revision,NOW);
        l.bindingAttemptId=attempt;l.state=MailListener.State.RECEIVED;l.activeAccountId=null;
        l.codeCipher=cipher.encrypt("123456",l.aad());l.receivedAt=NOW;l.resultExpiresAt=NOW.plusSeconds(60);l.expiresAt=l.resultExpiresAt;
        return listeners.saveAndFlush(l);
    }
    private User member(String email) { User u=new User(email,"synthetic-unused","Member");u.updateQuotaPolicy(team.getId(),null,null,NOW);return users.saveAndFlush(u); }
    private String token(User user) { return "Bearer "+jwt.create(user.getId(),user.getRole().name(),user.getSessionVersion()); }
    private String base() { return "/api/v1/client/login-assistance"; }
}
