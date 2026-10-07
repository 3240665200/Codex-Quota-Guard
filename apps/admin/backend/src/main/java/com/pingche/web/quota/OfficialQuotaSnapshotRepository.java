package com.pingche.web.quota;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OfficialQuotaSnapshotRepository extends JpaRepository<OfficialQuotaSnapshotEntity, UUID> { }
