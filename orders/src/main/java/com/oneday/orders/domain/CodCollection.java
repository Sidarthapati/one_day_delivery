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

import java.time.Instant;
import java.util.UUID;

/**
 * COD owed to a vendor for one shipment — the goods' value we collect from the buyer on delivery
 * and later remit. One row per COD shipment; see {@link CodCollectionState} for the lifecycle.
 */
@Entity
@Table(name = "cod_collection")
@Getter
@Setter
@NoArgsConstructor
public class CodCollection extends BaseEntity {

    @Column(name = "shipment_id", nullable = false, updatable = false, unique = true)
    private UUID shipmentId;

    @Column(name = "shipment_ref", length = 30, nullable = false, updatable = false)
    private String shipmentRef;

    @Column(name = "b2b_account_id", nullable = false, updatable = false)
    private UUID b2bAccountId;

    @Column(name = "amount_paise", nullable = false, updatable = false)
    private Long amountPaise;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", length = 20, nullable = false)
    private CodCollectionState state = CodCollectionState.AWAITING_COLLECTION;

    @Column(name = "collected_at")
    private Instant collectedAt;

    // Set when the collection is assigned to a remittance batch.
    @Column(name = "remittance_id")
    private UUID remittanceId;

    // The delivery associate who collected the cash (transition actor); null for hub-collect / old rows.
    @Column(name = "collected_by_da_id")
    private UUID collectedByDaId;
}
