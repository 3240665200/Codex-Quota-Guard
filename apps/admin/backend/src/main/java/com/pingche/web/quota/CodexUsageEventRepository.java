package com.pingche.web.quota;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CodexUsageEventRepository extends JpaRepository<CodexUsageEvent, String> {

    @Query("""
            select coalesce(sum(event.totalTokens), 0)
            from CodexUsageEvent event
            where event.userId = :userId
              and event.receivedAt > :since
            """)
    long sumTokensSince(@Param("userId") UUID userId,
            @Param("since") Instant since);

    @Query("select max(event.receivedAt) from CodexUsageEvent event where event.userId = :userId")
    Instant latestReceivedAt(@Param("userId") UUID userId);
}
