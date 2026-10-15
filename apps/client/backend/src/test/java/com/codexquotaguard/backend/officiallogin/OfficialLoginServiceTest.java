package com.codexquotaguard.backend.officiallogin;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.codexquotaguard.backend.controlplane.SystemUser;
import com.codexquotaguard.backend.system.SystemSessionService;
import java.time.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OfficialLoginServiceTest {
    final UUID owner = UUID.randomUUID(), jobId = UUID.randomUUID(), account = UUID.randomUUID();
    final Instant now = Instant.parse("2026-10-15T00:00:00Z");
    final SystemSessionService sessions = mock(SystemSessionService.class);
    final OfficialLoginGateway gateway = mock(OfficialLoginGateway.class);
    final OfficialLoginBrowser browser = mock(OfficialLoginBrowser.class);
    final OfficialLoginBrowser.Session browserSession = mock(OfficialLoginBrowser.Session.class);
    final Queue<Runnable> tasks = new ArrayDeque<>();
    final CompletableFuture<OfficialLoginBrowser.Authorization> authorization = new CompletableFuture<>();
    OfficialLoginService service;

    @BeforeEach void setup() {
        when(sessions.current()).thenReturn(user(owner,"ADMIN"));
        when(sessions.localUser()).thenReturn(Optional.of(user(owner,"ADMIN")));
        when(sessions.localUserId()).thenReturn(Optional.of(owner));
        when(sessions.withAuthenticatedToken(any())).thenAnswer(inv -> ((Function<String,?>)inv.getArgument(0)).apply("synthetic-system-token"));
        when(gateway.claim(anyString(),eq(jobId),any())).thenReturn(job("WAITING_FOR_LOGIN",now.plusSeconds(600)));
        when(gateway.get(anyString(),eq(jobId))).thenReturn(job("WAITING_FOR_LOGIN",now.plusSeconds(600)));
        when(gateway.complete(anyString(),eq(jobId),any())).thenReturn(job("COMPLETED",now.plusSeconds(600)));
        when(gateway.cancel(anyString(),eq(jobId))).thenReturn(job("CANCELLED",now.plusSeconds(600)));
        when(browser.open(OfficialLoginBrowser.Browser.EDGE)).thenReturn(browserSession);
        when(browserSession.authorization()).thenReturn(authorization);
        service = new OfficialLoginService(sessions,gateway,browser,Clock.fixed(now,ZoneOffset.UTC),tasks::add);
    }

    @Test void startsOnlyAnOwnedAdminJobAndSubmitsOnlyAfterBrowserObservation() {
        assertThat(service.start(jobId,OfficialLoginBrowser.Browser.EDGE).state()).isEqualTo("STARTING");
        tasks.remove().run();
        assertThat(service.status().state()).isEqualTo("WAITING_FOR_LOGIN");
        verify(gateway,never()).complete(anyString(),any(),any());
        authorization.complete(new OfficialLoginBrowser.Authorization("Bearer synthetic-token","__Secure-next-auth.session-token=synthetic-cookie","synthetic-account"));
        while(!tasks.isEmpty()) tasks.remove().run();
        assertThat(service.status().state()).isEqualTo("COMPLETED");
        verify(gateway).complete(eq("synthetic-system-token"),eq(jobId),argThat(value ->
            value.authorization().equals("Bearer synthetic-token") && value.requestedAccountId().equals("synthetic-account")
            && !value.toString().contains("synthetic-token")));
        verify(browserSession).close();
        assertThat(service.status().toString()).doesNotContain("synthetic-token","synthetic-cookie","synthetic-system-token");
    }

    @Test void rejectsRegularUsersAndConcurrentTasks() {
        when(sessions.current()).thenReturn(user(owner,"USER"));
        assertThatThrownBy(() -> service.jobs()).isInstanceOf(OfficialLoginException.class);
        verifyNoInteractions(gateway,browser);
        when(sessions.current()).thenReturn(user(owner,"ADMIN"));
        service.start(jobId,OfficialLoginBrowser.Browser.EDGE);
        assertThatThrownBy(() -> service.start(UUID.randomUUID(),OfficialLoginBrowser.Browser.CHROME)).isInstanceOf(OfficialLoginException.class);
        verify(gateway,times(1)).claim(anyString(),any(),any());
    }

    @Test void cancelledTaskCannotSubmitLateBrowserCredentials() {
        service.start(jobId,OfficialLoginBrowser.Browser.EDGE); tasks.remove().run();
        assertThat(service.cancel().state()).isEqualTo("CANCELLED");
        authorization.complete(new OfficialLoginBrowser.Authorization("Bearer synthetic-token",null,"synthetic-account"));
        while(!tasks.isEmpty()) tasks.remove().run();
        verify(gateway,never()).complete(anyString(),any(),any());
        verify(browserSession).close();
    }

    @Test void identitySwitchClosesTheOwnedBrowserAndNeverUsesTheNewUserToken() {
        service.start(jobId,OfficialLoginBrowser.Browser.EDGE); tasks.remove().run();
        UUID other = UUID.randomUUID();
        when(sessions.localUserId()).thenReturn(Optional.of(other));
        when(sessions.current()).thenReturn(user(other,"ADMIN"));
        service.poll();
        authorization.complete(new OfficialLoginBrowser.Authorization("Bearer synthetic-token",null,"synthetic-account"));
        while(!tasks.isEmpty()) tasks.remove().run();
        assertThat(service.status().jobId()).isNull();
        assertThat(service.status().accountName()).isNull();
        verify(gateway,never()).complete(anyString(),any(),any());
        verify(gateway,never()).cancel(anyString(),any());
        verify(browserSession).close();
    }

    @Test void serverExpiryAndShutdownPreventLateSubmission() {
        when(gateway.claim(anyString(),eq(jobId),any())).thenReturn(job("WAITING_FOR_LOGIN",now.minusSeconds(1)));
        assertThatThrownBy(() -> service.start(jobId,OfficialLoginBrowser.Browser.EDGE)).isInstanceOf(OfficialLoginException.class);
        verifyNoInteractions(browser);
        when(gateway.claim(anyString(),eq(jobId),any())).thenReturn(job("WAITING_FOR_LOGIN",now.plusSeconds(600)));
        service.start(jobId,OfficialLoginBrowser.Browser.EDGE); tasks.remove().run();
        service.close();
        authorization.complete(new OfficialLoginBrowser.Authorization("Bearer synthetic-token",null,"synthetic-account"));
        while(!tasks.isEmpty()) tasks.remove().run();
        verify(gateway,never()).complete(anyString(),any(),any());
        verify(browserSession).close();
    }

    @Test void reportsCentralVerificationFailureWithoutClaimingSuccess() {
        when(gateway.complete(anyString(),eq(jobId),any())).thenReturn(new OfficialLoginGateway.Job(jobId,account,"Synthetic account","FAILED",now.plusSeconds(600),"OFFICIAL_CHALLENGE_REQUIRED",null));
        service.start(jobId,OfficialLoginBrowser.Browser.EDGE); tasks.remove().run();
        authorization.complete(new OfficialLoginBrowser.Authorization("Bearer synthetic-token",null,"synthetic-account")); tasks.remove().run();
        assertThat(service.status().state()).isEqualTo("FAILED");
        assertThat(service.status().errorCode()).isEqualTo("OFFICIAL_CHALLENGE_REQUIRED");
        verify(browserSession).close();
    }

    @Test void startAndStatusDoNotPerformNetworkOperationsOnTheUiThread() {
        assertThat(service.start(jobId,OfficialLoginBrowser.Browser.EDGE).state()).isEqualTo("STARTING");
        assertThat(service.status().state()).isEqualTo("STARTING");
        verify(sessions,never()).current();
        verifyNoInteractions(gateway,browser);
    }

    @Test void submissionAtTheLoginDeadlineGetsItsOwnBoundedSavingWindow() {
        var moment=new java.util.concurrent.atomic.AtomicReference<>(now);
        Clock moving=mock(Clock.class);when(moving.instant()).thenAnswer(call -> moment.get());
        service=new OfficialLoginService(sessions,gateway,browser,moving,tasks::add);
        service.start(jobId,OfficialLoginBrowser.Browser.EDGE);tasks.remove().run();
        moment.set(now.plusSeconds(599));
        when(gateway.complete(anyString(),eq(jobId),any())).thenAnswer(call -> {
            moment.set(now.plusSeconds(601));service.poll();return job("COMPLETED",now.plusSeconds(719));
        });
        authorization.complete(new OfficialLoginBrowser.Authorization("Bearer synthetic-token",null,"synthetic-account"));tasks.remove().run();
        assertThat(service.status().state()).isEqualTo("COMPLETED");
    }

    @Test void cannotCancelOnceCentralSavingHasStarted() {
        service.start(jobId,OfficialLoginBrowser.Browser.EDGE);tasks.remove().run();
        when(gateway.complete(anyString(),eq(jobId),any())).thenAnswer(call -> {
            assertThatThrownBy(service::cancel).isInstanceOf(OfficialLoginException.class);
            assertThat(service.status().state()).isEqualTo("SAVING");
            return job("COMPLETED",now.plusSeconds(600));
        });
        authorization.complete(new OfficialLoginBrowser.Authorization("Bearer synthetic-token",null,"synthetic-account"));tasks.remove().run();
        assertThat(service.status().state()).isEqualTo("COMPLETED");
        verify(gateway,never()).cancel(anyString(),any());
    }

    private OfficialLoginGateway.Job job(String state, Instant expires) { return new OfficialLoginGateway.Job(jobId,account,"Synthetic account",state,expires,null,null); }
    private SystemUser user(UUID id,String role) { return new SystemUser(id,"synthetic@example.test","Synthetic member",role,"ACTIVE",null,null,null,null,null,null,null); }
}
