package com.oneday.orders.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * One append-only movement on a delivery associate's COD cash-in-hand ledger. The running balance is
 * stored on {@link DaCodBalance} for cheap gating; this ledger makes it fully reconstructable. Never
 * mutated after insert, so it does not extend {@code MutableBaseEntity}. Mirrors {@code WalletTransaction}.
 */
@Entity
@Table(name = "da_cod_ledger")
@Getter
@Setter
@NoArgsConstructor
public class DaCodLedgerEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "da_user_id", nullable = false, updatable = false)
    private UUID daUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", length = 20, nullable = false, updatable = false)
    private DaCodLedgerType type;

    /** Signed: positive increases cash-in-hand (a collection), negative decreases it (a deposit). */
    @Column(name = "amount_paise", nullable = false, updatable = false)
    private long amountPaise;

    @Column(name = "balance_after_paise", nullable = false, updatable = false)
    private long balanceAfterPaise;

    /** Shipment ref / deposit ref, for reconciliation. */
    @Column(name = "reference", length = 80, updatable = false)
    private String reference;

    @Column(name = "description", length = 300, updatable = false)
    private String description;

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false, nullable = false)
    private Instant createdAt;
}
