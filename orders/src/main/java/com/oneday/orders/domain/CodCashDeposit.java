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
 * A delivery associate's declared cash deposit — the COD cash they collected on deliveries, handed
 * in to the bank/hub. Admin reconciles it against the DA's expected holdings (Σ COLLECTED COD
 * attributed to that DA). See {@link CodCashDepositState} for the lifecycle.
 */
@Entity
@Table(name = "cod_cash_deposit")
@Getter
@Setter
@NoArgsConstructor
public class CodCashDeposit extends BaseEntity {

    @Column(name = "da_user_id", nullable = false, updatable = false)
    private UUID daUserId;

    @Column(name = "amount_paise", nullable = false, updatable = false)
    private Long amountPaise;

    /** Bank slip / deposit reference the DA recorded. */
    @Column(name = "deposit_ref", length = 80)
    private String depositRef;

    @Column(name = "note", length = 300)
    private String note;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private CodCashDepositState status = CodCashDepositState.DEPOSITED;

    @Column(name = "reconciled_by")
    private UUID reconciledBy;

    @Column(name = "reconciled_at")
    private Instant reconciledAt;
}
