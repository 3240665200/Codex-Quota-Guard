package com.pingche.web.quota;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/usage/reviews/bulk-jobs")
@PreAuthorize("hasRole('ADMIN')")
public class UsageReviewBulkController {
    public record Preview(@NotNull @Pattern(regexp="ARCHIVE|IMPORT") String action, UUID userId, Instant from, Instant to) { }
    public record Start(@NotBlank @Size(max=500) String note) { }
    private final UsageReviewBulkService jobs;
    public UsageReviewBulkController(UsageReviewBulkService jobs) { this.jobs = jobs; }
    @PostMapping
    public ResponseEntity<UsageReviewBulkService.Job> preview(@Valid @RequestBody Preview request, Authentication auth) {
        return result(jobs.preview((UUID) auth.getPrincipal(), request.action(), request.userId(), request.from(), request.to()));
    }
    @PostMapping("/{id}/start")
    public ResponseEntity<UsageReviewBulkService.Job> start(@PathVariable UUID id, @Valid @RequestBody Start request, Authentication auth) {
        return result(jobs.start(id, (UUID) auth.getPrincipal(), request.note()));
    }
    @GetMapping public ResponseEntity<List<UsageReviewBulkService.Job>> recent() { return result(jobs.recent()); }
    @GetMapping("/{id}") public ResponseEntity<UsageReviewBulkService.Job> get(@PathVariable UUID id) { return result(jobs.get(id)); }
    private <T> ResponseEntity<T> result(T value) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value); }
}
