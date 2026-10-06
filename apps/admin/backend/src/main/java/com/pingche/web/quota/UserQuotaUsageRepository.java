package com.pingche.web.quota;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserQuotaUsageRepository extends JpaRepository<UserQuotaUsage, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select usage from UserQuotaUsage usage where usage.userId = :userId")
    Optional<UserQuotaUsage> findByUserIdForUpdate(@Param("userId") UUID userId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from UserQuotaUsage usage where usage.userId = :userId")
    int deleteByUserId(@Param("userId") UUID userId);
}
