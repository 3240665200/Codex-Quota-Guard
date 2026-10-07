package com.pingche.web.meter;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MeterDeviceRepository extends JpaRepository<MeterDevice, UUID> {
    Optional<MeterDevice> findByTokenHash(String tokenHash);
    List<MeterDevice> findByUserIdOrderByCreatedAtDesc(UUID userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select device from MeterDevice device where device.id = :id")
    Optional<MeterDevice> findByIdForUpdate(@Param("id") UUID id);
}
