package com.pingche.web.quota;

import com.pingche.web.auth.DomainException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin")
@PreAuthorize("hasRole('ADMIN')")
public class UsdBudgetController {
    public record Budget(@DecimalMin("0") @DecimalMax("1000000000000") @Digits(integer=13,fraction=12) BigDecimal fiveHourUsdLimit,
            @DecimalMin("0") @DecimalMax("1000000000000") @Digits(integer=13,fraction=12) BigDecimal weeklyUsdLimit,
            @DecimalMin("0") @DecimalMax("1000000000000") @Digits(integer=13,fraction=12) BigDecimal totalUsdLimit,
            UUID officialAccountId, Long version) { }
    public record ModeDeclaration(@NotNull @Pattern(regexp="standard|fast|unknown") String serviceTier) { }
    public record ModeConfirmation(@NotEmpty @Size(max=1000) List<@NotNull @Pattern(regexp="[0-9a-f]{64}") String> eventIds,
            @NotNull @Pattern(regexp="standard|fast") String serviceTier) { }
    private final JdbcTemplate jdbc;
    private final UsdQuotaService quotas;
    private final UsageBillingService billing;
    private final com.pingche.web.team.TeamRepository teams;
    private final com.pingche.web.team.TeamTokenLimitService associations;
    public UsdBudgetController(JdbcTemplate jdbc, UsdQuotaService quotas, UsageBillingService billing,
            com.pingche.web.team.TeamRepository teams, com.pingche.web.team.TeamTokenLimitService associations) {
        this.jdbc=jdbc; this.quotas=quotas; this.billing=billing; this.teams=teams; this.associations=associations;
    }
    @PutMapping("/users/{id}/usd-limits") @Transactional
    public ResponseEntity<UsdQuotaService.Scope> user(@PathVariable UUID id, @Valid @RequestBody Budget request) {
        if (request.totalUsdLimit != null) throw new DomainException("PERSONAL_TOTAL_BUDGET_UNSUPPORTED","个人额度使用 5 小时和 7 天窗口，请勿填写累计上限。",HttpStatus.BAD_REQUEST);
        int changed = jdbc.update("update users set five_hour_usd_limit=?,weekly_usd_limit=? where id=?", request.fiveHourUsdLimit, request.weeklyUsdLimit, id);
        if (changed != 1) throw missing();
        return result(quotas.personal(id));
    }
    @PutMapping("/teams/{id}/usd-limits") @Transactional
    public ResponseEntity<UsdQuotaService.Scope> team(@PathVariable UUID id, @Valid @RequestBody Budget request) {
        var current = teams.findByIdForUpdate(id).orElseThrow(this::missing);
        // Reuse the existing membership/session invalidation path for association changes.
        // Money-only edits preserve active bindings and legacy Token policy values.
        if (!java.util.Objects.equals(current.getOfficialAccountId(), request.officialAccountId)) {
            associations.update(id, new com.pingche.web.team.TeamTokenLimitRequest(current.getFiveHourTokenLimit(),
                    current.getWeeklyTokenLimit(), null, request.officialAccountId, request.version));
        } else if (request.version != null && request.version != current.getVersion()) {
            throw new DomainException("TEAM_VERSION_CONFLICT","团队配置已变化，请刷新后重试。",HttpStatus.CONFLICT);
        }
        var rows = jdbc.query("select official_account_id from teams where id=? for update", (rs,n) -> rs.getObject(1,UUID.class), id);
        if (rows.isEmpty()) throw missing();
        BigDecimal total = rows.get(0) == null ? request.totalUsdLimit : jdbc.queryForObject("select budget_usd from official_accounts where id=?",BigDecimal.class,rows.get(0));
        jdbc.update("update teams set five_hour_usd_limit=?,weekly_usd_limit=?,total_usd_limit=? where id=?",request.fiveHourUsdLimit,request.weeklyUsdLimit,total,id);
        return result(quotas.team(id));
    }
    @GetMapping("/teams/{id}/billing")
    public ResponseEntity<UsdQuotaService.Scope> team(@PathVariable UUID id) { return result(quotas.team(id)); }
    @GetMapping("/users/{id}/billing")
    public ResponseEntity<UsdQuotaService.Overview> user(@PathVariable UUID id) { return result(quotas.overview(id,true)); }
    @PostMapping("/usage/billing/reconcile")
    public ResponseEntity<java.util.Map<String,Integer>> reconcile() { return result(java.util.Map.of("updated",billing.reconcileMissingPrices())); }
    @GetMapping("/users/{id}/billing-modes")
    public ResponseEntity<List<UsageBillingService.DeviceMode>> modes(@PathVariable UUID id) { return result(billing.deviceModes(id)); }
    @PutMapping("/users/{id}/billing-modes/{deviceId}")
    public ResponseEntity<List<UsageBillingService.DeviceMode>> mode(@PathVariable UUID id, @PathVariable UUID deviceId,
            @Valid @RequestBody ModeDeclaration request, Authentication authentication) {
        return result(billing.declareMode(id,deviceId,request.serviceTier(),(UUID) authentication.getPrincipal()));
    }
    @PostMapping("/usage/billing/confirm-mode")
    public ResponseEntity<java.util.Map<String,Integer>> confirm(@Valid @RequestBody ModeConfirmation request, Authentication authentication) {
        return result(java.util.Map.of("updated",billing.confirmMode(request.eventIds(),request.serviceTier(),(UUID) authentication.getPrincipal())));
    }
    private <T> ResponseEntity<T> result(T value) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value); }
    private DomainException missing() { return new DomainException("BUDGET_OWNER_NOT_FOUND","额度所属用户或团队不存在。",HttpStatus.NOT_FOUND); }
}
