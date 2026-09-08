package com.codexquotaguard.backend.room;

public record QuotaRule(String limitId, String window, double maxUsedPercent, String action) {
}
