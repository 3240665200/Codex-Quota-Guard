package com.codexquotaguard.backend.system;

import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.concurrent.Executor;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ApplicationShutdownServiceTest {

    @Test
    void closesTheSpringContextThroughTheShutdownExecutor() {
        var context = mock(ConfigurableApplicationContext.class);
        Executor sameThread = Runnable::run;
        var service = new ApplicationShutdownService(context, sameThread, 0L);

        service.requestShutdown();

        verify(context).close();
    }
}
