package com.pingche.web.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.pingche.web.auth.DomainException;
import com.pingche.web.auth.JwtService;
import com.pingche.web.auth.SystemLogoutEvent;
import com.pingche.web.user.User;
import com.pingche.web.user.UserRepository;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class LoginAssistanceEventsTest {
    private final Instant now = Instant.parse("2026-10-15T12:00:00Z");
    private final JwtService jwt = mock(JwtService.class);
    private final UserRepository users = mock(UserRepository.class);
    private final LoginAssistanceState state = mock(LoginAssistanceState.class);
    private final Clock clock = mock(Clock.class);
    private final List<SseEmitter> emitters = new ArrayList<>();
    private final List<List<String>> frames = new ArrayList<>();
    private final User owner = new User("owner@example.test", "unused", "Owner");
    private LoginAssistanceEvents service;

    @BeforeEach void setup() throws Exception {
        when(clock.instant()).thenReturn(now);
        when(users.findById(owner.getId())).thenReturn(Optional.of(owner));
        when(state.fingerprint(any())).thenReturn("initial");
        when(jwt.parseIdentity("synthetic-token")).thenReturn(new JwtService.JwtIdentity(owner.getId(), 0, now.plusSeconds(120)));
        service = new LoginAssistanceEvents(jwt, users, state, clock, () -> {
            SseEmitter emitter = mock(SseEmitter.class);
            List<String> sent = new ArrayList<>();
            try {
                doAnswer(invocation -> {
                    SseEmitter.SseEventBuilder event = invocation.getArgument(0);
                    sent.add(event.build().stream().map(part -> String.valueOf(part.getData())).reduce("", String::concat));
                    return null;
                }).when(emitter).send(any(SseEmitter.SseEventBuilder.class));
            } catch (IOException impossible) { throw new IllegalStateException(impossible); }
            emitters.add(emitter); frames.add(sent); return emitter;
        }, false);
    }

    @AfterEach void cleanup() { service.destroy(); }

    @Test void sendsOnlyInvalidationsAndHeartbeatAndDeduplicatesUnchangedState() {
        service.subscribe(owner.getId(), "synthetic-token");
        assertThat(frames.get(0)).singleElement().asString().contains("event:connected", "{}");
        service.tick();
        assertThat(frames.get(0)).hasSize(1);
        when(state.fingerprint(owner.getId())).thenReturn("changed");
        service.tick(); service.tick();
        assertThat(frames.get(0)).hasSize(2);
        assertThat(frames.get(0).get(1)).contains("event:state_changed", "{}");
        when(clock.instant()).thenReturn(now.plusSeconds(15));
        service.tick();
        assertThat(frames.get(0).get(2)).contains("event:heartbeat", "{}");
        assertThat(String.join("", frames.get(0))).doesNotContain("owner@", "synthetic-token", "initial", "changed", "code", "password");
    }

    @Test void readsDatabaseStateOncePerOwnerPerTickAndIsolatesUsers() {
        service.subscribe(owner.getId(), "synthetic-token");
        service.subscribe(owner.getId(), "synthetic-token");
        User other = new User("other@example.test", "unused", "Other");
        when(users.findById(other.getId())).thenReturn(Optional.of(other));
        when(jwt.parseIdentity("other-token")).thenReturn(new JwtService.JwtIdentity(other.getId(), 0, now.plusSeconds(120)));
        service.subscribe(other.getId(), "other-token");
        clearInvocations(state, users);
        when(state.fingerprint(owner.getId())).thenReturn("owner-change");
        service.tick();
        verify(state, times(1)).fingerprint(owner.getId());
        verify(users, times(1)).findById(owner.getId());
        assertThat(frames.get(0)).hasSize(2);
        assertThat(frames.get(1)).hasSize(2);
        assertThat(frames.get(2)).hasSize(1);
    }

    @Test void expirySessionRevocationAndDisabledUserCloseStreamsWithoutSendingMoreState() {
        service.subscribe(owner.getId(), "synthetic-token");
        when(clock.instant()).thenReturn(now.plusSeconds(120));
        service.tick();
        verify(emitters.get(0)).complete();
        assertThat(service.subscriptionCount()).isZero();
        when(clock.instant()).thenReturn(now);
        service.subscribe(owner.getId(), "synthetic-token");
        owner.invalidateSessions();
        service.tick();
        verify(emitters.get(1)).complete();
        assertThat(service.subscriptionCount()).isZero();
        when(jwt.parseIdentity("synthetic-token")).thenReturn(new JwtService.JwtIdentity(owner.getId(), 1, now.plusSeconds(120)));
        service.subscribe(owner.getId(), "synthetic-token");
        owner.updateAdministration(User.Status.DISABLED, null);
        service.tick();
        verify(emitters.get(2)).complete();
        assertThat(frames).allSatisfy(sent -> assertThat(sent).hasSize(1));
    }

    @Test void enforcesIdentityAndPerOwnerCapacityAndReleasesSlotsOnCompletion() {
        assertThatThrownBy(() -> service.subscribe(UUID.randomUUID(), "synthetic-token")).isInstanceOf(DomainException.class);
        service.subscribe(owner.getId(), "synthetic-token");
        service.subscribe(owner.getId(), "synthetic-token");
        assertThatThrownBy(() -> service.subscribe(owner.getId(), "synthetic-token"))
                .isInstanceOfSatisfying(DomainException.class, error -> assertThat(error.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
        var completion = org.mockito.ArgumentCaptor.forClass(Runnable.class);
        verify(emitters.get(0)).onCompletion(completion.capture());
        completion.getValue().run();
        service.subscribe(owner.getId(), "synthetic-token");
        assertThat(service.subscriptionCount()).isEqualTo(2);
    }

    @Test void logoutAndDisconnectDoNotCancelOtherOwnersOrCreateNewMailboxWork() {
        service.subscribe(owner.getId(), "synthetic-token");
        service.systemLogout(new SystemLogoutEvent(UUID.randomUUID()));
        assertThat(service.subscriptionCount()).isEqualTo(1);
        service.systemLogout(new SystemLogoutEvent(owner.getId()));
        verify(emitters.get(0)).complete();
        assertThat(service.subscriptionCount()).isZero();
        service.subscribe(owner.getId(), "synthetic-token");
        assertThat(frames.get(1)).singleElement().asString().contains("event:connected");
    }

    @Test void streamSendAndDatabaseFailuresReleaseCapacityWithoutExposingTheirMessages() throws Exception {
        service.subscribe(owner.getId(), "synthetic-token");
        doThrow(new IOException("secret-provider-message")).when(emitters.get(0)).send(any(SseEmitter.SseEventBuilder.class));
        when(state.fingerprint(owner.getId())).thenReturn("changed");
        service.tick();
        assertThat(service.subscriptionCount()).isZero();
        service.subscribe(owner.getId(), "synthetic-token");
        when(state.fingerprint(owner.getId())).thenThrow(new IllegalStateException("secret-db-message"));
        service.tick();
        assertThat(service.subscriptionCount()).isZero();
        assertThat(frames).allSatisfy(sent -> assertThat(String.join("", sent)).doesNotContain("secret-provider", "secret-db"));
    }
}
