package com.pingche.web.quota;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/admin/usage/model-prices")
@PreAuthorize("hasRole('ADMIN')")
public class UsageModelPriceController {
    private final UsageModelPriceRepository prices;
    private final Clock clock;

    public UsageModelPriceController(UsageModelPriceRepository prices, Clock clock) {
        this.prices = prices;
        this.clock = clock;
    }

    public record PriceRequest(
            @NotNull @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._:/-]{0,95}") String model,
            @NotNull @DecimalMin("0") @DecimalMax("1000000") @Digits(integer = 7, fraction = 6) BigDecimal inputPrice,
            @NotNull @DecimalMin("0") @DecimalMax("1000000") @Digits(integer = 7, fraction = 6) BigDecimal outputPrice,
            @NotNull @DecimalMin("0") @DecimalMax("1000000") @Digits(integer = 7, fraction = 6) BigDecimal cacheReadPrice,
            @NotNull @DecimalMin("0") @DecimalMax("1000000") @Digits(integer = 7, fraction = 6) BigDecimal cacheWritePrice,
            @DecimalMin("1") @DecimalMax("100") @Digits(integer = 3, fraction = 6) BigDecimal fastMultiplier) {
        public PriceRequest(String model, BigDecimal input, BigDecimal output, BigDecimal cached, BigDecimal written) { this(model,input,output,cached,written,null); }
    }

    public record PriceResponse(String model, BigDecimal inputPrice, BigDecimal outputPrice,
            BigDecimal cacheReadPrice, BigDecimal cacheWritePrice, String currency, Instant updatedAt,
            String priceSource, String sourceReference, LocalDate sourceCheckedAt, BigDecimal fastMultiplier) {
        static PriceResponse from(UsageModelPrice price) {
            return new PriceResponse(price.getModel(), price.getInputPrice(), price.getOutputPrice(),
                    price.getCacheReadPrice(), price.getCacheWritePrice(), "USD", price.getUpdatedAt(),
                    price.getPriceSource(), price.getSourceReference(), price.getSourceCheckedAt(), price.getFastMultiplier());
        }
    }

    @GetMapping
    public ResponseEntity<List<PriceResponse>> list() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(prices.findAll().stream()
                .sorted(java.util.Comparator.comparing(UsageModelPrice::getModel))
                .map(PriceResponse::from).toList());
    }

    @PutMapping
    public ResponseEntity<PriceResponse> save(@Valid @RequestBody PriceRequest request) {
        var price = new UsageModelPrice(request.model(), request.inputPrice(), request.outputPrice(),
                request.cacheReadPrice(), request.cacheWritePrice(), clock.instant());
        price.setFastMultiplier(request.fastMultiplier() == null ? prices.findById(request.model()).map(UsageModelPrice::getFastMultiplier).orElse(new BigDecimal("1.5")) : request.fastMultiplier());
        price = prices.save(price);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(PriceResponse.from(price));
    }

    @DeleteMapping
    public ResponseEntity<Void> delete(@RequestParam @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._:/-]{0,95}") String model) {
        prices.deleteById(model);
        return ResponseEntity.noContent().build();
    }
}
