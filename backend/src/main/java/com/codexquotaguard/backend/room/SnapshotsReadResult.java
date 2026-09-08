package com.codexquotaguard.backend.room;

import com.codexquotaguard.backend.quota.QuotaSnapshot;

import java.util.List;

public record SnapshotsReadResult(List<QuotaSnapshot> snapshots, List<String> warnings) {

    public SnapshotsReadResult {
        snapshots = List.copyOf(snapshots);
        warnings = List.copyOf(warnings);
    }
}
