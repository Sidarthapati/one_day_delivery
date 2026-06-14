package com.oneday.pricing.service.impl;

import com.oneday.common.domain.enums.CustomerType;
import com.oneday.common.domain.enums.DeliveryType;
import com.oneday.common.domain.enums.PaymentMode;
import com.oneday.common.port.PricingPort;
import com.oneday.common.port.dto.QuoteRequest;
import com.oneday.common.port.dto.QuoteResult;
import com.oneday.pricing.adapter.PricingPortAdapter;
import com.oneday.pricing.config.PricingProperties;
import com.oneday.pricing.domain.CityPairRate;
import com.oneday.pricing.domain.RateCard;
import com.oneday.pricing.repository.CityPairRateRepository;
import com.oneday.pricing.repository.RateCardRepository;
import com.oneday.pricing.service.NoRateConfiguredException;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * M2 ↔ M4 integration: drives the <b>real</b> M2 call chain
 * ({@link PricingPortAdapter} → {@link RateCardServiceImpl} → {@link com.oneday.pricing.service.PricingEngine})
 * through the cross-module {@link PricingPort} contract that M4 holds — using {@link QuoteRequest}s
 * built exactly as M4's two booking services build them, and M4's own chargeable-weight math.
 *
 * <p>Repositories are mocked to the values seeded by {@code V2_4__seed_rate_cards.sql}, so the
 * arithmetic asserted here is what a live booking would receive. This is the seam the M4 e2e suite
 * does not cover (it {@code @MockBean}s {@code PricingPort}).</p>
 *
 * <p>Mirrors:
 * {@code orders/.../BookingServiceImpl.java} (B2C/C2C) and
 * {@code orders/.../B2bBookingServiceImpl.java} (B2B).</p>
 */
class PricingM4IntegrationTest {

    private static final UUID B2B_CARD_ID = UUID.fromString("c0000000-0000-0000-0000-000000000001");
    private static final long BLR_DEL_PAISE = 15700; // ₹157 first 0.5 kg, per the sheet / V2_4

    private final RateCardRepository rateCards = mock(RateCardRepository.class);
    private final CityPairRateRepository pairRates = mock(CityPairRateRepository.class);

    // The exact object graph the app wires; PricingPort is the type M4 injects.
    private final PricingPort pricing = new PricingPortAdapter(
            new RateCardServiceImpl(rateCards, pairRates, new PricingProperties()),
            new com.oneday.pricing.service.PricingEngine());

    PricingM4IntegrationTest() {
        PricingProperties d = new PricingProperties();
        RateCard b2c = card("B2C-PUBLISHED", CustomerType.B2C, 0, d);
        RateCard c2c = card("C2C-PUBLISHED", CustomerType.C2C, 0, d);
        RateCard b2b = card("B2B-ACME-DEMO", CustomerType.B2B, 1500, d); // demo card, 15% off

        when(rateCards.findFirstByCustomerTypeAndStatus(CustomerType.B2C, "ACTIVE")).thenReturn(Optional.of(b2c));
        when(rateCards.findFirstByCustomerTypeAndStatus(CustomerType.C2C, "ACTIVE")).thenReturn(Optional.of(c2c));
        when(rateCards.findById(B2B_CARD_ID)).thenReturn(Optional.of(b2b));

        // BLR↔DEL is priced; any pair involving MAA is not (the seeded sheet has no MAA rows).
        CityPairRate blrDel = new CityPairRate();
        blrDel.setBasePricePaise(BLR_DEL_PAISE);
        lenient().when(pairRates.findByRateCardIdAndOriginCityAndDestCity(any(), eq("BLR"), eq("DEL")))
                .thenReturn(Optional.of(blrDel));
        lenient().when(pairRates.findByRateCardIdAndOriginCityAndDestCity(any(), eq("MAA"), eq("DEL")))
                .thenReturn(Optional.empty());
    }

    // ── M4-mirror helpers ──────────────────────────────────────────────────────

    /** M4's chargeable-weight math: volumetric = L·W·H/5 grams (divisor 5000), then max with actual. */
    private static int chargeable(int actualGrams, int lCm, int wCm, int hCm) {
        int volumetric = (lCm * wCm * hCm) / 5;
        return Math.max(actualGrams, volumetric);
    }

    /** As BookingServiceImpl builds it (B2C/C2C): cities upper-cased, b2bRateCardId null. */
    private static QuoteRequest b2cRequest(CustomerType type, DeliveryType delivery, String origin,
                                           String dest, int chargeableGrams, Long declaredValue, PaymentMode mode) {
        return new QuoteRequest(type, delivery, origin.toUpperCase(), dest.toUpperCase(),
                chargeableGrams, declaredValue, null, mode);
    }

    // ── Tests ────────────────────────────────────────────────────────────────

    @Test
    void b2cPrepaid_sheetWorkedExample_flowsThroughM4WeightMath() {
        // 40×30×20 cm box, 1.2 kg actual → volumetric 4.8 kg → chargeable 4.8 kg → 10 slabs → 7.0× base.
        // Proves M4's divisor (5) and M2's slab model agree end-to-end.
        int cw = chargeable(1200, 40, 30, 20);
        assertThat(cw).isEqualTo(4800);

        QuoteResult q = pricing.computeQuote(
                b2cRequest(CustomerType.B2C, DeliveryType.INTERCITY, "blr", "del", cw, null, PaymentMode.PREPAID));

        assertThat(q.breakdown()).containsEntry("base_freight", 7L * BLR_DEL_PAISE); // ₹1099
        assertThat(q.baseAmountPaise()).isEqualTo(109900);
        assertThat(q.taxPaise()).isEqualTo(19782);          // 18%
        assertThat(q.totalPricePaise()).isEqualTo(129682);
        assertThat(q.rateCardVersion()).isEqualTo("B2C-PUBLISHED v1.0");
    }

    @Test
    void b2cCod_addsSurchargeOnDeclaredValue() {
        int cw = chargeable(400, 10, 10, 10); // 200g volumetric → chargeable 400g → 1 slab
        QuoteResult q = pricing.computeQuote(
                b2cRequest(CustomerType.B2C, DeliveryType.INTERCITY, "BLR", "DEL", cw, 1_000_000L, PaymentMode.COD));

        assertThat(q.breakdown()).containsEntry("base_freight", BLR_DEL_PAISE);
        assertThat(q.breakdown()).containsEntry("cod_charge", 15000L); // 1.5% of ₹10,000
        assertThat(q.baseAmountPaise()).isEqualTo(BLR_DEL_PAISE + 15000);
    }

    @Test
    void c2cUsesItsOwnPublishedCard() {
        int cw = chargeable(400, 10, 10, 10);
        QuoteResult q = pricing.computeQuote(
                b2cRequest(CustomerType.C2C, DeliveryType.INTERCITY, "BLR", "DEL", cw, null, PaymentMode.PREPAID));

        assertThat(q.baseAmountPaise()).isEqualTo(BLR_DEL_PAISE); // no discount
        assertThat(q.rateCardVersion()).isEqualTo("C2C-PUBLISHED v1.0");
    }

    @Test
    void b2cSameCity_usesCardSameCityBase() {
        int cw = chargeable(400, 10, 10, 10);
        QuoteResult q = pricing.computeQuote(
                b2cRequest(CustomerType.B2C, DeliveryType.SAME_CITY, "BLR", "BLR", cw, null, PaymentMode.PREPAID));

        assertThat(q.breakdown()).containsEntry("base_freight", 5000L); // PricingProperties default
    }

    @Test
    void b2bResolvesAccountCardByIdAndAppliesDiscount() {
        // As B2bBookingServiceImpl builds it: customerType B2B, account rate card id, paymentMode null.
        int cw = chargeable(400, 10, 10, 10);
        QuoteResult q = pricing.computeQuote(new QuoteRequest(
                CustomerType.B2B, DeliveryType.INTERCITY, "BLR", "DEL", cw, 500_000L, B2B_CARD_ID, null));

        assertThat(q.breakdown()).containsEntry("base_freight", BLR_DEL_PAISE);
        assertThat(q.breakdown()).containsEntry("b2b_discount", -2355L); // 15% of ₹157
        assertThat(q.breakdown()).doesNotContainKey("cod_charge"); // B2B never COD
        assertThat(q.baseAmountPaise()).isEqualTo(BLR_DEL_PAISE - 2355);
        assertThat(q.rateCardVersion()).isEqualTo("B2B-ACME-DEMO v1.0");
    }

    @Test
    void unpricedLane_surfacesNoRateConfigured() {
        // MAA is serviceable but absent from the sheet → M4 surfaces this as 422.
        int cw = chargeable(400, 10, 10, 10);
        assertThatThrownBy(() -> pricing.computeQuote(
                b2cRequest(CustomerType.B2C, DeliveryType.INTERCITY, "MAA", "DEL", cw, null, PaymentMode.PREPAID)))
                .isInstanceOf(NoRateConfiguredException.class);
    }

    /** Builds a card carrying the published-sheet defaults, as RateCardServiceImpl.publishNewVersion does. */
    private static RateCard card(String code, CustomerType type, int discountBps, PricingProperties d) {
        RateCard c = new RateCard();
        c.setCode(code);
        c.setCustomerType(type);
        c.setVersion("v1.0");
        c.setStatus("ACTIVE");
        c.setSlabGrams(d.getSlabGrams());
        c.setFirstSlabPct(d.getFirstSlabPct());
        c.setSlabDecrementPct(d.getSlabDecrementPct());
        c.setSlabFloorPct(d.getSlabFloorPct());
        c.setGstBps(d.getGstBps());
        c.setCodPctBps(d.getCodPctBps());
        c.setCodMinPaise(d.getCodMinPaise());
        c.setSameCityBasePricePaise(d.getSameCityBasePricePaise());
        c.setDiscountBps(discountBps);
        return c;
    }
}
