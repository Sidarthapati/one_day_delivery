package com.oneday.orders.service.impl;

import com.oneday.orders.domain.DaCodBalance;
import com.oneday.orders.domain.DaCodLedgerEntry;
import com.oneday.orders.domain.DaCodLedgerType;
import com.oneday.orders.repository.DaCodBalanceRepository;
import com.oneday.orders.repository.DaCodLedgerRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The ledger's one load-bearing guarantee: each posting locks the DA's balance, applies the signed
 * amount, and stamps the resulting running balance on the entry. A collection then a deposit must land
 * at the right cash-in-hand.
 */
@ExtendWith(MockitoExtension.class)
class CodLedgerServiceImplTest {

    @Mock private DaCodBalanceRepository balances;
    @Mock private DaCodLedgerRepository ledger;

    private final UUID da = UUID.randomUUID();

    /** A tiny in-memory balance row keyed by DA, so post() reads back what it wrote (like the DB would). */
    private void wireBalanceStore() {
        Map<UUID, DaCodBalance> store = new HashMap<>();
        when(balances.findByIdForUpdate(any())).thenAnswer(inv -> {
            UUID id = inv.getArgument(0);
            return Optional.of(store.computeIfAbsent(id, k -> {
                DaCodBalance b = new DaCodBalance();
                b.setDaUserId(k);
                return b;
            }));
        });
        when(balances.save(any(DaCodBalance.class))).thenAnswer(inv -> {
            DaCodBalance b = inv.getArgument(0);
            store.put(b.getDaUserId(), b);
            return b;
        });
    }

    @Test
    void collectionThenDeposit_runningBalanceIsCorrect() {
        wireBalanceStore();
        CodLedgerServiceImpl svc = new CodLedgerServiceImpl(balances, ledger);

        long afterCollect = svc.post(da, DaCodLedgerType.COLLECTION, 1_000L, "1DD-1", "collected", da);
        long afterDeposit = svc.post(da, DaCodLedgerType.DEPOSIT, -400L, "DEP-1", "deposited", da);

        assertThat(afterCollect).isEqualTo(1_000L);   // holds ₹10
        assertThat(afterDeposit).isEqualTo(600L);      // handed ₹4 back → ₹6 left in hand
        verify(balances, org.mockito.Mockito.times(2)).ensureRow(da);

        ArgumentCaptor<DaCodLedgerEntry> entries = ArgumentCaptor.forClass(DaCodLedgerEntry.class);
        verify(ledger, org.mockito.Mockito.times(2)).save(entries.capture());
        assertThat(entries.getAllValues().get(0).getBalanceAfterPaise()).isEqualTo(1_000L);
        assertThat(entries.getAllValues().get(1).getBalanceAfterPaise()).isEqualTo(600L);
        assertThat(entries.getAllValues().get(1).getAmountPaise()).isEqualTo(-400L);
    }
}
