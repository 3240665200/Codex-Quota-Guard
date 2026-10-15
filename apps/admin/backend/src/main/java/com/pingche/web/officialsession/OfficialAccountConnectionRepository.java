package com.pingche.web.officialsession;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OfficialAccountConnectionRepository extends JpaRepository<OfficialAccountConnection, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select connection from OfficialAccountConnection connection where connection.officialAccountId = :id")
    Optional<OfficialAccountConnection> findByIdForUpdate(@Param("id") UUID id);
}
