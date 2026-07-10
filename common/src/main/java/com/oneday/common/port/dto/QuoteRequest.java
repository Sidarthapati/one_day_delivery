package com.oneday.common.port.dto;

import com.oneday.common.domain.enums.CustomerType;
import com.oneday.common.domain.enums.DeliveryType;
import com.oneday.common.domain.enums.PaymentMode;

import java.util.UUID;

/**
 * @param customerType         B2C, B2B, or C2C — selects the rate card family
 * @param deliveryType         INTERCITY or SAME_CITY — from ServiceabilityResult
 * @param originCity           city code, e.g. "BLR"
 * @param destCity             city code, e.g. "DEL"
 * @param chargeableWeightGrams max(actualWeight, volumetricWeight) — computed by M4 before this call
 * @param declaredValuePaise   shipper-declared value (GMV); basis for the COD surcharge. Does not
 *                             otherwise affect freight in v1; required for B2B.
 * @param b2bRateCardId        account-specific rate card; null for B2C and C2C
 * @param paymentMode          PREPAID or COD; null = treated as PREPAID (no COD surcharge).
 *                             M2 adds the COD charge only when this is {@link PaymentMode#COD}.
 */
public record QuoteRequest(
        CustomerType customerType,
        DeliveryType deliveryType,
        String originCity,
        String destCity,
        int chargeableWeightGrams,
        Long declaredValuePaise,
        UUID b2bRateCardId,
        PaymentMode paymentMode
) {}
