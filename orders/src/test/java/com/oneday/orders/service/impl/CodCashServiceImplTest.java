package com.oneday.orders.service.impl;

import com.oneday.auth.dto.response.UserResponse;
import com.oneday.auth.exception.UserNotFoundException;
import com.oneday.auth.service.UserService;
import com.oneday.orders.domain.CodCashDeposit;
import com.oneday.orders.domain.CodCashDepositState;
import com.oneday.orders.domain.DaCodBalance;
import com.oneday.orders.dto.AdminDaCashRow;
import com.oneday.orders.dto.CodCashDepositResponse;
import com.oneday.orders.dto.RecordCodDepositRequest;
import com.oneday.orders.domain.DaCodLedgerType;
import com.oneday.orders.repository.CodCashDepositRepository;
import com.oneday.orders.repository.CodCollectionRepository;
import com.oneday.orders.repository.DaCodBalanceRepository;
import com.oneday.orders.service.CodLedgerService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Guards the COD deposit dedup fix (vuln-0012 — a repeated (DA, deposit_ref) submit is idempotent) and
 * the station DA-cash view + city-scoping (#191).
 */
@ExtendWith(MockitoExtension.class)
class CodCashServiceImplTest {

    @Mock private CodCashDepositRepository deposits;
    @Mock private CodCollectionRepository collections;
    @Mock private CodLedgerService codLedger;
    @Mock private DaCodBalanceRepository balances;
    @Mock private UserService userService;

    private CodCashServiceImpl service() {
        return new CodCashServiceImpl(deposits, collections, codLedger, balances, userService);
    }

    private static DaCodBalance balance(UUID da, long paise) {
        DaCodBalance b = new DaCodBalance();
        b.setDaUserId(da);
        b.setCashInHandPaise(paise);
        return b;
    }

    private static UserResponse user(UUID id, String city) {
        return new UserResponse(id, id + "@1dd.in", "DA " + city, "DELIVERY_ASSOCIATE", city, true);
    }

    @Test
    void recordDeposit_duplicateRef_returnsExistingAndDoesNotInsert() {
        UUID da = UUID.randomUUID();
        CodCashDeposit existing = new CodCashDeposit();
        ReflectionTestUtils.setField(existing, "id", UUID.randomUUID());
        existing.setDaUserId(da);
        existing.setAmountPaise(500L);
        existing.setDepositRef("REF-1");
        existing.setStatus(CodCashDepositState.DEPOSITED);
        when(deposits.findByDaUserIdAndDepositRef(da, "REF-1")).thenReturn(Optional.of(existing));

        CodCashDepositResponse resp =
                service().recordDeposit(da, new RecordCodDepositRequest(500L, "REF-1", null));

        assertThat(resp.id()).isEqualTo(existing.getId());
        verify(deposits, never()).save(any());
        verify(deposits, never()).saveAndFlush(any());
        // An idempotent repeat must NOT post to the ledger again (no double debit of cash-in-hand).
        verify(codLedger, never()).post(any(), any(), org.mockito.ArgumentMatchers.anyLong(),
                any(), any(), any());
    }

    @Test
    void recordDeposit_newRef_inserts() {
        UUID da = UUID.randomUUID();
        when(deposits.findByDaUserIdAndDepositRef(da, "REF-2")).thenReturn(Optional.empty());
        when(deposits.saveAndFlush(any(CodCashDeposit.class))).thenAnswer(inv -> inv.getArgument(0));

        CodCashDepositResponse resp =
                service().recordDeposit(da, new RecordCodDepositRequest(700L, "REF-2", "note"));

        assertThat(resp.amountPaise()).isEqualTo(700L);
        verify(deposits).saveAndFlush(any(CodCashDeposit.class));
        // A new deposit posts a negative (DEPOSIT) movement to the DA's cash-in-hand ledger.
        verify(codLedger).post(eq(da), eq(DaCodLedgerType.DEPOSIT), eq(-700L), eq("REF-2"), any(), eq(da));
    }

    // ── #191 station DA-cash view + city scoping ──────────────────────────────────

    @Test
    void daCashBalances_enrichesAndHonoursCityFilter() {
        UUID blrDa = UUID.randomUUID();
        UUID delDa = UUID.randomUUID();
        // Repo already returns most-holding-first; the service preserves that order.
        when(balances.findAllByOrderByCashInHandPaiseDesc())
                .thenReturn(List.of(balance(blrDa, 50000L), balance(delDa, 30000L)));
        when(userService.getUser(blrDa)).thenReturn(user(blrDa, "BLR"));
        lenient().when(userService.getUser(delDa)).thenReturn(user(delDa, "DEL"));
        lenient().when(deposits.lastDepositAt(any())).thenReturn(null);

        // A BLR-scoped manager sees only the BLR rider.
        List<AdminDaCashRow> scoped = service().daCashBalances("BLR");
        assertThat(scoped).extracting(AdminDaCashRow::daUserId).containsExactly(blrDa);
        assertThat(scoped.get(0).cashInHandPaise()).isEqualTo(50000L);
        assertThat(scoped.get(0).daEmail()).isEqualTo(blrDa + "@1dd.in");

        // Admin (null filter) sees both, still ordered by cash desc.
        List<AdminDaCashRow> all = service().daCashBalances(null);
        assertThat(all).extracting(AdminDaCashRow::daUserId).containsExactly(blrDa, delDa);
    }

    @Test
    void daCashBalances_scopedManager_doesNotSeeUnresolvableDa() {
        UUID ghostDa = UUID.randomUUID();
        when(balances.findAllByOrderByCashInHandPaiseDesc()).thenReturn(List.of(balance(ghostDa, 10000L)));
        when(userService.getUser(ghostDa)).thenThrow(new UserNotFoundException("gone"));

        // Fail-closed: a rider whose city can't be resolved is hidden from a city-scoped manager…
        assertThat(service().daCashBalances("BLR")).isEmpty();
    }

    @Test
    void managerDaLedger_crossCity_forbidden_butAdminAndSameCityPass() {
        UUID da = UUID.randomUUID();
        when(userService.getUser(da)).thenReturn(user(da, "BLR"));
        lenient().when(codLedger.history(eq(da), any())).thenReturn(List.of());
        var page = PageRequest.of(0, 50);

        // A DEL manager reading a BLR rider → 403.
        assertThatThrownBy(() -> service().managerDaLedger(da, page, "DEL"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("your city");

        // Same-city manager and admin (null filter) both pass through to the ledger.
        service().managerDaLedger(da, page, "BLR");
        service().managerDaLedger(da, page, null);
        verify(codLedger, org.mockito.Mockito.times(2)).history(eq(da), any());
    }

    @Test
    void reconcile_crossCityManager_forbidden() {
        UUID da = UUID.randomUUID();
        UUID depositId = UUID.randomUUID();
        CodCashDeposit d = new CodCashDeposit();
        d.setDaUserId(da);
        when(deposits.findById(depositId)).thenReturn(Optional.of(d));
        when(userService.getUser(da)).thenReturn(user(da, "BLR"));

        assertThatThrownBy(() -> service().reconcile(depositId, UUID.randomUUID(), true, null, "DEL"))
                .isInstanceOf(ResponseStatusException.class);
        // The reconcile must be rejected BEFORE the verdict is applied — status stays DEPOSITED, not
        // flipped to RECONCILED/DISCREPANCY, and nothing is persisted.
        assertThat(d.getStatus()).isEqualTo(CodCashDepositState.DEPOSITED);
        verify(deposits, never()).save(any());
    }

    @Test
    void reconciliation_rowCarriesLedgerCashInHand() {
        UUID da = UUID.randomUUID();
        when(collections.findDasWithCollectedCash()).thenReturn(List.of(da));
        when(deposits.findDistinctDaIds()).thenReturn(List.of());
        when(collections.sumCollectedByDa(da)).thenReturn(4300L);
        when(collections.countCollectedByDa(da)).thenReturn(2L);
        when(deposits.sumDepositedByDa(da)).thenReturn(4000L);
        when(codLedger.cashInHand(da)).thenReturn(300L);
        when(userService.getUser(da)).thenReturn(user(da, "BLR"));

        var rows = service().reconciliation(null); // admin: every city
        assertThat(rows).singleElement().satisfies(r -> {
            assertThat(r.variancePaise()).isEqualTo(300L);       // collected − deposited
            assertThat(r.cashInHandPaise()).isEqualTo(300L);     // authoritative ledger balance
            assertThat(r.daName()).isEqualTo("DA BLR");
        });
    }

    @Test
    void reconciliation_scopedManager_hidesOtherCities() {
        UUID blr = UUID.randomUUID();
        UUID del = UUID.randomUUID();
        when(collections.findDasWithCollectedCash()).thenReturn(List.of(blr, del));
        when(deposits.findDistinctDaIds()).thenReturn(List.of());
        when(collections.sumCollectedByDa(any())).thenReturn(1000L);
        when(collections.countCollectedByDa(any())).thenReturn(1L);
        when(deposits.sumDepositedByDa(any())).thenReturn(0L);
        when(codLedger.cashInHand(any())).thenReturn(1000L);
        when(userService.getUser(blr)).thenReturn(user(blr, "BLR"));
        when(userService.getUser(del)).thenReturn(user(del, "DEL"));

        var rows = service().reconciliation("BLR"); // manager scoped to BLR sees only BLR's rider
        assertThat(rows).singleElement()
                .satisfies(r -> assertThat(r.daUserId()).isEqualTo(blr));
    }

    @Test
    void allDeposits_scopedManager_hidesOtherCities() {
        UUID blr = UUID.randomUUID();
        UUID del = UUID.randomUUID();
        CodCashDeposit inCity = new CodCashDeposit();
        inCity.setDaUserId(blr);
        CodCashDeposit otherCity = new CodCashDeposit();
        otherCity.setDaUserId(del);
        when(deposits.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(inCity, otherCity));
        when(userService.getUser(blr)).thenReturn(user(blr, "BLR"));
        when(userService.getUser(del)).thenReturn(user(del, "DEL"));

        var rows = service().allDeposits("BLR"); // manager scoped to BLR
        assertThat(rows).singleElement()
                .satisfies(r -> assertThat(r.daUserId()).isEqualTo(blr));
    }
}
