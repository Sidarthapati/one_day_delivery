package com.oneday.pricing.service;

import com.oneday.common.domain.enums.CustomerType;
import com.oneday.common.domain.enums.PaymentMode;
import com.oneday.common.port.dto.QuoteResult;
import com.oneday.pricing.domain.RateCard;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PricingEngineTest {

    private final PricingEngine engine = new PricingEngine();

    /** A B2C card mirroring the published sheet defaults. */
    private RateCard b2cCard() {
        RateCard c = new RateCard();
        c.setCode("B2C-PUBLISHED");
        c.setVersion("v1.0");
        c.setCustomerType(CustomerType.B2C);
        c.setSlabGrams(500);
        c.setFirstSlabPct(100);
        c.setSlabDecrementPct(10);
        c.setSlabFloorPct(60);
        c.setDiscountBps(0);
        c.setGstBps(1800);
        c.setCodPctBps(150);
        c.setCodMinPaise(5000);
        c.setSameCityBasePricePaise(5000);
        return c;
    }

    @Test
    void slabPercentDecaysToFloor() {
        RateCard c = b2cCard();
        assertThat(PricingEngine.slabPercent(1, c)).isEqualTo(100);
        assertThat(PricingEngine.slabPercent(2, c)).isEqualTo(90);
        assertThat(PricingEngine.slabPercent(3, c)).isEqualTo(80);
        assertThat(PricingEngine.slabPercent(4, c)).isEqualTo(70);
        assertThat(PricingEngine.slabPercent(5, c)).isEqualTo(60);
        assertThat(PricingEngine.slabPercent(6, c)).isEqualTo(60); // floored
        assertThat(PricingEngine.slabPercent(20, c)).isEqualTo(60);
    }

    @Test
    void slabCountCeilsAndHasMinimumOfOne() {
        assertThat(PricingEngine.slabCount(0, 500)).isEqualTo(1);
        assertThat(PricingEngine.slabCount(500, 500)).isEqualTo(1);
        assertThat(PricingEngine.slabCount(501, 500)).isEqualTo(2);
        assertThat(PricingEngine.slabCount(4800, 500)).isEqualTo(10); // 4.8 kg → 10 slabs
    }

    @Test
    void singleSlabIsJustBasePlusGst() {
        // BLR→DEL base ₹157, 0.4 kg → 1 slab → freight ₹157, GST 18% = ₹28.26.
        QuoteResult q = engine.price(b2cCard(), 15700, 400, PaymentMode.PREPAID, null);
        assertThat(q.baseAmountPaise()).isEqualTo(15700);
        assertThat(q.taxPaise()).isEqualTo(2826);
        assertThat(q.totalPricePaise()).isEqualTo(18526);
        assertThat(q.breakdown()).containsEntry("base_freight", 15700L);
        assertThat(q.rateCardVersion()).isEqualTo("B2C-PUBLISHED v1.0");
    }

    @Test
    void multiSlabSumsDecayingPercents() {
        // BLR→DEL base ₹157, 1.2 kg → 3 slabs (100+90+80 = 270%) → freight ₹423.90.
        QuoteResult q = engine.price(b2cCard(), 15700, 1200, PaymentMode.PREPAID, null);
        assertThat(q.breakdown()).containsEntry("base_freight", 42390L);
        assertThat(q.baseAmountPaise()).isEqualTo(42390);
        assertThat(q.taxPaise()).isEqualTo(Math.round(42390 * 0.18)); // 7630
    }

    @Test
    void wor011_worksheetExample_48kgGivesSevenTimesBase() {
        // Sheet example: 4.8 kg → 10 slabs → multiplier 100+90+80+70+60*6 = 700% = 7.0× base.
        QuoteResult q = engine.price(b2cCard(), 15700, 4800, PaymentMode.PREPAID, null);
        assertThat(q.breakdown()).containsEntry("base_freight", 7L * 15700); // ₹1099
    }

    @Test
    void codAddsPercentOfDeclaredValueWhenAboveMinimum() {
        // declared ₹10,000 → 1.5% = ₹150 (> ₹50 min). Applied to taxable base, GST on top.
        QuoteResult q = engine.price(b2cCard(), 15700, 400, PaymentMode.COD, 1_000_000L);
        assertThat(q.breakdown()).containsEntry("cod_charge", 15000L);
        assertThat(q.baseAmountPaise()).isEqualTo(15700 + 15000);
    }

    @Test
    void codFallsBackToFiftyRupeeMinimum() {
        // declared ₹1,000 → 1.5% = ₹15 < ₹50 min → COD = ₹50.
        QuoteResult q = engine.price(b2cCard(), 15700, 400, PaymentMode.COD, 100_000L);
        assertThat(q.breakdown()).containsEntry("cod_charge", 5000L);
    }

    @Test
    void prepaidHasNoCodCharge() {
        QuoteResult q = engine.price(b2cCard(), 15700, 400, PaymentMode.PREPAID, 1_000_000L);
        assertThat(q.breakdown()).doesNotContainKey("cod_charge");
    }

    @Test
    void b2bDiscountReducesFreight() {
        RateCard b2b = b2cCard();
        b2b.setCode("B2B-ACME-DEMO");
        b2b.setCustomerType(CustomerType.B2B);
        b2b.setDiscountBps(1500); // 15%
        // base ₹157, 1 slab → freight ₹157, discount ₹23.55 → ₹133.45 taxable.
        QuoteResult q = engine.price(b2b, 15700, 400, null, null);
        assertThat(q.breakdown()).containsEntry("base_freight", 15700L);
        assertThat(q.breakdown()).containsEntry("b2b_discount", -2355L);
        assertThat(q.baseAmountPaise()).isEqualTo(15700 - 2355);
    }
}
