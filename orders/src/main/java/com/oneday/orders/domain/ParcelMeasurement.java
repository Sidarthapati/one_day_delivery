package com.oneday.orders.domain;

import com.oneday.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * One append-only observation of a parcel's dimensions (see {@code V4_35}). Never mutated after
 * insert. The declared dimensions are snapshotted here so the row is self-contained evidence for a
 * dispute; the customer's declaration on {@code Shipment} is never changed.
 */
@Entity
@Table(name = "parcel_measurement")
@Getter
@Setter
@NoArgsConstructor
public class ParcelMeasurement extends BaseEntity {

    @Column(name = "shipment_id", nullable = false, updatable = false)
    private UUID shipmentId;

    @Column(name = "shipment_ref", length = 30, nullable = false, updatable = false)
    private String shipmentRef;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", length = 20, nullable = false, updatable = false)
    private MeasurementSource source;

    @Enumerated(EnumType.STRING)
    @Column(name = "method", length = 20, nullable = false, updatable = false)
    private MeasurementMethod method;

    /** OK / NO_MARKER / LOW_CONFIDENCE / TIMEOUT / ENGINE_UNAVAILABLE / BAD_INPUT / ERROR. */
    @Column(name = "status", length = 30, nullable = false, updatable = false)
    private String status;

    @Column(name = "length_cm", updatable = false)
    private Double lengthCm;

    @Column(name = "width_cm", updatable = false)
    private Double widthCm;

    @Column(name = "height_cm", updatable = false)
    private Double heightCm;

    @Column(name = "volumetric_weight_grams", updatable = false)
    private Integer volumetricWeightGrams;

    @Column(name = "confidence", updatable = false)
    private Float confidence;

    @Column(name = "declared_length_cm", updatable = false)
    private Short declaredLengthCm;

    @Column(name = "declared_width_cm", updatable = false)
    private Short declaredWidthCm;

    @Column(name = "declared_height_cm", updatable = false)
    private Short declaredHeightCm;

    @Column(name = "over_declared", nullable = false, updatable = false)
    private boolean overDeclared;

    @Column(name = "discrepancy_detail", length = 300, updatable = false)
    private String discrepancyDetail;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "evidence_keys", nullable = false, updatable = false)
    private List<String> evidenceKeys = new ArrayList<>();

    @Column(name = "measured_by", updatable = false)
    private UUID measuredBy;
}
