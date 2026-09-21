package com.codexquotaguard.backend.quota;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QuotaSnapshotFactoryTest {

    private static final Instant CAPTURED_AT = Instant.parse("2026-09-08T00:00:00Z");

    @Test
    void preservesEveryServerBucketInStableOrderWithoutPersonalAccountData() throws Exception {
        var snapshot = QuotaSnapshotFactory.create(
                "room_design",
                "member_alice",
                "Alice",
                new AccountState(true, "plus"),
                Map.of(
                        "codex_other", new RawRateLimitBucket(
                                "codex_other",
                                "Fast model",
                                new RawRateLimitWindow(44.5, 60L, CAPTURED_AT.plusSeconds(1_800).getEpochSecond()),
                                null,
                                "",
                                false
                        ),
                        "codex", new RawRateLimitBucket(
                                "codex",
                                "",
                                new RawRateLimitWindow(23.0, 300L, CAPTURED_AT.plusSeconds(7_200).getEpochSecond()),
                                new RawRateLimitWindow(33.0, 10_080L, CAPTURED_AT.plusSeconds(345_600).getEpochSecond()),
                                "plus",
                                false
                        )
                ),
                CAPTURED_AT
        );

        assertThat(snapshot.buckets()).extracting(RateLimitBucket::limitId)
                .containsExactly("codex", "codex_other");
        assertThat(snapshot.buckets().get(0).primary())
                .isEqualTo(new RateLimitWindow(23.0, 77.0, 300, CAPTURED_AT.plusSeconds(7_200)));
        assertThat(snapshot.buckets().get(0).secondary())
                .isEqualTo(new RateLimitWindow(33.0, 67.0, 10_080, CAPTURED_AT.plusSeconds(345_600)));
        assertThat(snapshot.authenticated()).isTrue();
        assertThat(snapshot.planType()).isEqualTo("plus");

        assertThat(snapshot.toString().toLowerCase()).doesNotContain("email", "access_token", "refresh_token");
    }

    @Test
    void rejectsInvalidUsagePercentagesAndResetTimes() {
        assertThatThrownBy(() -> QuotaSnapshotFactory.create(
                "room_design",
                "member_alice",
                "Alice",
                new AccountState(true, "plus"),
                Map.of("codex", new RawRateLimitBucket(
                        "codex", "", new RawRateLimitWindow(100.1, 300L, CAPTURED_AT.plusSeconds(7_200).getEpochSecond()), null, "plus", false
                )),
                CAPTURED_AT
        )).isInstanceOf(QuotaSnapshotException.class);

        assertThatThrownBy(() -> QuotaSnapshotFactory.create(
                "room_design",
                "member_alice",
                "Alice",
                new AccountState(true, "plus"),
                Map.of("codex", new RawRateLimitBucket(
                        "codex", "", new RawRateLimitWindow(50.0, 300L, 0L), null, "plus", false
                )),
                CAPTURED_AT
        )).isInstanceOf(QuotaSnapshotException.class);
    }
}
