package com.codexquotaguard.backend.officiallogin;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/official-login-helper")
public final class OfficialLoginController {
    private final OfficialLoginService service;
    public OfficialLoginController(OfficialLoginService service) { this.service=service; }
    record StartRequest(@NotNull OfficialLoginBrowser.Browser browser) { }
    @GetMapping("/jobs") public ResponseEntity<List<OfficialLoginGateway.Job>> jobs() { return result(service.jobs()); }
    @PostMapping("/jobs/{id}/start") public ResponseEntity<OfficialLoginService.Status> start(@PathVariable UUID id,@Valid @RequestBody StartRequest body) { return result(service.start(id,body.browser())); }
    @GetMapping("/status") public ResponseEntity<OfficialLoginService.Status> status() { return result(service.status()); }
    @PostMapping("/cancel") public ResponseEntity<OfficialLoginService.Status> cancel() { return result(service.cancel()); }
    private static <T> ResponseEntity<T> result(T body) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body); }
}
