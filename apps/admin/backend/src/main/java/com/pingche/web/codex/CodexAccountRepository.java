package com.pingche.web.codex;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CodexAccountRepository extends
        JpaRepository<CodexAccount, UUID>,
        JpaSpecificationExecutor<CodexAccount> {

    boolean existsByEmail(String email);

    boolean existsByEmailAndIdNot(String email, UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select account from CodexAccount account where account.id = :id")
    Optional<CodexAccount> findByIdForUpdate(@Param("id") UUID id);
}
