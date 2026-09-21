package com.codexquotaguard.backend.system;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/system")
public final class SystemController {

    private final ApplicationShutdownService shutdownService;

    public SystemController(ApplicationShutdownService shutdownService) {
        this.shutdownService = shutdownService;
    }

    @PostMapping("/shutdown")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void shutdown() {
        shutdownService.requestShutdown();
    }
}
