package com.pingche.web.quota;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.when;
import com.pingche.web.user.User;
import com.pingche.web.user.UserRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:usage-review-bulk;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
class UsageReviewBulkIntegrationTest {
    static final Instant NOW = Instant.parse("2026-10-26T14:00:00Z");
    @Autowired UsageReviewBulkService jobs;
    @Autowired UsageReviewService reviews;
    @Autowired CodexUsageEventRepository events;
    @Autowired UsageModelPriceRepository prices;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean Clock clock;
    User admin, member;
    UUID device;
    @BeforeEach void setup() {
        when(clock.instant()).thenReturn(NOW);
        jdbc.update("delete from usage_review_bulk_items"); jdbc.update("delete from usage_review_bulk_jobs");
        events.deleteAll(); users.deleteAll();
        admin = users.saveAndFlush(User.bootstrapAdministrator("bulk-admin@example.com", "fixture-hash", "Bulk admin"));
        member = users.saveAndFlush(new User("bulk-member@example.com", "fixture-hash", "Bulk member"));
        device = UUID.randomUUID();
        prices.saveAndFlush(new UsageModelPrice("bulk-fixture", new BigDecimal("2"), new BigDecimal("10"),
                new BigDecimal("0.2"), new BigDecimal("3"), NOW));
    }
    private CodexUsageReportRequest.Event event(int n, Instant at) {
        return new CodexUsageReportRequest.Event("codex-local-usage:v1:" + String.format("%064x", n), at, 1500,
                CodexUsageReportRequest.Source.TOKEN_USAGE_RECORD, "bulk-fixture", "max", 1000L, 200L, 100L, 500L, 200L, "fast");
    }
    private void receive(int count, Instant at) {
        for (int offset = 0; offset < count; offset += 100) {
            var batch = new ArrayList<CodexUsageReportRequest.Event>();
            for (int n = offset; n < Math.min(count, offset + 100); n++) batch.add(event(n + 1, at));
            reviews.receive(member.getId(), device, new CodexUsageReportRequest(batch));
        }
    }
    @Test void aSingleConfirmationProcessesAllPagesWithFrozenTargetsAndDurableProgress() {
        receive(125, NOW.minusSeconds(60));
        var job = jobs.preview(admin.getId(), "ARCHIVE", member.getId(), null, null);
        assertThat(job.totalCount()).isEqualTo(125);
        assertThat(job.status()).isEqualTo("DRAFT");
        assertThat(reviews.list("PENDING", null, 0, 20).totalElements()).isEqualTo(125);
        reviews.receive(member.getId(), device, new CodexUsageReportRequest(List.of(event(999, NOW))));
        jobs.start(job.id(), admin.getId(), "批量归档历史记录");
        assertThat(jobs.processNextBatch()).isEqualTo(50);
        assertThat(jobs.get(job.id()).processedCount()).isEqualTo(50);
        assertThat(jobs.processNextBatch()).isEqualTo(50);
        assertThat(jobs.processNextBatch()).isEqualTo(25);
        assertThat(jobs.get(job.id()).status()).isEqualTo("COMPLETED");
        assertThat(jobs.get(job.id()).succeededCount()).isEqualTo(125);
        assertThat(reviews.list("PENDING", null, 0, 20).totalElements()).isEqualTo(1);
        assertThat(events.count()).isZero();
        jobs.start(job.id(), admin.getId(), "重复提交");
        assertThat(jobs.processNextBatch()).isZero();
    }
    @Test void dateSelectionIsInclusiveAtStartExclusiveAtEndAndScopedToTheUser() {
        Instant from = NOW.minusSeconds(7200), to = NOW.minusSeconds(3600);
        reviews.receive(member.getId(), device, new CodexUsageReportRequest(List.of(event(1, from.minusSeconds(1)), event(2, from), event(3, to))));
        reviews.receive(admin.getId(), UUID.randomUUID(), new CodexUsageReportRequest(List.of(event(4, from))));
        assertThat(reviews.list("PENDING", member.getId(), 0, 20, from, to).totalElements()).isEqualTo(1);
        var job = jobs.preview(admin.getId(), "IMPORT", member.getId(), from, to);
        assertThat(job.totalCount()).isEqualTo(1);
        jobs.start(job.id(), admin.getId(), "按时间范围入账"); jobs.processNextBatch();
        assertThat(events.findAll()).singleElement().satisfies(e -> {
            assertThat(e.getOccurredAt()).isEqualTo(from); assertThat(e.getUserId()).isEqualTo(member.getId());
        });
        assertThatThrownBy(() -> jobs.preview(admin.getId(), "ARCHIVE", null, to, from)).hasMessageContaining("开始时间");
    }
    @Test void futureRecordsAreExcludedAndCommittedRowsAreRecognizedAfterAWorkerRestart() {
        reviews.receive(member.getId(), device, new CodexUsageReportRequest(List.of(event(1, NOW.minusSeconds(10)), event(2, NOW.plusSeconds(3600)))));
        var job = jobs.preview(admin.getId(), "IMPORT", null, null, null);
        assertThat(job.totalCount()).isEqualTo(1); assertThat(job.excludedCount()).isEqualTo(1);
        jobs.start(job.id(), admin.getId(), "原始日志核对完成");
        String id = CodexUsageReportService.eventKey(member.getId(), event(1, NOW).eventId());
        reviews.review(List.of(id), "IMPORT", "原始日志核对完成", admin.getId(), job.id());
        jobs.processNextBatch();
        assertThat(events.count()).isEqualTo(1);
        assertThat(jobs.get(job.id()).succeededCount()).isEqualTo(1);
        assertThat(jobs.get(job.id()).failedCount()).isZero();
        assertThat(reviews.list("PENDING", null, 0, 20).totalElements()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select cost_usd from usage_billing_entries", BigDecimal.class)).isEqualByComparingTo("0.01011");
    }
    @Test void conflictsAreSkippedAndRevokedAdministratorsCannotRunQueuedJobs() {
        receive(2, NOW);
        var job = jobs.preview(admin.getId(), "ARCHIVE", member.getId(), null, null);
        assertThatThrownBy(() -> jobs.start(job.id(), member.getId(), "不允许代启动")).hasMessageContaining("仅任务创建者");
        String id = CodexUsageReportService.eventKey(member.getId(), event(1, NOW).eventId());
        reviews.review(List.of(id), "ARCHIVE", "先前人工处理", admin.getId());
        jobs.start(job.id(), admin.getId(), "批量归档"); jobs.processNextBatch();
        assertThat(jobs.get(job.id()).skippedCount()).isEqualTo(1);
        assertThat(jobs.get(job.id()).succeededCount()).isEqualTo(1);
        reviews.receive(member.getId(), device, new CodexUsageReportRequest(List.of(event(3, NOW))));
        var next = jobs.preview(admin.getId(), "IMPORT", member.getId(), null, null);
        jobs.start(next.id(), admin.getId(), "入账");
        admin.updateAdministration(User.Status.DISABLED, null); users.saveAndFlush(admin);
        jobs.processNextBatch();
        assertThat(jobs.get(next.id()).status()).isEqualTo("FAILED"); assertThat(events.count()).isZero();
    }
}
