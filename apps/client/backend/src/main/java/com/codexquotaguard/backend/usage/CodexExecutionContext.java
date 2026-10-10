package com.codexquotaguard.backend.usage;

import java.util.Locale;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/** Only non-secret model selection metadata, never the remaining turn context. */
public record CodexExecutionContext(String model, String reasoningEffort) {
    public CodexExecutionContext {
        model = safeModel(model);
        reasoningEffort = safeEffort(reasoningEffort);
    }

    static CodexExecutionContext from(JsonNode payload) {
        String effort = payload.get("reasoning_effort") != null ? text(payload, "reasoning_effort")
                : payload.get("effort") != null ? text(payload, "effort")
                : text(payload.path("reasoning"), "effort");
        return new CodexExecutionContext(text(payload, "model"), effort);
    }

    static boolean hasEffort(JsonNode payload) {
        return payload.get("reasoning_effort") != null || payload.get("effort") != null
                || payload.path("reasoning").get("effort") != null;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isString() ? value.asString() : null;
    }

    static String safeModel(String value) {
        return value != null && value.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,95}") ? value : null;
    }

    static String safeEffort(String value) {
        String normalized = value == null ? null : value.toLowerCase(Locale.ROOT);
        return normalized != null && Set.of("none", "minimal", "low", "medium", "high", "xhigh", "max", "ultra")
                .contains(normalized) ? normalized : null;
    }
}
