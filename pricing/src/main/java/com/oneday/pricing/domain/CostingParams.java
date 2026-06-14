package com.oneday.pricing.domain;

import com.oneday.common.domain.MutableBaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Versioned per-city ops costing inputs that feed the internal per-parcel cost floor (M2-D-004).
 * Internal only — never exposed to customers. One ACTIVE row per city.
 */
@Entity
@Table(name = "costing_params")
@Getter
@Setter
@NoArgsConstructor
public class CostingParams extends MutableBaseEntity {

    @Column(name = "city", length = 10, nullable = false)
    private String city;

    @Column(name = "version", length = 50, nullable = false)
    private String version;

    /** ACTIVE or SUPERSEDED. */
    @Column(name = "status", length = 20, nullable = false)
    private String status;

    /** Fully-loaded DA cost for one shift, in paise. */
    @Column(name = "da_cost_per_shift_paise", nullable = false)
    private long daCostPerShiftPaise;

    @Column(name = "shift_hours", nullable = false)
    private double shiftHours;

    /** Target productive share of the shift (~70). The cost floor divides capacity by this. */
    @Column(name = "utilisation_pct", nullable = false)
    private int utilisationPct;

    /** Parcels a DA handles per shift at 100% utilisation (nameplate capacity). */
    @Column(name = "avg_parcels_per_shift", nullable = false)
    private int avgParcelsPerShift;

    @Column(name = "van_cost_per_run_paise", nullable = false)
    private long vanCostPerRunPaise;

    @Column(name = "avg_parcels_per_van_run", nullable = false)
    private int avgParcelsPerVanRun;

    @Column(name = "hub_cost_per_parcel_paise", nullable = false)
    private long hubCostPerParcelPaise;

    @Column(name = "airline_cost_per_parcel_paise", nullable = false)
    private long airlineCostPerParcelPaise;
}
