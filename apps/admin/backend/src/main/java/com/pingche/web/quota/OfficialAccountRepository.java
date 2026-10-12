package com.pingche.web.quota;

import java.util.List;
import java.util.UUID;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OfficialAccountRepository extends JpaRepository<OfficialAccount, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select account from OfficialAccount account where account.id = :id")
    Optional<OfficialAccount> findByIdForUpdate(@Param("id") UUID id);
    List<OfficialAccount> findAllByOrderByLabelAscIdAsc();
    boolean existsByLabel(String label);
    boolean existsByLabelAndIdNot(String label, UUID id);
    boolean existsByReporterUserId(UUID reporterUserId);
    boolean existsByReporterUserIdAndIdNot(UUID reporterUserId, UUID id);
}
