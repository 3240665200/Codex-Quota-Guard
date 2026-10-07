package com.pingche.web.quota;

import com.pingche.web.auth.DomainException;
import com.pingche.web.user.UserRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Service
public class OfficialQuotaSnapshotService {

    private final UserRepository users;
    private final OfficialQuotaSnapshotRepository snapshots;
    private final ObjectMapper mapper;
    private final Clock clock;

    public OfficialQuotaSnapshotService(UserRepository users, OfficialQuotaSnapshotRepository snapshots,
            ObjectMapper mapper, Clock clock) {
        this.users = users;
        this.snapshots = snapshots;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Transactional
    public OfficialQuotaSnapshotResponse report(UUID userId, OfficialQuotaSnapshotRequest request) {
        Instant now = clock.instant();
        if (!users.existsById(userId)) throw notFound();
        if (request.fetchedAt().isAfter(now.plus(Duration.ofMinutes(5)))
                || request.fetchedAt().isBefore(now.minus(Duration.ofDays(7)))) {
            throw invalid();
        }
        for (OfficialQuotaSnapshotRequest.Window window : request.windows()) {
            if (window.usedPercent().signum() < 0
                    || window.usedPercent().compareTo(java.math.BigDecimal.valueOf(100)) > 0
                    || window.usedPercent().scale() > 2) {
                throw invalid();
            }
        }
        String serialized = mapper.writeValueAsString(request.windows());
        if (serialized.length() > 8192) throw invalid();
        OfficialQuotaSnapshotEntity existing = snapshots.findById(userId).orElse(null);
        if (existing != null && existing.getFetchedAt().isAfter(request.fetchedAt())) {
            return project(existing);
        }
        OfficialQuotaSnapshotEntity saved = snapshots.saveAndFlush(new OfficialQuotaSnapshotEntity(
                userId, request.planType(), serialized, request.fetchedAt(), now));
        return project(saved);
    }

    @Transactional(readOnly = true)
    public OfficialQuotaSnapshotResponse read(UUID userId) {
        if (!users.existsById(userId)) throw notFound();
        OfficialQuotaSnapshotEntity snapshot = snapshots.findById(userId).orElse(null);
        return snapshot == null ? null : project(snapshot);
    }

    private OfficialQuotaSnapshotResponse project(OfficialQuotaSnapshotEntity snapshot) {
        List<OfficialQuotaSnapshotRequest.Window> windows = mapper.readValue(
                snapshot.getWindowsJson(), new TypeReference<List<OfficialQuotaSnapshotRequest.Window>>() { });
        return new OfficialQuotaSnapshotResponse(snapshot.getPlanType(), windows,
                snapshot.getFetchedAt(), snapshot.getReceivedAt());
    }

    private DomainException invalid() {
        return new DomainException("INVALID_OFFICIAL_QUOTA_SNAPSHOT",
                "官方额度摘要格式无效。", HttpStatus.BAD_REQUEST);
    }

    private DomainException notFound() {
        return new DomainException("USER_NOT_FOUND", "User not found", HttpStatus.NOT_FOUND);
    }
}
