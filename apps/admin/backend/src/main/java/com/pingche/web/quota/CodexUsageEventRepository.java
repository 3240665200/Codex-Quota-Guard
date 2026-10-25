package com.pingche.web.quota;

import java.time.Instant;
import java.util.List;
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

    @Query("""
            select event.userId as userId,
                   sum(case when event.occurredAt > :sinceFiveHour then event.totalTokens else 0 end)
                       as fiveHourUsedTokens,
                   sum(case when event.occurredAt > :sinceWeekly then event.totalTokens else 0 end)
                       as weeklyUsedTokens,
                   sum(event.totalTokens) as totalUsedTokens,
                   max(event.receivedAt) as measuredAt
            from CodexUsageEvent event
            where event.teamId = :teamId
              and event.userId in (select member.id from User member where member.teamId = :teamId)
              and event.occurredAt > :resetAt
              and event.occurredAt <= :now
              and event.receivedAt > :resetAt
            group by event.userId
            """)
    List<TeamMemberUsage> summarizeTeamMemberUsage(@Param("teamId") UUID teamId,
            @Param("sinceFiveHour") Instant sinceFiveHour,
            @Param("sinceWeekly") Instant sinceWeekly,
            @Param("now") Instant now, @Param("resetAt") Instant resetAt);

    interface TeamMemberUsage {
        UUID getUserId();
        long getFiveHourUsedTokens();
        long getWeeklyUsedTokens();
        long getTotalUsedTokens();
        Instant getMeasuredAt();
    }

    @Query("""
            select event.userId as userId,
                   sum(case when event.occurredAt > :sinceFiveHour then event.totalTokens else 0 end)
                       as fiveHourUsedTokens,
                   sum(event.totalTokens) as weeklyUsedTokens
            from CodexUsageEvent event, User member
            where member.id = event.userId
              and member.teamId = :teamId
              and event.occurredAt > :sinceWeekly
              and event.occurredAt <= :now
              and (member.quotaResetAt is null or event.occurredAt > member.quotaResetAt)
              and event.receivedAt > coalesce(member.quotaResetAt, :epoch)
            group by event.userId
            """)
    List<TeamMemberPersonalUsage> summarizeTeamMemberPersonalUsage(@Param("teamId") UUID teamId,
            @Param("sinceFiveHour") Instant sinceFiveHour,
            @Param("sinceWeekly") Instant sinceWeekly,
            @Param("now") Instant now, @Param("epoch") Instant epoch);

    interface TeamMemberPersonalUsage {
        UUID getUserId();
        long getFiveHourUsedTokens();
        long getWeeklyUsedTokens();
    }

    @Query("select max(event.receivedAt) from CodexUsageEvent event where event.userId = :userId")
    Instant latestReceivedAt(@Param("userId") UUID userId);

    @Query("select max(event.receivedAt) from CodexUsageEvent event where event.teamId = :teamId")
    Instant latestTeamReceivedAt(@Param("teamId") UUID teamId);
}
