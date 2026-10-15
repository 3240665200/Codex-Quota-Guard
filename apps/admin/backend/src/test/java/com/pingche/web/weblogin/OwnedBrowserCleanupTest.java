package com.pingche.web.weblogin;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class OwnedBrowserCleanupTest {
    @Test
    void waitsForOwnedDescendantsToExitBeforeProfileDeletionCanBegin() throws Exception {
        Process process = mock(Process.class);
        ProcessHandle child = mock(ProcessHandle.class);
        AtomicBoolean alive = new AtomicBoolean(true), observedExit = new AtomicBoolean();
        when(process.descendants()).thenReturn(Stream.of(child));
        when(process.waitFor(anyLong(), any())).thenReturn(true);
        when(child.isAlive()).thenAnswer(ignored -> alive.get());
        when(child.onExit()).thenAnswer(ignored -> {
            observedExit.set(true); alive.set(false); return CompletableFuture.completedFuture(child);
        });
        var method = ChromiumManagedLoginBrowser.class.getDeclaredMethod("terminate", Process.class);
        method.setAccessible(true); method.invoke(null, process);
        assertThat(observedExit.get()).isTrue();
        assertThat(alive.get()).isFalse();
    }
}
