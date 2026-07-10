package com.oneday.pricing.service;

import com.oneday.common.domain.enums.PaymentMode;
import com.oneday.common.port.dto.QuoteResult;
import com.oneday.pricing.domain.RateCard;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Pure pricing computation — no DB, no Spring state. Given a resolved {@link RateCard}, the base
 * price for the city pair, and the booking context, produces the full {@link QuoteResult}.
 *
 * <p>Rules (from the published rate sheet):</p>
 * <ul>
 *   <li>The base price covers the first slab (0.5 kg). Each further 0.5 kg slab decays by 10
 *       percentage points (90%, 80%, 70%, 60%) and floors at 60%:
 *       {@code pct(n) = max(floor, firstSlabPct − decrement·(n−1))}.</li>
 *   <li>B2B accounts apply their card's negotiated {@code discountBps} to freight.</li>
 *   <li>COD adds {@code max(codMinPaise, declaredValue·codPctBps/10000)}.</li>
 *   <li>GST ({@code gstBps}) applies to (freight after discount + COD).</li>
 * </ul>
 */
@Component
public class PricingEngine {

    /**
     * @param card         the resolved rate card (carries all rate parameters)
     * @param basePricePaise base price for the first slab of the resolved city pair
     * @param chargeableWeightGrams chargeable weight (M4 already took max of actual/volumetric)
     * @param paymentMode  PREPAID or COD (null = PREPAID)
     * @param declaredValuePaise GMV basis for the COD surcharge (null = 0)
     */
    public QuoteResult price(RateCard card, long basePricePaise, int chargeableWeightGrams,
                             PaymentMode paymentMode, Long declaredValuePaise) {

        int slabs = slabCount(chargeableWeightGrams, card.getSlabGrams());

        long freight = 0L;
        for (int n = 1; n <= slabs; n++) {
            int pct = slabPercent(n, card);
            freight += Math.round((double) basePricePaise * pct / 100.0);
        }

        long discount = card.getDiscountBps() > 0
                ? Math.round((double) freight * card.getDiscountBps() / 10_000.0)
                : 0L;
        long freightAfterDiscount = freight - discount;

        long codCharge = 0L;
        if (paymentMode == PaymentMode.COD) {
            long declared = declaredValuePaise == null ? 0L : declaredValuePaise;
            long pctCharge = Math.round((double) declared * card.getCodPctBps() / 10_000.0);
            codCharge = Math.max(card.getCodMinPaise(), pctCharge);
        }

        long taxableBase = freightAfterDiscount + codCharge;
        long gst = Math.round((double) taxableBase * card.getGstBps() / 10_000.0);
        long total = taxableBase + gst;

        Map<String, Long> breakdown = new LinkedHashMap<>();
        breakdown.put("base_freight", freight);
        if (discount > 0) {
            breakdown.put("b2b_discount", -discount);
        }
        if (codCharge > 0) {
            breakdown.put("cod_charge", codCharge);
        }
        breakdown.put("gst_" + (card.getGstBps() / 100) + "pct", gst);

        String version = card.getCode() + " " + card.getVersion();
        return new QuoteResult(taxableBase, gst, total, breakdown, version);
    }

    /** Number of weight slabs (ceil), minimum 1. */
    static int slabCount(int chargeableWeightGrams, int slabGrams) {
        if (chargeableWeightGrams <= 0) {
            return 1;
        }
        return (chargeableWeightGrams + slabGrams - 1) / slabGrams;
    }

    /** Percentage of base price charged for slab {@code n} (1-based). */
    static int slabPercent(int n, RateCard card) {
        int pct = card.getFirstSlabPct() - card.getSlabDecrementPct() * (n - 1);
        return Math.max(card.getSlabFloorPct(), pct);
    }
}
