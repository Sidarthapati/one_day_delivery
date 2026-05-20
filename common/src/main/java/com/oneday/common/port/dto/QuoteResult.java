package com.oneday.common.port.dto;

import java.util.Map;

/**
 * Complete pricing result from M2. M4 stores every field and forwards the breakdown
 * to the booking response unchanged — it does not compute or validate any field here.
 *
 * @param baseAmountPaise  freight charge before tax
 * @param taxPaise         GST (18% in v1); M2 owns the rate
 * @param totalPricePaise  baseAmountPaise + taxPaise
 * @param breakdown        itemised charge components, e.g. {"base_freight": 4000, "fuel_surcharge": 500}
 * @param rateCardVersion  snapshot of the M2 rate card applied; stored on Shipment for audit
 */
public record QuoteResult(
        long baseAmountPaise,
        long taxPaise,
        long totalPricePaise,
        Map<String, Long> breakdown,
        String rateCardVersion
) {}
