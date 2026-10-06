package com.pingche.web.user;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, UUID>, JpaSpecificationExecutor<User> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    boolean existsByEmailAndIdNot(String email, UUID id);

    boolean existsByRole(User.Role role);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select user from User user
             where user.role = com.pingche.web.user.User.Role.ADMIN
               and user.status = com.pingche.web.user.User.Status.ACTIVE
             order by user.id
            """)
    List<User> findActiveAdministratorsForUpdate();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select user from User user where user.id = :id")
    Optional<User> findByIdForUpdate(@Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select user from User user where user.teamId = :teamId order by user.id")
    List<User> findByTeamIdForUpdate(@Param("teamId") UUID teamId);

    long countByTeamId(UUID teamId);

    long countByStatus(User.Status status);

    long countByRole(User.Role role);

    long countByCreatedAtGreaterThanEqual(Instant createdAt);
}
