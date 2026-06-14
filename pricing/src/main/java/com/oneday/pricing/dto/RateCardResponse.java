package com.oneday.pricing.dto;

import com.oneday.common.domain.enums.CustomerType;
import com.oneday.pricing.domain.RateCard;

import java.time.Instant;
import java.util.UUID;

public record RateCardResponse(
        UUID id,
        String code,
        CustomerType customerType,
        String version,
        String status,
        Instant effectiveFrom,
        Instant effectiveTo,
        String currency,
        int slabGrams,
        int volumetricDivisor,
        int firstSlabPct,
        int slabDecrementPct,
        int slabFloorPct,
        int discountBps,
        int gstBps,
        int codPctBps,
        long codMinPaise,
        long sameCityBasePricePaise
) {
    public static RateCardResponse from(RateCard c) {
        return new RateCardResponse(c.getId(), c.getCode(), c.getCustomerType(), c.getVersion(),
                c.getStatus(), c.getEffectiveFrom(), c.getEffectiveTo(), c.getCurrency(),
                c.getSlabGrams(), c.getVolumetricDivisor(), c.getFirstSlabPct(), c.getSlabDecrementPct(),
                c.getSlabFloorPct(), c.getDiscountBps(), c.getGstBps(), c.getCodPctBps(),
                c.getCodMinPaise(), c.getSameCityBasePricePaise());
    }
}
