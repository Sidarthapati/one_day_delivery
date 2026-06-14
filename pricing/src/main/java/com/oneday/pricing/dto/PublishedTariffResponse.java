package com.oneday.pricing.dto;

import com.oneday.common.domain.enums.CustomerType;
import com.oneday.pricing.domain.RateCard;

import java.util.List;

/**
 * Customer-facing published tariff: the active rate card's parameters plus its full city-pair
 * base-price matrix. Read-only, no auth — these are the published rates a customer is quoted against.
 */
public record PublishedTariffResponse(
        String code,
        CustomerType customerType,
        String version,
        String currency,
        int slabGrams,
        int firstSlabPct,
        int slabDecrementPct,
        int slabFloorPct,
        int volumetricDivisor,
        int gstBps,
        int codPctBps,
        long codMinPaise,
        long sameCityBasePricePaise,
        int discountBps,
        List<CityPairRateResponse> rates
) {
    public static PublishedTariffResponse from(RateCard c, List<CityPairRateResponse> rates) {
        return new PublishedTariffResponse(c.getCode(), c.getCustomerType(), c.getVersion(),
                c.getCurrency(), c.getSlabGrams(), c.getFirstSlabPct(), c.getSlabDecrementPct(),
                c.getSlabFloorPct(), c.getVolumetricDivisor(), c.getGstBps(), c.getCodPctBps(),
                c.getCodMinPaise(), c.getSameCityBasePricePaise(), c.getDiscountBps(), rates);
    }
}
