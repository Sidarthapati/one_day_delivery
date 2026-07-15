package com.oneday.airline.domain;

import com.oneday.common.domain.MutableBaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

/**
 * One parcel that was in a sealed bag, linked to the AWB it was booked on, with its proportional
 * share of the AWB's total cost (§6, §10 — split by weight, not a flat even split).
 */
@Entity
@Table(name = "awb_parcel")
@Getter
@Setter
@NoArgsConstructor
public class AwbParcel extends MutableBaseEntity {

    @Column(name = "awb_id", nullable = false)
    private UUID awbId;

    @Column(name = "parcel_id", nullable = false)
    private UUID parcelId;

    @Column(name = "shipment_ref", length = 30, nullable = false)
    private String shipmentRef;

    @Column(name = "weight_grams", nullable = false)
    private int weightGrams;

    @Column(name = "allocated_cost_paise", nullable = false)
    private long allocatedCostPaise;
}
