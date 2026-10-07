package com.codexquotaguard.backend.usage;

/** Internal persistence value for a legacy cumulative {@code token_count} snapshot. */
public record CodexCumulativeUsage(
        long inputTokens,
        long cachedInputTokens,
        long cacheWriteInputTokens,
        long outputTokens,
        long reasoningOutputTokens,
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
        return inputTokens >= other.inputTokens
                && cachedInputTokens >= other.cachedInputTokens
                && cacheWriteInputTokens >= other.cacheWriteInputTokens
                && outputTokens >= other.outputTokens
                && reasoningOutputTokens >= other.reasoningOutputTokens
                && totalTokens >= other.totalTokens;
    }

    public CodexCumulativeUsage minus(CodexCumulativeUsage other) {
        if (!isAtLeast(other)) {
            throw new IllegalArgumentException("Cumulative token counters cannot be decremented.");
        }
        return new CodexCumulativeUsage(
                inputTokens - other.inputTokens,
                cachedInputTokens - other.cachedInputTokens,
                cacheWriteInputTokens - other.cacheWriteInputTokens,
                outputTokens - other.outputTokens,
                reasoningOutputTokens - other.reasoningOutputTokens,
                totalTokens - other.totalTokens
        );
    }

    public boolean isZero() {
        return inputTokens == 0
                && cachedInputTokens == 0
                && cacheWriteInputTokens == 0
                && outputTokens == 0
                && reasoningOutputTokens == 0
                && totalTokens == 0;
    }

    private static void requireNonNegative(long value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must not be negative.");
        }
    }
}
