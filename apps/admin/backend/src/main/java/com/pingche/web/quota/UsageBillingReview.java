package com.pingche.web.quota;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.annotation.JsonDeserialize;

public final class UsageBillingReview {
    private UsageBillingReview() { }

    public record Facts(
            @Pattern(regexp="[A-Za-z0-9][A-Za-z0-9._:/-]{0,95}") String model,
            @Pattern(regexp="none|minimal|low|medium|high|xhigh|max|ultra") String reasoningEffort,
            @JsonDeserialize(using=UsageBillingTokenDeserializer.class) @NotNull @Min(0) @Max(100_000_000) Long totalTokens,
            @JsonDeserialize(using=UsageBillingTokenDeserializer.class) @Min(0) @Max(100_000_000) Long inputTokens,
            @JsonDeserialize(using=UsageBillingTokenDeserializer.class) @Min(0) @Max(100_000_000) Long cachedInputTokens,
            @JsonDeserialize(using=UsageBillingTokenDeserializer.class) @Min(0) @Max(100_000_000) Long cacheWriteInputTokens,
            @JsonDeserialize(using=UsageBillingTokenDeserializer.class) @Min(0) @Max(100_000_000) Long outputTokens,
            @JsonDeserialize(using=UsageBillingTokenDeserializer.class) @Min(0) @Max(100_000_000) Long reasoningOutputTokens,
            @Pattern(regexp="standard|fast|unknown") String serviceTier) {
        static Facts from(CodexUsageEvent event) {
            return new Facts(event.getModel(),event.getReasoningEffort(),event.getTotalTokens(),event.getInputTokens(),
                    event.getCachedInputTokens(),event.getCacheWriteInputTokens(),event.getOutputTokens(),event.getReasoningOutputTokens(),event.getServiceTier());
        }
        CodexUsageEvent apply(CodexUsageEvent original) {
            var copy = new CodexUsageEvent(original.getId(),original.getUserId(),original.getTeamId(),totalTokens,
                    original.getOccurredAt(),original.getReceivedAt(),original.getSource(),model,reasoningEffort,
                    inputTokens,cachedInputTokens,cacheWriteInputTokens,outputTokens,reasoningOutputTokens);
            copy.setMeterDeviceId(original.getMeterDeviceId()); copy.setServiceTier(serviceTier);
            return copy;
        }
    }

    public record Prices(
            @NotNull @DecimalMin("0") @DecimalMax("1000000") @Digits(integer=7,fraction=6) BigDecimal inputPrice,
            @NotNull @DecimalMin("0") @DecimalMax("1000000") @Digits(integer=7,fraction=6) BigDecimal outputPrice,
            @NotNull @DecimalMin("0") @DecimalMax("1000000") @Digits(integer=7,fraction=6) BigDecimal cacheReadPrice,
            @NotNull @DecimalMin("0") @DecimalMax("1000000") @Digits(integer=7,fraction=6) BigDecimal cacheWritePrice,
            @NotNull @DecimalMin("1") @DecimalMax("100") @Digits(integer=3,fraction=6) BigDecimal fastMultiplier) {
        static Prices from(UsageModelPrice price) {
            return price == null ? null : new Prices(price.getInputPrice(),price.getOutputPrice(),price.getCacheReadPrice(),price.getCacheWritePrice(),price.getFastMultiplier());
        }
        UsageModelPrice modelPrice(String model) {
            if (model == null) return null;
            var price = new UsageModelPrice(model,inputPrice,outputPrice,cacheReadPrice,cacheWritePrice,Instant.EPOCH);
            price.setFastMultiplier(fastMultiplier); return price;
        }
    }

    public record Request(@NotNull @Min(0) Long revision, @NotNull @Pattern(regexp="[a-f0-9]{64}") String fingerprint,
            @NotNull @Valid Facts facts, @NotNull @Pattern(regexp="CATALOG|CUSTOM") String pricingMode,
            @Valid Prices customPrice, @NotBlank @Size(max=500) String note,
            @Pattern(regexp="[a-f0-9]{64}") String previewToken) { }
    public record Preview(String previewToken,String costStatus,String costUsd,String multiplier,
            Prices appliedPrice,List<String> missingFields) { }
    public record Audit(UUID id,long revision,UUID reviewedBy,String administratorName,Instant reviewedAt,
            String note,JsonNode before,JsonNode after) { }
    public record Detail(String id,UUID userId,UUID teamId,UUID officialAccountId,Instant occurredAt,Instant receivedAt,
            String source,Facts original,Facts effective,long revision,String fingerprint,boolean editable,
            String pricingMode,Prices customPrice,Prices catalogPrice,Prices appliedPrice,String multiplier,
            String costStatus,String costUsd,String modeStatus,List<Audit> history) { }
    public record Saved(Detail detail,Preview calculation) { }
}
