package com.pingche.web.quota;

import com.pingche.web.auth.DomainException;
import com.pingche.web.user.User;
import com.pingche.web.user.UserRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class UsageReviewService {
    public record Item(String id, UUID userId, String displayName, String email, UUID deviceId,
            UUID teamId, UUID officialAccountId, Instant occurredAt, Instant receivedAt,
            String reason, String status, Instant reviewedAt, UUID reviewedBy, String reviewNote,
            CodexUsageReportRequest.Event event) { }
    public record Page<T>(List<T> items, int page, int size, long totalElements, long totalPages) { }
    public record PendingBill(String id, UUID userId, String displayName, String email,
            Instant occurredAt, String model, long totalTokens, String costStatus, String modeStatus) { }

    private static final Instant LAST_TIMESTAMP = Instant.parse("9999-12-31T23:59:59Z");
    private final JdbcTemplate jdbc;
    private final UserRepository users;
    private final CodexUsageEventRepository events;
    private final UsageBillingService billing;
    private final Clock clock;
    private final ObjectMapper json;

    public UsageReviewService(JdbcTemplate jdbc, UserRepository users, CodexUsageEventRepository events,
            UsageBillingService billing, Clock clock, ObjectMapper json) {
        this.jdbc = jdbc; this.users = users; this.events = events;
        this.billing = billing; this.clock = clock; this.json = json;
    }

    /** Receipt acknowledges durable quarantine, never acceptance for billing. */
    @Transactional
    public List<String> receive(UUID userId, UUID deviceId, CodexUsageReportRequest request) {
        User user = users.findByIdForUpdate(userId).orElseThrow(this::unavailable);
        if (user.getStatus() != User.Status.ACTIVE || deviceId == null) throw unavailable();
        Instant now = clock.instant();
        List<UUID> linked = user.getTeamId() == null ? List.of() : jdbc.query(
                "select official_account_id from teams where id=?", (rs, n) -> rs.getObject(1, UUID.class), user.getTeamId());
        UUID account = linked.isEmpty() ? null : linked.get(0);
        List<String> received = new ArrayList<>();
        for (var event : request.events()) {
            if (event.occurredAt().isBefore(Instant.EPOCH) || event.occurredAt().isAfter(LAST_TIMESTAMP)) {
                throw new DomainException("USAGE_REVIEW_TIME_INVALID", "用量原始时间无效，记录仍保留在设备中。", HttpStatus.BAD_REQUEST);
            }
            String id = CodexUsageReportService.eventKey(userId, event.eventId());
            if (jdbc.queryForObject("select count(*) from usage_review_queue where id=?", Integer.class, id) == 0) {
                jdbc.update("insert into usage_review_queue(id,user_id,device_id,team_id,official_account_id,occurred_at,received_at,reason,payload,status) values(?,?,?,?,?,?,?,?,?,'PENDING')",
                        id, userId, deviceId, user.getTeamId(), account, Timestamp.from(event.occurredAt()), Timestamp.from(now),
                        reason(event.occurredAt(), now), json.writeValueAsString(event));
            }
            received.add(event.eventId());
        }
        return List.copyOf(received);
    }

    @Transactional(readOnly = true)
    public Page<Item> list(String status, UUID userId, int page, int size) {
        return list(status, userId, page, size, null, null);
    }

    @Transactional(readOnly = true)
    public Page<Item> list(String status, UUID userId, int page, int size, Instant from, Instant to) {
        if (!List.of("PENDING", "ARCHIVED", "IMPORTED", "ALL").contains(status)) throw invalid();
        StringBuilder filter = new StringBuilder(" where (?='ALL' or r.status=?)" + (userId == null ? "" : " and r.user_id=?"));
        var params = new ArrayList<Object>(List.of(status, status));
        if (userId != null) params.add(userId);
        appendRange(filter, params, "r", from, to);
        long total = jdbc.queryForObject("select count(*) from usage_review_queue r" + filter, Long.class, params.toArray());
        params.add(size); params.add((long) page * size);
        var rows = jdbc.query("select r.*,u.display_name,u.email from usage_review_queue r join users u on u.id=r.user_id"
                + filter + " order by r.received_at desc,r.id limit ? offset ?", this::item, params.toArray());
        return new Page<>(rows, page, size, total, (total + size - 1) / size);
    }

    @Transactional(readOnly = true)
    public Page<PendingBill> pendingBilling(UUID userId, int page, int size) {
        return pendingBilling(userId, page, size, null, null);
    }

    @Transactional(readOnly = true)
    public Page<PendingBill> pendingBilling(UUID userId, int page, int size, Instant fromAt, Instant toAt) {
        StringBuilder filter = new StringBuilder(" where b.cost_usd is null" + (userId == null ? "" : " and e.user_id=?"));
        var params = new ArrayList<Object>();
        if (userId != null) params.add(userId);
        appendRange(filter, params, "e", fromAt, toAt);
        String from = " from usage_billing_entries b join codex_usage_events e on e.id=b.event_id join users u on u.id=e.user_id";
        long total = jdbc.queryForObject("select count(*)" + from + filter, Long.class, params.toArray());
        params.add(size); params.add((long) page * size);
        var rows = jdbc.query("select e.id,e.user_id,u.display_name,u.email,e.occurred_at,e.model,e.total_tokens,b.cost_status,b.mode_status"
                + from + filter + " order by e.received_at desc,e.id limit ? offset ?", (rs, n) -> new PendingBill(
                        rs.getString(1), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4), rs.getTimestamp(5).toInstant(),
                        rs.getString(6), rs.getLong(7), rs.getString(8), rs.getString(9)), params.toArray());
        return new Page<>(rows, page, size, total, (total + size - 1) / size);
    }

    /** One bounded atomic action. Concurrent reports share the same user lock. */
    @Transactional
    public int review(List<String> ids, String action, String note, UUID administrator) {
        return review(ids, action, note, administrator, null);
    }

    @Transactional
    public int review(List<String> ids, String action, String note, UUID administrator, UUID jobId) {
        List<String> ordered = ids.stream().distinct().sorted().toList();
        if (ordered.isEmpty() || ordered.size() > 100 || !List.of("ARCHIVE", "IMPORT").contains(action)) throw invalid();
        String placeholders = String.join(",", java.util.Collections.nCopies(ordered.size(), "?"));
        var owners = jdbc.query("select distinct user_id from usage_review_queue where id in (" + placeholders + ") order by user_id",
                (rs, n) -> rs.getObject(1, UUID.class), ordered.toArray());
        for (UUID owner : owners) users.findByIdForUpdate(owner).orElseThrow(this::unavailable);
        Instant now = clock.instant();
        for (String id : ordered) {
            var rows = jdbc.query("select r.*,u.display_name,u.email from usage_review_queue r join users u on u.id=r.user_id where r.id=? for update",
                    this::item, id);
            if (!rows.isEmpty() && jobId != null && ("IMPORT".equals(action) ? "IMPORTED" : "ARCHIVED").equals(rows.get(0).status())
                    && jobId.equals(jdbc.queryForObject("select review_job_id from usage_review_queue where id=?", UUID.class, id))) {
                // A worker may have committed this row just before a restart.
                // Its stable job identity makes resuming safe and countable.
                continue;
            }
            if (rows.isEmpty() || !"PENDING".equals(rows.get(0).status())) {
                throw new DomainException("USAGE_REVIEW_CONFLICT", "部分记录已处理或已移除，请刷新列表后重试。", HttpStatus.CONFLICT);
            }
            Item row = rows.get(0);
            if ("IMPORT".equals(action)) {
                if (row.occurredAt().isAfter(now)) {
                    throw new DomainException("USAGE_REVIEW_FUTURE_TIME", "所选记录包含未来时间，请核对设备时钟后再处理；不能修改原始时间入账。", HttpStatus.CONFLICT);
                }
                User owner = users.findById(row.userId()).orElseThrow(this::unavailable);
                if (owner.getStatus() != User.Status.ACTIVE) throw unavailable();
                if (!events.existsById(id)) {
                    var e = row.event();
                    var stored = new CodexUsageEvent(id, row.userId(), row.teamId(), e.totalTokens(), e.occurredAt(), now,
                            e.source().name(), e.model(), e.reasoningEffort(), e.inputTokens(), e.cachedInputTokens(),
                            e.cacheWriteInputTokens(), e.outputTokens(), e.reasoningOutputTokens());
                    stored.setServiceTier(e.serviceTier()); stored.setMeterDeviceId(row.deviceId());
                    events.saveAndFlush(stored);
                    billing.recordReviewed(stored, row.officialAccountId());
                }
            }
            jdbc.update("update usage_review_queue set status=?,reviewed_at=?,reviewed_by=?,review_note=?,review_job_id=? where id=? and status='PENDING'",
                    "IMPORT".equals(action) ? "IMPORTED" : "ARCHIVED", Timestamp.from(now), administrator, note, jobId, id);
        }
        return ordered.size();
    }

    static void appendRange(StringBuilder sql, List<Object> params, String alias, Instant from, Instant to) {
        if (from != null && to != null && !from.isBefore(to)) {
            throw new DomainException("USAGE_REVIEW_RANGE_INVALID", "开始时间须早于结束时间。", HttpStatus.BAD_REQUEST);
        }
        if (from != null) { sql.append(" and ").append(alias).append(".occurred_at>=?"); params.add(Timestamp.from(from)); }
        if (to != null) { sql.append(" and ").append(alias).append(".occurred_at<?"); params.add(Timestamp.from(to)); }
    }

    private Item item(ResultSet rs, int n) throws SQLException {
        Timestamp reviewed = rs.getTimestamp("reviewed_at");
        return new Item(rs.getString("id"), rs.getObject("user_id", UUID.class), rs.getString("display_name"), rs.getString("email"),
                rs.getObject("device_id", UUID.class), rs.getObject("team_id", UUID.class), rs.getObject("official_account_id", UUID.class),
                rs.getTimestamp("occurred_at").toInstant(), rs.getTimestamp("received_at").toInstant(), rs.getString("reason"), rs.getString("status"),
                reviewed == null ? null : reviewed.toInstant(), rs.getObject("reviewed_by", UUID.class), rs.getString("review_note"),
                json.readValue(rs.getString("payload"), CodexUsageReportRequest.Event.class));
    }
    private String reason(Instant at, Instant now) {
        return at.isBefore(now.minus(Duration.ofDays(30))) ? "TOO_OLD"
                : at.isAfter(now.plus(Duration.ofMinutes(5))) ? "FUTURE_TIME" : "CLIENT_DEFERRED";
    }
    private DomainException unavailable() { return new DomainException("USAGE_REVIEW_UNAVAILABLE", "当前系统账户不可用。", HttpStatus.CONFLICT); }
    private DomainException invalid() { return new DomainException("USAGE_REVIEW_INVALID", "核对请求无效，请刷新后重试。", HttpStatus.BAD_REQUEST); }
}
