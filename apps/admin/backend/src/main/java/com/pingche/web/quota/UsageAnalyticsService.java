package com.pingche.web.quota;

import com.pingche.web.auth.DomainException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Bounded, database-side aggregates; never loads an entire ledger into the browser. */
@Service
public class UsageAnalyticsService {
    public enum Granularity { HOUR, DAY }

    public record Filter(Instant from, Instant to, Granularity granularity, String model,
            String reasoningEffort, UUID userId, UUID teamId, int page, int size) { }
    public record Stats(long eventCount, long totalTokens, Long inputTokens, Long cachedInputTokens,
            Long cacheWriteInputTokens, Long outputTokens, Long reasoningOutputTokens,
            long breakdownEventCount, long costedEventCount, BigDecimal estimatedCostUsd,
            BigDecimal cacheHitRatePercent) { }
    public record Trend(Instant bucket, Stats stats) { }
    public record Group(String key, String label, Stats stats) { }
    public record LedgerItem(String id, UUID userId, String userName, UUID teamId, String teamName,
            Instant occurredAt, Instant receivedAt, long totalTokens, String source, String model,
            String reasoningEffort, Long inputTokens, Long cachedInputTokens, Long cacheWriteInputTokens,
            Long outputTokens, Long reasoningOutputTokens, BigDecimal estimatedCostUsd, String costStatus) { }
    public record LedgerPage(List<LedgerItem> items, int page, int size, long totalElements, int totalPages) { }
    public record Response(Instant from, Instant to, String currency, String timezone, Granularity granularity,
            Stats summary, List<Trend> trend, List<Group> models, List<Group> reasoningEfforts,
            List<Group> users, List<Group> teams, LedgerPage events) { }
    public record PersonalResponse(Instant from, Instant to, String currency, String timezone,
            Granularity granularity, Stats summary, List<Trend> trend) { }
    private record Scope(Instant from, Instant to, String sql, MapSqlParameterSource params) { }
    public record Option(String value, String label) { }
    public record Options(List<Option> models, List<Option> users, List<Option> teams) { }

    private static final String VALID = """
            e.input_tokens is not null and e.output_tokens is not null
            and e.cached_input_tokens is not null and e.cache_write_input_tokens is not null
            and e.input_tokens >= 0 and e.output_tokens >= 0
            and e.cached_input_tokens >= 0 and e.cache_write_input_tokens >= 0
            and e.cached_input_tokens + e.cache_write_input_tokens <= e.input_tokens
            and e.input_tokens + e.output_tokens = e.total_tokens
            and (e.reasoning_output_tokens is null
                 or (e.reasoning_output_tokens >= 0 and e.reasoning_output_tokens <= e.output_tokens))
            """;
    private static final String COST = """
            cast(((e.input_tokens - e.cached_input_tokens - e.cache_write_input_tokens) * p.input_price
                 + e.output_tokens * p.output_price + e.cached_input_tokens * p.cache_read_price
                 + e.cache_write_input_tokens * p.cache_write_price) / 1000000.0 as decimal(30,12))
            """;
    private static final String SCOPE = """
            with scoped as (
                select e.*, u.display_name as user_name, t.name as team_name,
                  case when %s then 1 else 0 end as valid_breakdown,
                  case when (%s) and p.model is not null then %s end as estimated_cost_usd,
                  case when e.input_tokens is null or e.output_tokens is null
                         or e.cached_input_tokens is null or e.cache_write_input_tokens is null
                       then 'MISSING_BREAKDOWN'
                       when not (%s) then 'INVALID_BREAKDOWN'
                       when p.model is null then 'MISSING_PRICE' else 'PRICED' end as cost_status
                from codex_usage_events e
                left join users u on u.id = e.user_id
                left join teams t on t.id = e.team_id
                left join usage_model_prices p on p.model = e.model
                where e.occurred_at >= :from and e.occurred_at < :to %s
            )
            """;
    private static final String AGGREGATES = """
            count(*) as event_count, coalesce(sum(total_tokens),0) as total_tokens,
            sum(input_tokens) as input_tokens, sum(cached_input_tokens) as cached_input_tokens,
            sum(cache_write_input_tokens) as cache_write_input_tokens, sum(output_tokens) as output_tokens,
            sum(reasoning_output_tokens) as reasoning_output_tokens,
            coalesce(sum(valid_breakdown),0) as breakdown_count,
            count(estimated_cost_usd) as costed_count, sum(estimated_cost_usd) as estimated_cost_usd,
            sum(case when valid_breakdown = 1 then cached_input_tokens end) as valid_cached,
            sum(case when valid_breakdown = 1 then input_tokens end) as valid_input
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;

    public UsageAnalyticsService(NamedParameterJdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Response analytics(Filter filter) {
        Scope scope = scope(filter);
        Stats summary = summary(scope);
        List<LedgerItem> items = jdbc.query(scope.sql() + """
                select * from scoped order by occurred_at desc, received_at desc, id desc
                limit :size offset :offset
                """, scope.params(), (row, index) -> ledgerItem(row));
        long total = summary.eventCount();
        return new Response(scope.from(), scope.to(), "USD", "UTC", filter.granularity(), summary,
                trend(scope, filter.granularity()),
                groups(scope.sql(), scope.params(), "model", "model", "未知模型"),
                groups(scope.sql(), scope.params(), "reasoning_effort", "reasoning_effort", "未知等级"),
                groups(scope.sql(), scope.params(), "cast(user_id as varchar(36))", "user_name", "已删除用户"),
                groups(scope.sql(), scope.params(), "cast(team_id as varchar(36))", "team_name", "未分配 / 已删除团队"),
                new LedgerPage(items, filter.page(), filter.size(), total,
                        (int) Math.min(Integer.MAX_VALUE, (total + filter.size() - 1) / filter.size())));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PersonalResponse personalAnalytics(UUID userId, int days) {
        if (userId == null || (days != 1 && days != 7 && days != 30)) {
            throw new DomainException("INVALID_USAGE_RANGE", "个人统计范围须为 1、7 或 30 天。", HttpStatus.BAD_REQUEST);
        }
        Instant to = clock.instant();
        Granularity granularity = days == 1 ? Granularity.HOUR : Granularity.DAY;
        Scope scope = scope(new Filter(to.minus(Duration.ofDays(days)), to, granularity,
                null, null, userId, null, 0, 1));
        return new PersonalResponse(scope.from(), scope.to(), "USD", "UTC", granularity,
                summary(scope), trend(scope, granularity));
    }

    private Scope scope(Filter filter) {
        Instant to = filter.to() == null ? clock.instant().plusSeconds(1) : filter.to();
        Instant from = filter.from() == null ? to.minus(Duration.ofDays(7)) : filter.from();
        if (!from.isBefore(to) || Duration.between(from, to).compareTo(Duration.ofDays(93)) > 0
                || (filter.granularity() == Granularity.HOUR
                    && Duration.between(from, to).compareTo(Duration.ofDays(31)) > 0)) {
            throw new DomainException("INVALID_USAGE_RANGE", "统计范围最多 93 天；小时趋势最多 31 天。", HttpStatus.BAD_REQUEST);
        }
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("from", from.atOffset(ZoneOffset.UTC)).addValue("to", to.atOffset(ZoneOffset.UTC))
                .addValue("size", filter.size()).addValue("offset", (long) filter.page() * filter.size());
        StringBuilder predicates = new StringBuilder();
        addFilter(predicates, params, "model", filter.model());
        addFilter(predicates, params, "reasoning_effort", filter.reasoningEffort());
        addFilter(predicates, params, "user_id", filter.userId());
        addFilter(predicates, params, "team_id", filter.teamId());
        return new Scope(from, to, SCOPE.formatted(VALID, VALID, COST, VALID, predicates), params);
    }

    private Stats summary(Scope scope) {
        return jdbc.queryForObject(scope.sql() + " select " + AGGREGATES + " from scoped", scope.params(),
                (row, index) -> stats(row));
    }

    private List<Trend> trend(Scope scope, Granularity granularity) {
        String day = "cast(occurred_at at time zone 'UTC' as date)";
        String hour = granularity == Granularity.HOUR
                ? "extract(hour from occurred_at at time zone 'UTC')" : "0";
        String groupBy = granularity == Granularity.HOUR ? day + ", " + hour : day;
        return jdbc.query(scope.sql() + " select " + day + " as bucket_day, " + hour
                + " as bucket_hour, " + AGGREGATES + " from scoped group by " + groupBy
                + " order by bucket_day, bucket_hour", scope.params(), (row, index) -> {
                    LocalDate date = row.getObject("bucket_day", LocalDate.class);
                    return new Trend(date.atTime(row.getInt("bucket_hour"), 0).toInstant(ZoneOffset.UTC), stats(row));
                });
    }

    @Transactional(readOnly = true)
    public Options options() {
        var params = new MapSqlParameterSource("since", clock.instant().minus(Duration.ofDays(93)).atOffset(ZoneOffset.UTC));
        List<Option> models = jdbc.query("""
                select distinct model from codex_usage_events
                where model is not null and occurred_at >= :since order by model limit 1000
                """, params, (row, index) -> new Option(row.getString("model"), row.getString("model")));
        List<Option> users = jdbc.query("select id, display_name from users order by display_name, id limit 1000",
                params, (row, index) -> new Option(row.getString("id"), row.getString("display_name")));
        List<Option> teams = jdbc.query("select id, name from teams order by name, id limit 1000",
                params, (row, index) -> new Option(row.getString("id"), row.getString("name")));
        return new Options(models, users, teams);
    }

    private void addFilter(StringBuilder sql, MapSqlParameterSource params, String column, Object value) {
        if (value != null && !(value instanceof String text && text.isBlank())) {
            // column comes only from the fixed allowlist above; values are always bound.
            sql.append(" and e.").append(column).append(" = :").append(column);
            params.addValue(column, value);
        }
    }

    private List<Group> groups(String scope, MapSqlParameterSource params, String key, String label, String fallback) {
        return jdbc.query(scope + " select " + key + " as group_key, " + label + " as group_label, "
                + AGGREGATES + " from scoped group by " + key + ", " + label
                + " order by total_tokens desc, group_key limit 20", params, (row, index) -> {
                    String text = row.getString("group_label");
                    return new Group(row.getString("group_key"), text == null ? fallback : text, stats(row));
                });
    }

    private Stats stats(ResultSet row) throws SQLException {
        BigDecimal input = row.getBigDecimal("valid_input");
        BigDecimal cached = row.getBigDecimal("valid_cached");
        BigDecimal hitRate = input == null || input.signum() == 0 ? null
                : cached.multiply(BigDecimal.valueOf(100)).divide(input, 2, RoundingMode.HALF_UP);
        return new Stats(row.getLong("event_count"), row.getLong("total_tokens"),
                nullableLong(row, "input_tokens"), nullableLong(row, "cached_input_tokens"),
                nullableLong(row, "cache_write_input_tokens"), nullableLong(row, "output_tokens"),
                nullableLong(row, "reasoning_output_tokens"), row.getLong("breakdown_count"),
                row.getLong("costed_count"), row.getBigDecimal("estimated_cost_usd"), hitRate);
    }

    private Long nullableLong(ResultSet row, String name) throws SQLException {
        long value = row.getLong(name);
        return row.wasNull() ? null : value;
    }

    private LedgerItem ledgerItem(ResultSet row) throws SQLException {
        return new LedgerItem(row.getString("id"), row.getObject("user_id", UUID.class), row.getString("user_name"),
                row.getObject("team_id", UUID.class), row.getString("team_name"),
                row.getTimestamp("occurred_at").toInstant(), row.getTimestamp("received_at").toInstant(),
                row.getLong("total_tokens"), row.getString("source"), row.getString("model"),
                row.getString("reasoning_effort"), nullableLong(row, "input_tokens"),
                nullableLong(row, "cached_input_tokens"), nullableLong(row, "cache_write_input_tokens"),
                nullableLong(row, "output_tokens"), nullableLong(row, "reasoning_output_tokens"),
                row.getBigDecimal("estimated_cost_usd"), row.getString("cost_status"));
    }
}
