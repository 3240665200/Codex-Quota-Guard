package com.pingche.web.quota;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import com.pingche.web.auth.DomainException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Immutable first-pricing snapshots. Changing the catalog never rewrites billed rows. */
@Service
public class UsageBillingService {
    public record Entry(String eventId, BigDecimal costUsd, String costStatus, BigDecimal multiplier,
            String modeStatus, String origin, Instant pricedAt, String modeSource) { }
    public record DeviceMode(UUID deviceId, String status, String serviceTier, Instant effectiveAt) { }
    private record Mode(String tier, String status, String source) { }
    private final JdbcTemplate jdbc;
    private final UsageModelPriceRepository prices;
    private final CodexUsageEventRepository events;
    private final Clock clock;

    public UsageBillingService(JdbcTemplate jdbc, UsageModelPriceRepository prices,
            CodexUsageEventRepository events, Clock clock) {
        this.jdbc = jdbc; this.prices = prices; this.events = events; this.clock = clock;
    }

    @Transactional
    public void record(Collection<CodexUsageEvent> batch) {
        for (CodexUsageEvent event : batch) {
            if (jdbc.queryForObject("select count(*) from usage_billing_entries where event_id=?", Integer.class, event.getId()) > 0) continue;
            List<UUID> linked = event.getTeamId() == null ? List.of() : jdbc.query(
                    "select official_account_id from teams where id=?", (rs,n) -> rs.getObject(1, UUID.class), event.getTeamId());
            UUID account = linked.isEmpty() ? null : linked.get(0);
            write(event, account, "LIVE", resolveMode(event), false);
        }
    }

    /** Only unresolved missing-price rows may be completed; PRICED rows are never updated. */
    @Transactional
    public int reconcileMissingPrices() {
        List<String> ids = jdbc.query("select b.event_id from usage_billing_entries b join codex_usage_events e on e.id=b.event_id "
                + "join usage_model_prices p on p.model=e.model where b.cost_status='MISSING_PRICE' order by b.event_id limit 500",
                (rs,n) -> rs.getString(1));
        int updated = 0;
        for (CodexUsageEvent event : events.findAllById(ids)) {
            var row = jdbc.queryForMap("select * from usage_billing_entries where event_id=? for update", event.getId());
            if (!"MISSING_PRICE".equals(row.get("cost_status"))) continue;
            String origin = (String) row.get("origin");
            String mode = (String) row.get("mode_status");
            Mode resolved = "FAST".equals(mode) ? new Mode("fast", mode, (String) row.get("mode_source"))
                    : "STANDARD".equals(mode) ? new Mode("standard", mode, (String) row.get("mode_source"))
                    : "HISTORICAL_RECALC".equals(origin) ? new Mode("standard", "UNRECORDED", "HISTORICAL_UNRECORDED")
                    : resolveMode(event);
            updated += write(event, (UUID) row.get("official_account_id"), origin, resolved, true);
        }
        return updated;
    }

    public Map<String, Entry> entries(Collection<String> ids) {
        if (ids.isEmpty()) return Map.of();
        if (ids.size() > 1000) throw new IllegalArgumentException("Billing lookup is limited to 1000 events");
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        return jdbc.query("select event_id,cost_usd,cost_status,multiplier,mode_status,origin,priced_at,mode_source from usage_billing_entries where event_id in (" + placeholders + ")",
                (rs,n) -> new Entry(rs.getString(1), rs.getBigDecimal(2), rs.getString(3), rs.getBigDecimal(4),
                        rs.getString(5), rs.getString(6), rs.getTimestamp(7).toInstant(), rs.getString(8)), ids.toArray())
                .stream().collect(Collectors.toMap(Entry::eventId, entry -> entry));
    }

    @Transactional(readOnly = true)
    public List<DeviceMode> deviceModes(UUID userId) {
        return jdbc.query("select d.id,d.status,m.service_tier,m.effective_at from meter_devices d "
                + "left join meter_billing_modes m on m.device_id=d.id and m.user_id=d.user_id "
                + "and m.effective_at=(select max(m2.effective_at) from meter_billing_modes m2 "
                + "where m2.device_id=d.id and m2.user_id=d.user_id and m2.effective_at<=?) "
                + "where d.user_id=? order by d.created_at desc", (rs, n) -> new DeviceMode(
                        rs.getObject(1, UUID.class), rs.getString(2),
                        rs.getString(3) == null ? "unknown" : rs.getString(3),
                        rs.getTimestamp(4) == null ? null : rs.getTimestamp(4).toInstant()),
                Timestamp.from(clock.instant()), userId);
    }

    /** Administrator declaration, scoped to this device and only future event timestamps. */
    @Transactional
    public List<DeviceMode> declareMode(UUID userId, UUID deviceId, String tier, UUID administrator) {
        var found = jdbc.query("select id from meter_devices where id=? and user_id=? and status='ACTIVE' for update",
                (rs, n) -> rs.getObject(1, UUID.class), deviceId, userId);
        if (found.isEmpty()) throw new DomainException("BILLING_DEVICE_UNAVAILABLE", "该用户的统计设备不存在或已撤销。", HttpStatus.CONFLICT);
        Timestamp now = Timestamp.from(clock.instant());
        int changed = jdbc.update("update meter_billing_modes set service_tier=?,declared_by=? where device_id=? and effective_at=?",
                tier, administrator, deviceId, now);
        if (changed == 0) jdbc.update("insert into meter_billing_modes(device_id,user_id,effective_at,service_tier,declared_by) values(?,?,?,?,?)",
                deviceId, userId, now, tier, administrator);
        return deviceModes(userId);
    }

    /** Explicit confirmation is bounded and atomic; never rewrites a PRICED row. */
    @Transactional
    public int confirmMode(List<String> eventIds, String tier, UUID administrator) {
        int updated = 0;
        for (String id : eventIds.stream().distinct().sorted().toList()) {
            var rows = jdbc.queryForList("select * from usage_billing_entries where event_id=? for update", id);
            if (rows.isEmpty() || rows.get(0).get("cost_usd") != null
                    || !"UNRECORDED".equals(rows.get(0).get("mode_status"))) {
                throw new DomainException("BILLING_MODE_CONFLICT", "部分记录已入账或已确认模式，请刷新流水后重试。", HttpStatus.CONFLICT);
            }
            var row = rows.get(0);
            var event = events.findById(id).orElseThrow();
            updated += write(event, (UUID) row.get("official_account_id"), (String) row.get("origin"),
                    new Mode(tier, "fast".equals(tier) ? "FAST" : "STANDARD", "ADMIN_CONFIRMED"), true);
            jdbc.update("update usage_billing_entries set mode_confirmed_by=?,mode_confirmed_at=? where event_id=?",
                    administrator, Timestamp.from(clock.instant()), id);
        }
        return updated;
    }

    /** Metadata-only repair: missing/deleted events and billed amounts stay untouched. */
    @Transactional
    public int correctReportedMode(UUID userId, UUID deviceId, String eventId, String tier) {
        var rows=jdbc.queryForList("select b.* from usage_billing_entries b join codex_usage_events e on e.id=b.event_id "
                + "where b.event_id=? and e.user_id=? and (e.meter_device_id=? or e.meter_device_id is null) for update",eventId,userId,deviceId);
        if(rows.isEmpty() || rows.get(0).get("cost_usd")!=null || !"UNRECORDED".equals(rows.get(0).get("mode_status"))) return 0;
        var row=rows.get(0);
        var event=events.findById(eventId).orElseThrow();
        event.setServiceTier(tier);events.saveAndFlush(event);
        return write(event,(UUID)row.get("official_account_id"),(String)row.get("origin"),resolveMode(event),true);
    }

    private Mode resolveMode(CodexUsageEvent event) {
        String tier = event.getServiceTier();
        if ("fast".equals(tier) || "priority".equals(tier)) return new Mode("fast", "FAST", "REPORTED");
        if ("standard".equals(tier) || "default".equals(tier)) return new Mode("standard", "STANDARD", "REPORTED");
        if (event.getMeterDeviceId() != null) {
            var declarations = jdbc.query("select service_tier from meter_billing_modes where device_id=? and user_id=? "
                    + "and effective_at<=? order by effective_at desc limit 1", (rs,n) -> rs.getString(1),
                    event.getMeterDeviceId(), event.getUserId(), Timestamp.from(event.getOccurredAt()));
            if (!declarations.isEmpty() && !"unknown".equals(declarations.get(0))) {
                tier = declarations.get(0);
                return new Mode(tier, "fast".equals(tier) ? "FAST" : "STANDARD", "ADMIN_DECLARED");
            }
        }
        return new Mode(null, "UNRECORDED", "UNRECORDED");
    }

    private int write(CodexUsageEvent event, UUID account, String origin, Mode mode, boolean reconcile) {
        UsageModelPrice price = event.getModel() == null ? null : prices.findById(event.getModel()).orElse(null);
        var estimate = UsagePricingCalculator.estimate(event, price);
        boolean fast = "fast".equals(mode.tier());
        BigDecimal multiplier = fast ? (price == null ? new BigDecimal("1.5") : price.getFastMultiplier()) : BigDecimal.ONE;
        BigDecimal cost = estimate.estimatedCostUsd() == null || mode.tier() == null ? null
                : estimate.estimatedCostUsd().multiply(multiplier).setScale(12, RoundingMode.HALF_UP);
        String status = "PRICED".equals(estimate.costStatus()) && mode.tier() == null ? "MISSING_SERVICE_TIER" : estimate.costStatus();
        Object[] values = { account, cost, status, multiplier, mode.status(), mode.source(),
                price == null ? null : price.getInputPrice(), price == null ? null : price.getOutputPrice(),
                price == null ? null : price.getCacheReadPrice(), price == null ? null : price.getCacheWritePrice(),
                origin, Timestamp.from(clock.instant()), event.getId() };
        if (reconcile) return jdbc.update("update usage_billing_entries set official_account_id=?,cost_usd=?,cost_status=?,multiplier=?,mode_status=?,mode_source=?,"
                + "input_price=?,output_price=?,cache_read_price=?,cache_write_price=?,origin=?,priced_at=? where event_id=? and cost_usd is null", values);
        return jdbc.update("insert into usage_billing_entries(official_account_id,cost_usd,cost_status,multiplier,mode_status,mode_source,input_price,output_price,cache_read_price,cache_write_price,origin,priced_at,event_id) values(?,?,?,?,?,?,?,?,?,?,?,?,?)", values);
    }
}
