package com.pingche.web.quota;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.pingche.web.auth.DomainException;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Authoritative monetary budgets. Token counters are retained only as measurements. */
@Service
public class UsdQuotaService {
    public record Window(@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal limitUsd,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal usedUsd,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal remainingUsd,
            long pendingEvents, long modeUnrecordedEvents, long historicalEvents) { }
    public record Scope(String currency, Window fiveHour, Window weekly, Window total) { }
    public record Account(UUID id, String label, Scope quota) { }
    public record Member(UUID userId, String displayName, String status, Scope personal, Scope contribution) { }
    public record Overview(String currency, Scope personal, Scope team, Account officialAccount,
            List<Member> teamMembers, boolean allowed, String blockReason) { }
    private record Owner(UUID id, String label, String status, UUID team, UUID account,
            BigDecimal five, BigDecimal weekly, BigDecimal total, Instant reset) { }
    private final JdbcTemplate jdbc;
    private final Clock clock;
    public UsdQuotaService(JdbcTemplate jdbc, Clock clock) { this.jdbc = jdbc; this.clock = clock; }

    @Transactional(readOnly = true)
    public Overview overview(UUID userId, boolean includeMembers) {
        Owner user = user(userId);
        Scope personal = personal(user);
        Owner teamOwner = user.team == null ? null : teamOwner(user.team);
        Scope team = teamOwner == null ? null : team(teamOwner);
        Account official = teamOwner == null || teamOwner.account == null ? null : officialAccount(teamOwner.account);
        String reason = !"ACTIVE".equals(user.status) ? "USER_UNAVAILABLE"
                : teamOwner != null && !"ACTIVE".equals(teamOwner.status) ? "TEAM_UNAVAILABLE"
                : exhausted(personal.fiveHour) ? "FIVE_HOUR_USD_QUOTA_EXCEEDED"
                : exhausted(personal.weekly) ? "WEEKLY_USD_QUOTA_EXCEEDED"
                : team != null && exhausted(team.fiveHour) ? "TEAM_FIVE_HOUR_USD_QUOTA_EXCEEDED"
                : team != null && exhausted(team.weekly) ? "TEAM_WEEKLY_USD_QUOTA_EXCEEDED"
                : team != null && exhausted(team.total) ? "TEAM_TOTAL_USD_QUOTA_EXCEEDED"
                : official != null && exhausted(official.quota.total) ? "OFFICIAL_ACCOUNT_USD_QUOTA_EXCEEDED"
                : incomplete(personal) || team != null && incomplete(team) || official != null && incomplete(official.quota) ? "BILLING_PENDING" : null;
        List<Member> members = includeMembers && teamOwner != null ? members(teamOwner) : List.of();
        return new Overview("USD", personal, team, official, members, reason == null, reason);
    }

    @Transactional(readOnly = true)
    public Scope personal(UUID userId) { return personal(user(userId)); }
    @Transactional(readOnly = true)
    public Scope team(UUID teamId) { return team(teamOwner(teamId)); }

    @Transactional(readOnly = true)
    public Account officialAccount(UUID id) {
        var rows = jdbc.query("select id,label,budget_usd,billing_reset_at from official_accounts where id=?", (rs,n) ->
                new Owner(rs.getObject(1, UUID.class), rs.getString(2), "ACTIVE", null, null, null, null, rs.getBigDecimal(3), instant(rs,4)), id);
        if (rows.isEmpty()) throw missing("OFFICIAL_ACCOUNT_NOT_FOUND");
        Owner account = rows.get(0);
        return new Account(id, account.label, scope("b.official_account_id=?", List.of(id), account.reset, null, null, account.total));
    }

    public boolean personalExhausted(UUID id) {
        Scope quota = personal(id);
        return exhausted(quota.fiveHour) || exhausted(quota.weekly);
    }

    private Scope personal(Owner user) { return scope("e.user_id=?", List.of(user.id), user.reset, user.five, user.weekly, null); }
    private Scope team(Owner team) { return scope("e.team_id=?", List.of(team.id), team.reset, team.five, team.weekly, team.total); }
    private List<Member> members(Owner team) {
        List<Owner> users = jdbc.query("select id,display_name,status,team_id,five_hour_usd_limit,weekly_usd_limit,quota_reset_at from users where team_id=? order by display_name,id",
                (rs,n) -> userRow(rs), team.id);
        return users.stream().map(user -> new Member(user.id, user.label, user.status, personal(user),
                scope("e.user_id=? and e.team_id=?", List.of(user.id, team.id), team.reset, null, null, null))).toList();
    }
    private Owner user(UUID id) {
        var rows = jdbc.query("select id,display_name,status,team_id,five_hour_usd_limit,weekly_usd_limit,quota_reset_at from users where id=?", (rs,n) -> userRow(rs), id);
        if (rows.isEmpty()) throw missing("USER_NOT_FOUND");
        return rows.get(0);
    }
    private Owner userRow(ResultSet rs) throws SQLException {
        return new Owner(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getObject(4, UUID.class), null,
                rs.getBigDecimal(5), rs.getBigDecimal(6), null, instant(rs,7));
    }
    private Owner teamOwner(UUID id) {
        var rows = jdbc.query("select id,name,status,official_account_id,five_hour_usd_limit,weekly_usd_limit,total_usd_limit,quota_reset_at from teams where id=?", (rs,n) ->
                new Owner(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), null, rs.getObject(4, UUID.class),
                        rs.getBigDecimal(5), rs.getBigDecimal(6), rs.getBigDecimal(7), instant(rs,8)), id);
        if (rows.isEmpty()) throw missing("TEAM_NOT_FOUND");
        return rows.get(0);
    }
    private Scope scope(String selector, List<Object> selectorArgs, Instant reset,
            BigDecimal fiveLimit, BigDecimal weeklyLimit, BigDecimal totalLimit) {
        Instant now = clock.instant(), floor = reset == null ? Instant.EPOCH : reset;
        Instant five = now.minusSeconds(5 * 3600L), weekly = now.minusSeconds(7 * 86400L);
        if (floor.isAfter(five)) five = floor;
        if (floor.isAfter(weekly)) weekly = floor;
        List<Object> args = new ArrayList<>();
        StringBuilder select = new StringBuilder("select ");
        int index = 0;
        for (Instant since : List.of(five, weekly, floor)) {
            if (index++ > 0) select.append(",");
            select.append("coalesce(sum(case when e.occurred_at>? then b.cost_usd else 0 end),0),")
                    .append("coalesce(sum(case when e.occurred_at>? and (b.event_id is null or b.cost_status<>'PRICED') then 1 else 0 end),0),")
                    .append("coalesce(sum(case when e.occurred_at>? and b.mode_status='UNRECORDED' then 1 else 0 end),0),")
                    .append("coalesce(sum(case when e.occurred_at>? and b.origin='HISTORICAL_RECALC' then 1 else 0 end),0)");
            for (int i = 0; i < 4; i++) args.add(Timestamp.from(since));
        }
        select.append(" from codex_usage_events e left join usage_billing_entries b on b.event_id=e.id where ")
                .append(selector).append(" and e.occurred_at>? and e.occurred_at<=? and e.received_at>?");
        args.addAll(selectorArgs); args.add(Timestamp.from(floor)); args.add(Timestamp.from(now)); args.add(Timestamp.from(floor));
        return jdbc.queryForObject(select.toString(), (rs,n) -> new Scope("USD", window(rs,1,fiveLimit),
                window(rs,5,weeklyLimit), window(rs,9,totalLimit)), args.toArray());
    }
    private Window window(ResultSet rs, int offset, BigDecimal limit) throws SQLException {
        BigDecimal used = rs.getBigDecimal(offset);
        long pending = rs.getLong(offset+1);
        return new Window(limit, used, limit == null || pending > 0 && used.compareTo(limit) < 0 ? null : limit.subtract(used).max(BigDecimal.ZERO),
                rs.getLong(offset+1), rs.getLong(offset+2), rs.getLong(offset+3));
    }
    private static boolean exhausted(Window value) { return value.limitUsd != null && value.usedUsd.compareTo(value.limitUsd) >= 0; }
    private static boolean incomplete(Scope value) { return java.util.stream.Stream.of(value.fiveHour,value.weekly,value.total).anyMatch(window -> window.limitUsd != null && window.pendingEvents > 0); }
    private static Instant instant(ResultSet rs, int column) throws SQLException { var value = rs.getTimestamp(column); return value == null ? null : value.toInstant(); }
    private static DomainException missing(String code) { return new DomainException(code, "额度所属用户、团队或官方账户不存在。", HttpStatus.NOT_FOUND); }
}
