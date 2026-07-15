package com.oneday.airline.domain;

import com.oneday.common.domain.MutableBaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * The standard cost table for a lane (§10): General Cargo Rate (GCR) style — a per-kg rate that
 * steps down as chargeable weight crosses a break, a flat minimum charge, and a fixed terminal
 * handling fee added on top regardless of weight. Mirrors M2's {@code RateCard} versioning pattern:
 * never mutated in place — a rate change ships as a new ACTIVE version.
 */
@Entity
@Table(name = "lane_rate_card")
@Getter
@Setter
@NoArgsConstructor
public class LaneRateCard extends MutableBaseEntity {

    @Column(name = "origin_hub", length = 10, nullable = false)
    private String originHub;

    @Column(name = "dest_hub", length = 10, nullable = false)
    private String destHub;

    @Column(name = "version", length = 50, nullable = false)
    private String version;

    /** ACTIVE or SUPERSEDED. */
    @Column(name = "status", length = 20, nullable = false)
    private String status;

    @Column(name = "min_charge_paise", nullable = false)
    private long minChargePaise;

    @Column(name = "terminal_handling_paise", nullable = false)
    private long terminalHandlingPaise;

    @Column(name = "rate_below_45kg_paise_per_kg", nullable = false)
    private long rateBelow45kgPaisePerKg;

    @Column(name = "rate_q45_paise_per_kg", nullable = false)
    private long rateQ45PaisePerKg;

    @Column(name = "rate_q100_paise_per_kg", nullable = false)
    private long rateQ100PaisePerKg;

    @Column(name = "rate_q300_paise_per_kg", nullable = false)
    private long rateQ300PaisePerKg;

    @Column(name = "rate_q500_paise_per_kg", nullable = false)
    private long rateQ500PaisePerKg;

    @Column(name = "rate_q1000_paise_per_kg", nullable = false)
    private long rateQ1000PaisePerKg;
}
