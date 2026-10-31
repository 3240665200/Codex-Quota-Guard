package com.pingche.web.quota;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import static com.pingche.web.quota.UsageBillingReview.*;

@RestController
@Validated
@PreAuthorize("hasRole('ADMIN')")
@RequestMapping("/api/v1/admin/usage/billing/entries/{id}")
public class UsageBillingReviewController {
    private final UsageBillingReviewService reviews;
    public UsageBillingReviewController(UsageBillingReviewService reviews) { this.reviews=reviews; }
    @GetMapping
    public ResponseEntity<Detail> detail(@PathVariable @Pattern(regexp="[a-f0-9]{64}") String id) { return result(reviews.detail(id)); }
    @PostMapping("/preview")
    public ResponseEntity<Preview> preview(@PathVariable @Pattern(regexp="[a-f0-9]{64}") String id,@Valid @RequestBody Request request) {
        return result(reviews.preview(id,request));
    }
    @PostMapping("/review")
    public ResponseEntity<Saved> save(@PathVariable @Pattern(regexp="[a-f0-9]{64}") String id,@Valid @RequestBody Request request,Authentication authentication) {
        return result(reviews.save(id,request,(UUID)authentication.getPrincipal()));
    }
    private <T> ResponseEntity<T> result(T value) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value); }
}
