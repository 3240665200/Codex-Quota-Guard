package com.pingche.web.mail;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MailListenerRepository extends JpaRepository<MailListener, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select listener from MailListener listener where listener.id = :id")
    Optional<MailListener> findByIdForUpdate(@Param("id") UUID id);
    Optional<MailListener> findByActiveAccountId(UUID accountId);
    List<MailListener> findByOfficialAccountId(UUID accountId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<MailListener> findByOwnerUserIdAndStateInOrderByStartedAtDescIdDesc(UUID userId, List<MailListener.State> states);
    long countByOwnerUserIdAndStartedAtAfter(UUID userId, Instant since);
    List<MailListener> findByStateInAndExpiresAtLessThan(List<MailListener.State> states, Instant now);
    List<MailListener> findByResultExpiresAtLessThan(Instant now);
    long deleteByStartedAtLessThanAndActiveAccountIdIsNull(Instant before);
}
