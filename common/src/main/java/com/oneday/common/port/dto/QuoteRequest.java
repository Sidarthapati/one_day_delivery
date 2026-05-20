package com.oneday.common.port.dto;

import com.oneday.common.domain.enums.CustomerType;
import com.oneday.common.domain.enums.DeliveryType;

import java.util.UUID;

/**
 * @param customerType         B2C, B2B, or C2C — selects the rate card family
 * @param deliveryType         INTERCITY or SAME_CITY — from ServiceabilityResult
 * @param originCity           city code, e.g. "BLR"
 * @param destCity             city code, e.g. "DEL"
 * @param chargeableWeightGrams max(actualWeight, volumetricWeight) — computed by M4 before this call
 * @param declaredValuePaise   shipper-declared value; does not affect pricing in v1 but required for B2B
 * @param b2bRateCardId        account-specific rate card; null for B2C and C2C
 */
public record QuoteRequest(
        CustomerType customerType,
        DeliveryType deliveryType,
        String originCity,
        String destCity,
        int chargeableWeightGrams,
        Long declaredValuePaise,
        UUID b2bRateCardId
) {}
