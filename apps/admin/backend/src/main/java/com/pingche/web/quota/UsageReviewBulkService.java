package com.pingche.web.quota;

import com.pingche.web.auth.DomainException;
import com.pingche.web.user.User;
import com.pingche.web.user.UserRepository;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class UsageReviewBulkService {
    public record Job(UUID id, UUID requestedBy, String action, String status, UUID userId,
            Instant from, Instant to, Instant createdAt, int totalCount, int excludedCount,
            int processedCount, int succeededCount, int skippedCount, int failedCount, String lastErrorCode) { }
    private static final int MAX_TARGETS = 100_000;
    private final JdbcTemplate jdbc;
    private final UserRepository users;
    private final UsageReviewService reviews;
    private final Clock clock;
    private final TransactionTemplate tx;
    private final TransactionTemplate rowTx;

    public UsageReviewBulkService(JdbcTemplate jdbc, UserRepository users, UsageReviewService reviews,
            Clock clock, PlatformTransactionManager manager) {
        this.jdbc = jdbc; this.users = users; this.reviews = reviews; this.clock = clock;
        tx = new TransactionTemplate(manager);
        rowTx = new TransactionTemplate(manager);
        rowTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Transactional
    public Job preview(UUID actor, String action, UUID userId, Instant from, Instant to) {
        var administrator = users.findByIdForUpdate(actor).orElseThrow(this::forbidden);
        assertAdministrator(administrator);
        if (!List.of("ARCHIVE", "IMPORT").contains(action)) throw invalid();
        Instant now = clock.instant();
        var args = new ArrayList<Object>();
        var where = new StringBuilder(" where r.status='PENDING'");
        if (userId != null) { where.append(" and r.user_id=?"); args.add(userId); }
        UsageReviewService.appendRange(where, args, "r", from, to);
        long matches = jdbc.queryForObject("select count(*) from usage_review_queue r" + where, Long.class, args.toArray());
        if (matches > MAX_TARGETS) throw new DomainException("USAGE_REVIEW_TOO_MANY", "一次最多处理 100,000 条，请按时间缩小范围。", HttpStatus.BAD_REQUEST);
        if ("IMPORT".equals(action)) { where.append(" and r.occurred_at<=?"); args.add(Timestamp.from(now)); }
        int eligible = jdbc.queryForObject("select count(*) from usage_review_queue r" + where, Integer.class, args.toArray());
        if (eligible == 0) throw new DomainException("USAGE_REVIEW_EMPTY", "当前范围没有可处理的待核对记录；未来时间记录不能入账。", HttpStatus.CONFLICT);
        UUID id = UUID.randomUUID();
        jdbc.update("insert into usage_review_bulk_jobs(id,requested_by,action,status,user_id,from_at,to_at,created_at,total_count,excluded_count) values(?,?,?,'DRAFT',?,?,?,?,?,?)",
                id, actor, action, userId, timestamp(from), timestamp(to), Timestamp.from(now), eligible, (int) matches - eligible);
        args.add(0, id);
        jdbc.update("insert into usage_review_bulk_items(job_id,review_id,status) select ?,r.id,'PENDING' from usage_review_queue r" + where + " limit " + (MAX_TARGETS + 1), args.toArray());
        // The INSERT, not a potentially racing preview count, defines the exact
        // immutable target set shown in the confirmation dialog.
        jdbc.update("update usage_review_bulk_jobs set total_count=(select count(*) from usage_review_bulk_items where job_id=?) where id=?", id, id);
        Job job = get(id);
        if (job.totalCount() > MAX_TARGETS) throw new DomainException("USAGE_REVIEW_TOO_MANY", "一次最多处理 100,000 条，请按时间缩小范围。", HttpStatus.BAD_REQUEST);
        if (job.totalCount() == 0) throw new DomainException("USAGE_REVIEW_EMPTY", "匹配记录已由其他操作处理，请刷新列表。", HttpStatus.CONFLICT);
        return job;
    }

    @Transactional
    public Job start(UUID id, UUID actor, String note) {
        var rows = jdbc.query("select * from usage_review_bulk_jobs where id=? for update", this::job, id);
        if (rows.isEmpty()) throw missing();
        Job job = rows.get(0);
        if (!job.requestedBy().equals(actor)) throw forbidden();
        assertAdministrator(users.findById(actor).orElseThrow(this::forbidden));
        if ("DRAFT".equals(job.status())) {
            if (note == null || note.isBlank() || note.length() > 500) throw invalid();
            long active = jdbc.queryForObject("select count(*) from usage_review_bulk_jobs where status in ('QUEUED','RUNNING')", Long.class);
            if (active >= 8) throw new DomainException("USAGE_REVIEW_BUSY", "批量任务较多，请等待当前任务完成后重试。", HttpStatus.CONFLICT);
            jdbc.update("update usage_review_bulk_jobs set status='QUEUED',note=? where id=?", note, id);
        }
        return get(id);
    }

    @Transactional(readOnly = true)
    public List<Job> recent() {
        return jdbc.query("select * from usage_review_bulk_jobs where status<>'DRAFT' order by created_at desc,id desc limit 10", this::job);
    }

    @Transactional(readOnly = true)
    public Job get(UUID id) {
        var rows = jdbc.query("select * from usage_review_bulk_jobs where id=?", this::job, id);
        if (rows.isEmpty()) throw missing();
        return rows.get(0);
    }

    /** Durable cursor + independent row transactions keep a large job resumable.
     * A database failure rolls back progress; the stable job ID then recognizes
     * rows already committed before the interruption, without charging twice. */
    public int processNextBatch() {
        Integer processed = tx.execute(ignored -> {
            var ids = jdbc.query("select id from usage_review_bulk_jobs where status in ('QUEUED','RUNNING') order by created_at,id limit 1",
                    (rs, n) -> rs.getObject(1, UUID.class));
            if (ids.isEmpty()) return 0;
            UUID id = ids.get(0);
            Job job = jdbc.query("select * from usage_review_bulk_jobs where id=? for update", this::job, id).get(0);
            if (!List.of("QUEUED", "RUNNING").contains(job.status())) return 0;
            var actor = users.findById(job.requestedBy());
            if (actor.isEmpty() || actor.get().getStatus() != User.Status.ACTIVE || actor.get().getRole() != User.Role.ADMIN) {
                jdbc.update("update usage_review_bulk_jobs set status='FAILED',last_error_code='ADMIN_UNAVAILABLE',finished_at=? where id=?", Timestamp.from(clock.instant()), id);
                return 0;
            }
            String note = jdbc.queryForObject("select note from usage_review_bulk_jobs where id=?", String.class, id);
            jdbc.update("update usage_review_bulk_jobs set status='RUNNING',started_at=coalesce(started_at,?) where id=?", Timestamp.from(clock.instant()), id);
            var targets = jdbc.query("select review_id from usage_review_bulk_items where job_id=? and status='PENDING' order by review_id limit 50", (rs, n) -> rs.getString(1), id);
            int succeeded = 0, skipped = 0, failed = 0;
            String lastError = null;
            for (String target : targets) {
                String outcome = "SUCCEEDED", error = null;
                try {
                    rowTx.execute(status -> reviews.review(List.of(target), job.action(), note, job.requestedBy(), job.id()));
                    succeeded++;
                } catch (DomainException exception) {
                    error = exception.getCode();
                    if ("USAGE_REVIEW_CONFLICT".equals(error)) { outcome = "SKIPPED"; skipped++; }
                    else { outcome = "FAILED"; failed++; lastError = error; }
                }
                jdbc.update("update usage_review_bulk_items set status=?,error_code=? where job_id=? and review_id=?", outcome, error, id, target);
            }
            int done = job.processedCount() + targets.size();
            int errors = job.failedCount() + failed;
            String state = done >= job.totalCount() ? errors > 0 ? "COMPLETED_WITH_ERRORS" : "COMPLETED" : "RUNNING";
            jdbc.update("update usage_review_bulk_jobs set status=?,processed_count=?,succeeded_count=?,skipped_count=?,failed_count=?,last_error_code=coalesce(?,last_error_code),finished_at=? where id=?",
                    state, done, job.succeededCount() + succeeded, job.skippedCount() + skipped, errors, lastError,
                    done >= job.totalCount() ? Timestamp.from(clock.instant()) : null, id);
            return targets.size();
        });
        return processed == null ? 0 : processed;
    }

    private Job job(ResultSet rs, int n) throws SQLException {
        Timestamp from = rs.getTimestamp("from_at"), to = rs.getTimestamp("to_at");
        return new Job(rs.getObject("id", UUID.class), rs.getObject("requested_by", UUID.class), rs.getString("action"), rs.getString("status"),
                rs.getObject("user_id", UUID.class), from == null ? null : from.toInstant(), to == null ? null : to.toInstant(), rs.getTimestamp("created_at").toInstant(),
                rs.getInt("total_count"), rs.getInt("excluded_count"), rs.getInt("processed_count"), rs.getInt("succeeded_count"), rs.getInt("skipped_count"), rs.getInt("failed_count"), rs.getString("last_error_code"));
    }
    private void assertAdministrator(User user) { if (user.getRole() != User.Role.ADMIN || user.getStatus() != User.Status.ACTIVE) throw forbidden(); }
    private static Timestamp timestamp(Instant at) { return at == null ? null : Timestamp.from(at); }
    private DomainException forbidden() { return new DomainException("USAGE_REVIEW_FORBIDDEN", "仅任务创建者管理员可以启动该批量操作。", HttpStatus.FORBIDDEN); }
    private DomainException missing() { return new DomainException("USAGE_REVIEW_JOB_MISSING", "批量核对任务不存在。", HttpStatus.NOT_FOUND); }
    private DomainException invalid() { return new DomainException("USAGE_REVIEW_INVALID", "批量核对请求无效。", HttpStatus.BAD_REQUEST); }
}
