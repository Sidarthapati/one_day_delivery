package com.oneday.pricing.dto;

import com.oneday.common.domain.enums.CustomerType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.List;

/**
 * Admin request to publish a new version of a published (B2C/C2C) rate card. The prior ACTIVE card
 * of the same customer type is superseded. {@code pairs} are seeded as given (callers should send
 * both directions, or rely on {@code symmetric=true} to mirror each pair automatically).
 */
public record NewRateCardRequest(
        @NotBlank String code,
        @NotNull CustomerType customerType,
        @NotBlank String version,
        Integer discountBps,
        Long sameCityBasePricePaise,
        boolean symmetric,
        @NotEmpty List<PairRate> pairs
) {
    public record PairRate(
            @NotBlank String originCity,
            @NotBlank String destCity,
            @Positive long basePricePaise
    ) {}
}
