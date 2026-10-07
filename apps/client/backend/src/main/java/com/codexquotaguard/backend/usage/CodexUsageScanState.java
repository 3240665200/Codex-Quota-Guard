package com.codexquotaguard.backend.usage;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * State a caller should retain between scans to support byte-cursor and cross-file event-id de-duplication.
 *
 * <p>{@link #emittedEventIds()} deliberately remains exact rather than applying a time-based eviction: a
 * fork can replay older parent history long after its original file was scanned. A production integration
 * should pair the opaque event ID with server-side idempotency, then use an acknowledged checkpoint or a
 * bounded persistent index appropriate to its retention policy. Blind local expiry would reintroduce
 * fork-history double counting.</p>
 */
public record CodexUsageScanState(
        Map<String, CodexUsageFileCursor> files,
        Set<String> emittedEventIds
) {

    public CodexUsageScanState {
        files = Map.copyOf(new LinkedHashMap<>(files == null ? Map.of() : files));
        emittedEventIds = Set.copyOf(new LinkedHashSet<>(emittedEventIds == null ? Set.of() : emittedEventIds));
    }

    public static CodexUsageScanState empty() {
        return new CodexUsageScanState(Map.of(), Set.of());
    }
}
