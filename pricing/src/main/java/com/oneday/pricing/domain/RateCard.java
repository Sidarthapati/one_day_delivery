package com.oneday.pricing.domain;

import com.oneday.common.domain.MutableBaseEntity;
import com.oneday.common.domain.enums.CustomerType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * A versioned rate card. B2C/C2C have one ACTIVE published card each; every B2B account references
 * its own card by id (see {@code b2b_accounts.rate_card_id}). Superseding a card never mutates an
 * existing row — a new version is inserted ACTIVE and the prior one flipped to SUPERSEDED, so a
 * shipment booked earlier always re-prices against the version snapshot stored on it (M2-D-002).
 */
@Entity
@Table(name = "rate_card")
@Getter
@Setter
@NoArgsConstructor
public class RateCard extends MutableBaseEntity {

    /** Human-readable identifier, e.g. "B2C-PUBLISHED" or an account code. */
    @Column(name = "code", length = 50, nullable = false)
    private String code;

    @Enumerated(EnumType.STRING)
    @Column(name = "customer_type", length = 10, nullable = false)
    private CustomerType customerType;

    @Column(name = "version", length = 50, nullable = false)
    private String version;

    /** ACTIVE or SUPERSEDED. */
    @Column(name = "status", length = 20, nullable = false)
    private String status;

    @Column(name = "effective_from", nullable = false)
    private Instant effectiveFrom;

    @Column(name = "effective_to")
    private Instant effectiveTo;

    @Column(name = "currency", length = 3, nullable = false)
    private String currency;

    /** Weight slab size in grams (0.5 kg = 500). */
    @Column(name = "slab_grams", nullable = false)
    private int slabGrams;

    /** Divisor for volumetric weight (L×W×H / divisor). Informational; M4 computes chargeable weight. */
    @Column(name = "volumetric_divisor", nullable = false)
    private int volumetricDivisor;

    /** Percentage of the base price charged for the first slab (100). */
    @Column(name = "first_slab_pct", nullable = false)
    private int firstSlabPct;

    /** Percentage-point decrement applied to each additional slab (10). */
    @Column(name = "slab_decrement_pct", nullable = false)
    private int slabDecrementPct;

    /** Floor percentage that additional slabs decay toward (60). */
    @Column(name = "slab_floor_pct", nullable = false)
    private int slabFloorPct;

    /** Negotiated discount in basis points applied to freight (B2B). 0 = no discount. */
    @Column(name = "discount_bps", nullable = false)
    private int discountBps;

    /** GST in basis points (1800 = 18%). */
    @Column(name = "gst_bps", nullable = false)
    private int gstBps;

    /** COD charge percentage of declared value, in basis points (150 = 1.5%). */
    @Column(name = "cod_pct_bps", nullable = false)
    private int codPctBps;

    /** Minimum COD charge in paise (5000 = ₹50). */
    @Column(name = "cod_min_paise", nullable = false)
    private long codMinPaise;

    /** Base price (paise) for the first slab of a same-city shipment (not in the published sheet). */
    @Column(name = "same_city_base_price_paise", nullable = false)
    private long sameCityBasePricePaise;
}
