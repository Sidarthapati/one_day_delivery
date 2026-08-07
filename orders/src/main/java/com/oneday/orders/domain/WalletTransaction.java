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
 * One append-only movement on a B2B account's prepaid wallet. The running balance is stored on
 * {@code b2b_accounts.wallet_balance_paise} for cheap gating; this ledger makes the balance fully
 * reconstructable. Never mutated after insert, so it does not extend {@code MutableBaseEntity}.
 */
@Entity
@Table(name = "wallet_transaction")
@Getter
@Setter
@NoArgsConstructor
public class WalletTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "b2b_account_id", nullable = false, updatable = false)
    private UUID b2bAccountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", length = 30, nullable = false, updatable = false)
    private WalletTransactionType type;

    /** Signed: positive credits the wallet, negative debits it. */
    @Column(name = "amount_paise", nullable = false, updatable = false)
    private Long amountPaise;

    @Column(name = "balance_after_paise", nullable = false, updatable = false)
    private Long balanceAfterPaise;

    /** Shipment ref / razorpay payment id / remittance ref, for reconciliation. */
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
