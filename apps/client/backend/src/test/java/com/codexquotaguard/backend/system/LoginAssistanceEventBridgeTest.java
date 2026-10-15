package com.codexquotaguard.backend.system;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.awaitility.Awaitility.await;
import com.codexquotaguard.backend.controlplane.*;
import java.io.InputStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class LoginAssistanceEventBridgeTest {
    private final ControlPlaneGateway gateway=mock(ControlPlaneGateway.class);
    private final SystemSessionService sessions=new SystemSessionService(gateway);
    private final Clock clock=mock(Clock.class);
    private final AtomicReference<Instant> now=new AtomicReference<>(Instant.parse("2026-10-15T00:00:00Z"));
    private final SystemUser user=new SystemUser(UUID.randomUUID(),"synthetic@example.test","Synthetic","USER","ACTIVE",null,null,null,null,null,null,null);

    private void login() {
        when(clock.instant()).thenAnswer(call->now.get());
        when(gateway.login(anyString(),anyString())).thenReturn(new ControlPlaneAuthSession("synthetic-access","synthetic-refresh",user));
        when(gateway.current(anyString())).thenReturn(user);
        sessions.login("synthetic@example.test","not-a-real-password");
    }

    @Test void restorationUsesTheSameGenerationFenceAsOtherMailOperations() {
        login();
        when(gateway.currentLoginAssistance("synthetic-access")).thenAnswer(call->{sessions.logout();return new LoginAssistanceCurrent(null);});
        assertThatThrownBy(sessions::currentLoginAssistance).isInstanceOf(SystemSessionRequiredException.class);
    }

    @Test void streamRequiresLoginAndNeverRestoresAnUnauthenticatedListener() {
        try(var bridge=new LoginAssistanceEventBridge(sessions,gateway)) {
            assertThatThrownBy(bridge::subscribe).isInstanceOf(SystemSessionRequiredException.class);
            verify(gateway,never()).loginAssistanceEvents(any());
        }
    }

    @Test void disconnectClosesOnlyTheStreamAndDoesNotCancelTheMailTask() throws Exception {
        login();
        var input=new BlockingInput();var emitter=mock(SseEmitter.class);
        var completion=new AtomicReference<Runnable>();
        doAnswer(call->{completion.set(call.getArgument(0));return null;}).when(emitter).onCompletion(any());
        when(gateway.loginAssistanceEvents("synthetic-access")).thenReturn(new AssistanceEventStream(input));
        try(var bridge=new LoginAssistanceEventBridge(sessions,gateway,()->emitter,clock)) {
            assertThat(bridge.subscribe()).isSameAs(emitter);
            input.event("connected");
            verify(emitter,timeout(2000)).send(any(SseEmitter.SseEventBuilder.class));
            completion.get().run();
            await().atMost(Duration.ofSeconds(2)).untilTrue(input.closed);
            assertThat(bridge.activeCount()).isZero();
            verify(gateway,never()).cancelLoginAssistance(any(),any());
            verify(gateway,never()).startLoginAssistance(any());
        }
    }

    @Test void logoutAndSameUserReloginCloseTheOldStreamWithoutForwardingMoreSignals() {
        login();var input=new BlockingInput();var emitter=mock(SseEmitter.class);
        when(gateway.loginAssistanceEvents("synthetic-access")).thenReturn(new AssistanceEventStream(input));
        try(var bridge=new LoginAssistanceEventBridge(sessions,gateway,()->emitter,clock)) {
            bridge.subscribe();sessions.logout();login();bridge.sweep();
            assertThat(input.closed).isTrue();assertThat(bridge.activeCount()).isZero();
            verify(gateway,never()).cancelLoginAssistance(any(),any());
        }
    }

    @Test void lateOpenedStreamAfterLogoutIsClosedBeforeTheRendererCanSubscribe() {
        login();var input=new BlockingInput();
        when(gateway.loginAssistanceEvents("synthetic-access")).thenAnswer(call->{sessions.logout();return new AssistanceEventStream(input);});
        try(var bridge=new LoginAssistanceEventBridge(sessions,gateway,()->mock(SseEmitter.class),clock)) {
            assertThatThrownBy(bridge::subscribe).isInstanceOf(SystemSessionRequiredException.class);
            assertThat(input.closed).isTrue();assertThat(bridge.activeCount()).isZero();
        }
    }

    @Test void idleUpstreamIsBoundedAndCapacityIsReleased() {
        login();var input=new BlockingInput();
        when(gateway.loginAssistanceEvents("synthetic-access")).thenReturn(new AssistanceEventStream(input));
        try(var bridge=new LoginAssistanceEventBridge(sessions,gateway,()->mock(SseEmitter.class),clock)) {
            bridge.subscribe();now.set(now.get().plusSeconds(46));bridge.sweep();
            assertThat(input.closed).isTrue();assertThat(bridge.activeCount()).isZero();
        }
    }

    @Test void boundedCapacityDoesNotOpenAdditionalUpstreamStreams() {
        login();when(gateway.loginAssistanceEvents("synthetic-access")).thenAnswer(call->new AssistanceEventStream(new BlockingInput()));
        try(var bridge=new LoginAssistanceEventBridge(sessions,gateway,()->mock(SseEmitter.class),clock)) {
            bridge.subscribe();bridge.subscribe();
            assertThatThrownBy(bridge::subscribe).isInstanceOf(ControlPlaneUnavailableException.class);
            verify(gateway,times(2)).loginAssistanceEvents(any());
        }
    }

    static final class BlockingInput extends InputStream {
        final LinkedBlockingQueue<Integer> values=new LinkedBlockingQueue<>();
        final AtomicBoolean closed=new AtomicBoolean();
        void event(String name){("event:"+name+"\ndata:{}\n\n").chars().forEach(values::add);}
        @Override public int read(){try{return values.take();}catch(InterruptedException ignored){Thread.currentThread().interrupt();return -1;}}
        @Override public void close(){closed.set(true);values.offer(-1);}
    }
}
