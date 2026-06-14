package com.oneday.pricing.dto;

import com.oneday.pricing.domain.CityPairRate;

public record CityPairRateResponse(
        String originCity,
        String destCity,
        long basePricePaise
) {
    public static CityPairRateResponse from(CityPairRate r) {
        return new CityPairRateResponse(r.getOriginCity(), r.getDestCity(), r.getBasePricePaise());
    }
}
