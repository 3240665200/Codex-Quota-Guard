package com.pingche.web.quota;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.time.Instant;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@Validated
@RequestMapping("/api/v1/admin/usage/reviews")
@PreAuthorize("hasRole('ADMIN')")
public class UsageReviewController {
    public record Action(@NotEmpty @Size(max=100) List<@NotNull @Pattern(regexp="[0-9a-f]{64}") String> ids,
            @NotNull @Pattern(regexp="ARCHIVE|IMPORT") String action, @NotBlank @Size(max=500) String note) { }
    private final UsageReviewService reviews;
    public UsageReviewController(UsageReviewService reviews) { this.reviews = reviews; }

    @GetMapping
    public ResponseEntity<UsageReviewService.Page<UsageReviewService.Item>> list(
            @RequestParam(defaultValue="PENDING") String status, @RequestParam(required=false) UUID userId,
            @RequestParam(defaultValue="0") @Min(0) @Max(1_000_000) int page,
            @RequestParam(defaultValue="20") @Min(1) @Max(100) int size,
            @RequestParam(required=false) Instant from, @RequestParam(required=false) Instant to) {
        return result(reviews.list(status, userId, page, size, from, to));
    }
    @GetMapping("/billing")
    public ResponseEntity<UsageReviewService.Page<UsageReviewService.PendingBill>> billing(@RequestParam(required=false) UUID userId,
            @RequestParam(defaultValue="0") @Min(0) @Max(1_000_000) int page,
            @RequestParam(defaultValue="20") @Min(1) @Max(100) int size,
            @RequestParam(required=false) Instant from, @RequestParam(required=false) Instant to) {
        return result(reviews.pendingBilling(userId, page, size, from, to));
    }
    @PostMapping("/actions")
    public ResponseEntity<Map<String, Integer>> act(@Valid @RequestBody Action request, Authentication authentication) {
        return result(Map.of("updated", reviews.review(request.ids(), request.action(), request.note(), (UUID) authentication.getPrincipal())));
    }
    private <T> ResponseEntity<T> result(T value) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value); }
}
