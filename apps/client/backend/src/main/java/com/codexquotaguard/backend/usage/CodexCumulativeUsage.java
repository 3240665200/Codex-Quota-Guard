package com.codexquotaguard.backend.usage;

/** Internal persistence value for a legacy cumulative {@code token_count} snapshot. */
public record CodexCumulativeUsage(
        Long inputTokens,
        Long cachedInputTokens,
        Long cacheWriteInputTokens,
        Long outputTokens,
        Long reasoningOutputTokens,
        long totalTokens
) {

    public CodexCumulativeUsage {
        requireNonNegative(inputTokens, "inputTokens");
        requireNonNegative(cachedInputTokens, "cachedInputTokens");
        requireNonNegative(cacheWriteInputTokens, "cacheWriteInputTokens");
        requireNonNegative(outputTokens, "outputTokens");
        requireNonNegative(reasoningOutputTokens, "reasoningOutputTokens");
        requireNonNegative(totalTokens, "totalTokens");
    }

    public boolean isAtLeast(CodexCumulativeUsage other) {
        return isAtLeast(inputTokens, other.inputTokens)
                && isAtLeast(cachedInputTokens, other.cachedInputTokens)
                && isAtLeast(cacheWriteInputTokens, other.cacheWriteInputTokens)
                && isAtLeast(outputTokens, other.outputTokens)
                && isAtLeast(reasoningOutputTokens, other.reasoningOutputTokens)
                && totalTokens >= other.totalTokens;
    }

    public CodexCumulativeUsage minus(CodexCumulativeUsage other) {
        if (!isAtLeast(other)) {
            throw new IllegalArgumentException("Cumulative token counters cannot be decremented.");
        }
        return new CodexCumulativeUsage(
                delta(inputTokens, other.inputTokens),
                delta(cachedInputTokens, other.cachedInputTokens),
                delta(cacheWriteInputTokens, other.cacheWriteInputTokens),
                delta(outputTokens, other.outputTokens),
                delta(reasoningOutputTokens, other.reasoningOutputTokens),
                totalTokens - other.totalTokens
        );
    }

    public boolean isZero() {
        return isZeroOrUnknown(inputTokens)
                && isZeroOrUnknown(cachedInputTokens)
                && isZeroOrUnknown(cacheWriteInputTokens)
                && isZeroOrUnknown(outputTokens)
                && isZeroOrUnknown(reasoningOutputTokens)
                && totalTokens == 0;
    }

    private static boolean isAtLeast(Long current, Long previous) {
        return current == null || previous == null || current >= previous;
    }

    private static Long delta(Long current, Long previous) {
        // A component delta is unknown unless both cumulative snapshots contain it.
        return current == null || previous == null ? null : current - previous;
    }

    private static boolean isZeroOrUnknown(Long value) {
        return value == null || value == 0;
    }

    private static void requireNonNegative(Long value, String name) {
        if (value != null && value < 0) {
            throw new IllegalArgumentException(name + " must not be negative.");
        }
    }
}
