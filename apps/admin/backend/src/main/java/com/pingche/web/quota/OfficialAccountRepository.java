package com.pingche.web.quota;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OfficialAccountRepository extends JpaRepository<OfficialAccount, UUID> {
    List<OfficialAccount> findAllByOrderByLabelAscIdAsc();
    boolean existsByLabel(String label);
    boolean existsByLabelAndIdNot(String label, UUID id);
    boolean existsByReporterUserId(UUID reporterUserId);
    boolean existsByReporterUserIdAndIdNot(UUID reporterUserId, UUID id);
}
