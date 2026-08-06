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
 * A COD payout batch to one vendor: gross (Σ collected) − fee = net, transferred to the vendor's
 * bank. PENDING until the transfer is confirmed with a UTR, then PAID.
 */
@Entity
@Table(name = "cod_remittance")
@Getter
@Setter
@NoArgsConstructor
public class CodRemittance extends BaseEntity {

    @Column(name = "reference", length = 30, nullable = false, updatable = false, unique = true)
    private String reference;

    @Column(name = "b2b_account_id", nullable = false, updatable = false)
    private UUID b2bAccountId;

    @Column(name = "gross_paise", nullable = false)
    private Long grossPaise;

    @Column(name = "fee_paise", nullable = false)
    private Long feePaise;

    @Column(name = "net_paise", nullable = false)
    private Long netPaise;

    @Column(name = "collection_count", nullable = false)
    private Integer collectionCount;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", length = 20, nullable = false)
    private CodRemittanceState state = CodRemittanceState.PENDING;

    @Column(name = "utr", length = 50)
    private String utr;

    @Column(name = "period_start")
    private Instant periodStart;

    @Column(name = "period_end")
    private Instant periodEnd;

    @Column(name = "notes", length = 500)
    private String notes;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "paid_at")
    private Instant paidAt;
}
