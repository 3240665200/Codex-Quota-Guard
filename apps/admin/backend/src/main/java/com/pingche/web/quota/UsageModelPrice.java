package com.pingche.web.quota;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** USD prices per million tokens for estimates, not subscription charges. */
@Entity
@Table(name = "usage_model_prices")
public class UsageModelPrice {
    @Id
    @Column(length = 96)
    private String model;
    @Column(name = "input_price", nullable = false, precision = 18, scale = 6)
    private BigDecimal inputPrice;
    @Column(name = "output_price", nullable = false, precision = 18, scale = 6)
    private BigDecimal outputPrice;
    @Column(name = "cache_read_price", nullable = false, precision = 18, scale = 6)
    private BigDecimal cacheReadPrice;
    @Column(name = "cache_write_price", nullable = false, precision = 18, scale = 6)
    private BigDecimal cacheWritePrice;
    @Column(name = "fast_multiplier", nullable = false, precision = 12, scale = 6)
    private BigDecimal fastMultiplier = new BigDecimal("1.5");
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @Column(name = "price_source", nullable = false, length = 32)
    private String priceSource;
    @Column(name = "source_reference", length = 512)
    private String sourceReference;
    @Column(name = "source_checked_at")
    private LocalDate sourceCheckedAt;

    protected UsageModelPrice() { }

    public UsageModelPrice(String model, BigDecimal inputPrice, BigDecimal outputPrice,
            BigDecimal cacheReadPrice, BigDecimal cacheWritePrice, Instant updatedAt) {
        this.model = model;
        this.inputPrice = inputPrice;
        this.outputPrice = outputPrice;
        this.cacheReadPrice = cacheReadPrice;
        this.cacheWritePrice = cacheWritePrice;
        this.updatedAt = updatedAt;
        this.priceSource = "MANUAL";
    }

    public String getModel() { return model; }
    public BigDecimal getInputPrice() { return inputPrice; }
    public BigDecimal getOutputPrice() { return outputPrice; }
    public BigDecimal getCacheReadPrice() { return cacheReadPrice; }
    public BigDecimal getCacheWritePrice() { return cacheWritePrice; }
    public BigDecimal getFastMultiplier() { return fastMultiplier; }
    public void setFastMultiplier(BigDecimal value) { this.fastMultiplier = value; }
    public Instant getUpdatedAt() { return updatedAt; }
    public String getPriceSource() { return priceSource; }
    public String getSourceReference() { return sourceReference; }
    public LocalDate getSourceCheckedAt() { return sourceCheckedAt; }
}
