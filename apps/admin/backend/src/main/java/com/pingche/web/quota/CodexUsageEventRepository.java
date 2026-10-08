package com.pingche.web.quota;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CodexUsageEventRepository extends JpaRepository<CodexUsageEvent, String> {

    Page<CodexUsageEvent> findByUserIdOrderByReceivedAtDescIdDesc(UUID userId, Pageable pageable);

    @Query("""
            select coalesce(sum(event.totalTokens), 0)
            from CodexUsageEvent event
            where event.userId = :userId
              and event.occurredAt > :since
              and event.occurredAt <= :now
              and event.receivedAt > :resetAt
            """)
    long sumTokensSince(@Param("userId") UUID userId,
            @Param("since") Instant since, @Param("now") Instant now,
            @Param("resetAt") Instant resetAt);

    @Query("""
            select coalesce(sum(event.totalTokens), 0)
            from CodexUsageEvent event
            where event.teamId = :teamId
              and event.occurredAt > :since
              and event.occurredAt <= :now
              and event.receivedAt > :resetAt
            """)
    long sumTeamTokensSince(@Param("teamId") UUID teamId,
            @Param("since") Instant since, @Param("now") Instant now,
            @Param("resetAt") Instant resetAt);

    @Query("select max(event.receivedAt) from CodexUsageEvent event where event.userId = :userId")
    Instant latestReceivedAt(@Param("userId") UUID userId);

    @Query("select max(event.receivedAt) from CodexUsageEvent event where event.teamId = :teamId")
    Instant latestTeamReceivedAt(@Param("teamId") UUID teamId);
}
