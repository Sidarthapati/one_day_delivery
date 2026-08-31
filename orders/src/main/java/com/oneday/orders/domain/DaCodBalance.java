package com.oneday.orders.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * A delivery associate's running COD cash-in-hand — the cash they've collected on delivery but not yet
 * deposited. The lockable balance row (PK = the DA's user id) that serialises concurrent ledger
 * postings, mirroring {@code b2b_accounts.wallet_balance_paise} for the wallet. The
 * {@link DaCodLedgerEntry} history makes it fully reconstructable.
 */
@Entity
@Table(name = "da_cod_balance")
@Getter
@Setter
@NoArgsConstructor
public class DaCodBalance {

    @Id
    @Column(name = "da_user_id", nullable = false, updatable = false)
    private UUID daUserId;

    @Column(name = "cash_in_hand_paise", nullable = false)
    private long cashInHandPaise;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
