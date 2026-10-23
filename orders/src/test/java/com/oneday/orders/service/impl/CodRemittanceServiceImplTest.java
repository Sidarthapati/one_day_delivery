package com.oneday.orders.service.impl;

import com.oneday.orders.config.CodProperties;
import com.oneday.orders.domain.B2bAccount;
import com.oneday.orders.domain.BankVerificationState;
import com.oneday.orders.domain.CodCollection;
import com.oneday.orders.domain.CodCollectionState;
import com.oneday.orders.domain.CodRemittance;
import com.oneday.orders.domain.CodRemittanceState;
import com.oneday.orders.domain.DaCodLedgerType;
import com.oneday.orders.repository.B2bAccountRepository;
import com.oneday.orders.repository.CodCollectionRepository;
import com.oneday.orders.repository.CodRemittanceRepository;
import com.oneday.orders.service.CodLedgerService;
import com.oneday.orders.service.Notifier;
import com.oneday.orders.service.PayoutPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CodRemittanceServiceImplTest {

    @Mock CodCollectionRepository collections;
    @Mock CodRemittanceRepository remittances;
    @Mock B2bAccountRepository accounts;
    @Mock CodProperties props;
    @Mock PayoutPort payouts;
    @Mock Notifier notifier;
    @Mock CodLedgerService codLedger;
    @InjectMocks CodRemittanceServiceImpl service;

    private final UUID accountId = UUID.randomUUID();

    private static CodCollection coll(CodCollectionState state, long amount, Instant collectedAt) {
        CodCollection c = new CodCollection();
        ReflectionTestUtils.setField(c, "id", UUID.randomUUID());
        c.setShipmentRef("REF");
        c.setAmountPaise(amount);
        c.setState(state);
        c.setCollectedAt(collectedAt);
        return c;
    }

    private static CodRemittance rem(CodRemittanceState state) {
        CodRemittance r = new CodRemittance();
        ReflectionTestUtils.setField(r, "id", UUID.randomUUID());
        r.setReference("RMT/1");
        r.setB2bAccountId(UUID.randomUUID());
        r.setGrossPaise(1000L);
        r.setFeePaise(50L);
        r.setNetPaise(950L);
        r.setCollectionCount(1);
        r.setState(state);
        return r;
    }

    private static B2bAccount payableAccount() {
        B2bAccount a = new B2bAccount();
        a.setAccountName("Acme");
        a.setBankVerificationState(BankVerificationState.VERIFIED);
        a.setBankAccountNumber("123456789");
        a.setBankIfsc("HDFC0001234");
        a.setBankBeneficiaryName("Acme");
        return a;
    }

    // ── lifecycle ──
    @Test
    void onDelivered_collectsAndPostsLedger_whenDaPresent() {
        UUID sid = UUID.randomUUID();
        UUID da = UUID.randomUUID();
        CodCollection c = coll(CodCollectionState.AWAITING_COLLECTION, 500L, null);
        when(collections.findByShipmentId(sid)).thenReturn(Optional.of(c));
        service.onDelivered(sid, da);
        assertThat(c.getState()).isEqualTo(CodCollectionState.COLLECTED);
        verify(codLedger).post(eq(da), eq(DaCodLedgerType.COLLECTION), eq(500L), anyString(), anyString(), eq(da));
    }

    @Test
    void onDelivered_hubSelfCollect_skipsLedger() {
        UUID sid = UUID.randomUUID();
        CodCollection c = coll(CodCollectionState.AWAITING_COLLECTION, 500L, null);
        when(collections.findByShipmentId(sid)).thenReturn(Optional.of(c));
        service.onDelivered(sid, null);
        assertThat(c.getState()).isEqualTo(CodCollectionState.COLLECTED);
        verify(codLedger, never()).post(any(), any(), anyLong(), any(), any(), any());
    }

    @Test
    void onDelivered_notAwaiting_noop() {
        UUID sid = UUID.randomUUID();
        when(collections.findByShipmentId(sid)).thenReturn(Optional.of(
                coll(CodCollectionState.COLLECTED, 500L, Instant.now())));
        service.onDelivered(sid, UUID.randomUUID());
        verify(collections, never()).save(any());
    }

    @Test
    void onCancelled_awaiting_cancels_elseNoop() {
        UUID sid = UUID.randomUUID();
        CodCollection c = coll(CodCollectionState.AWAITING_COLLECTION, 500L, null);
        when(collections.findByShipmentId(sid)).thenReturn(Optional.of(c));
        service.onCancelled(sid);
        assertThat(c.getState()).isEqualTo(CodCollectionState.CANCELLED);

        UUID sid2 = UUID.randomUUID();
        when(collections.findByShipmentId(sid2)).thenReturn(Optional.of(
                coll(CodCollectionState.COLLECTED, 1L, Instant.now())));
        service.onCancelled(sid2); // no state change for non-awaiting
    }

    // ── reads ──
    @Test
    void summaryFor_aggregates() {
        when(collections.sumByAccountAndState(accountId, CodCollectionState.AWAITING_COLLECTION)).thenReturn(100L);
        when(collections.sumRemittable(accountId)).thenReturn(40L);
        when(collections.sumByAccountAndState(accountId, CodCollectionState.COLLECTED)).thenReturn(70L);
        when(collections.sumByAccountAndState(accountId, CodCollectionState.REMITTED)).thenReturn(10L);
        when(collections.countByB2bAccountIdAndState(accountId, CodCollectionState.AWAITING_COLLECTION)).thenReturn(2);
        when(collections.findRemittable(accountId)).thenReturn(List.of(coll(CodCollectionState.COLLECTED, 40L, Instant.now())));
        assertThat(service.summaryFor(accountId)).isNotNull();
    }

    @Test
    void collectionsFor_bothBranches() {
        when(collections.findByB2bAccountIdOrderByCreatedAtDesc(accountId)).thenReturn(List.of());
        assertThat(service.collectionsFor(accountId, null)).isEmpty();
        when(collections.findByB2bAccountIdAndStateOrderByCreatedAtDesc(accountId, CodCollectionState.COLLECTED))
                .thenReturn(List.of(coll(CodCollectionState.COLLECTED, 1L, Instant.now())));
        assertThat(service.collectionsFor(accountId, CodCollectionState.COLLECTED)).hasSize(1);
    }

    @Test
    void remittancesFor_and_detail_and_notFound() {
        when(remittances.findByB2bAccountIdOrderByCreatedAtDesc(accountId)).thenReturn(List.of(rem(CodRemittanceState.PENDING)));
        assertThat(service.remittancesFor(accountId)).hasSize(1);

        CodRemittance r = rem(CodRemittanceState.PAID);
        when(remittances.findById(r.getId())).thenReturn(Optional.of(r));
        when(collections.findByRemittanceIdOrderByCreatedAtDesc(r.getId())).thenReturn(List.of());
        assertThat(service.remittanceDetail(r.getId())).isNotNull();

        UUID missing = UUID.randomUUID();
        when(remittances.findById(missing)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.remittanceDetail(missing)).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void accountsWithBalance_mapsAndSorts_nameMaybeNull() {
        UUID a1 = UUID.randomUUID();
        UUID a2 = UUID.randomUUID();
        when(collections.findAccountsWithRemittableBalance()).thenReturn(List.of(a1, a2));
        when(accounts.findById(a1)).thenReturn(Optional.of(payableAccount()));
        when(accounts.findById(a2)).thenReturn(Optional.empty()); // name null branch
        when(collections.sumRemittable(a1)).thenReturn(100L);
        when(collections.sumRemittable(a2)).thenReturn(500L);
        when(collections.findRemittable(a1)).thenReturn(List.of());
        when(collections.findRemittable(a2)).thenReturn(List.of());
        var out = service.accountsWithBalance();
        assertThat(out).hasSize(2);
        assertThat(out.get(0).availableToRemitPaise()).isEqualTo(500L); // sorted desc
    }

    @Test
    void allRemittances_branches_and_badState() {
        when(remittances.findAllByOrderByCreatedAtDesc()).thenReturn(List.of());
        assertThat(service.allRemittances(null)).isEmpty();
        assertThat(service.allRemittances("  ")).isEmpty();
        when(remittances.findByStateOrderByCreatedAtDesc(CodRemittanceState.PAID))
                .thenReturn(List.of(rem(CodRemittanceState.PAID)));
        assertThat(service.allRemittances("paid")).hasSize(1);
        assertThatThrownBy(() -> service.allRemittances("nope")).isInstanceOf(ResponseStatusException.class);
    }

    // ── createRemittance ──
    @Test
    void createRemittance_happyPath() {
        when(accounts.findByIdForUpdate(accountId)).thenReturn(Optional.of(payableAccount()));
        when(collections.findRemittable(accountId)).thenReturn(List.of(
                coll(CodCollectionState.COLLECTED, 600L, Instant.now()),
                coll(CodCollectionState.COLLECTED, 400L, null))); // one null collectedAt → period filter
        when(props.feeOn(1000L)).thenReturn(50L);
        when(remittances.nextRemittanceSequence()).thenReturn(1L);
        when(remittances.save(any())).thenAnswer(i -> {
            CodRemittance r = i.getArgument(0);
            ReflectionTestUtils.setField(r, "id", UUID.randomUUID());
            return r;
        });
        assertThat(service.createRemittance(accountId, UUID.randomUUID())).isNotNull();
        verify(collections, org.mockito.Mockito.times(2)).save(any());
    }

    @Test
    void createRemittance_accountNotFound_is404() {
        when(accounts.findByIdForUpdate(accountId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.createRemittance(accountId, UUID.randomUUID()))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void createRemittance_noBank_is409() {
        when(accounts.findByIdForUpdate(accountId)).thenReturn(Optional.of(new B2bAccount())); // null bank state
        assertThatThrownBy(() -> service.createRemittance(accountId, UUID.randomUUID()))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void createRemittance_emptyBatch_is409() {
        when(accounts.findByIdForUpdate(accountId)).thenReturn(Optional.of(payableAccount()));
        when(collections.findRemittable(accountId)).thenReturn(List.of());
        assertThatThrownBy(() -> service.createRemittance(accountId, UUID.randomUUID()))
                .isInstanceOf(ResponseStatusException.class);
    }

    // ── markPaid ──
    @Test
    void markPaid_happy_notifiesWhenAccountPresent() {
        CodRemittance r = rem(CodRemittanceState.PENDING);
        when(remittances.findByIdForUpdate(r.getId())).thenReturn(Optional.of(r));
        when(remittances.save(any())).thenAnswer(i -> i.getArgument(0));
        CodCollection c = coll(CodCollectionState.COLLECTED, 950L, Instant.now());
        when(collections.findByRemittanceIdOrderByCreatedAtDesc(r.getId())).thenReturn(List.of(c));
        when(accounts.findById(r.getB2bAccountId())).thenReturn(Optional.of(payableAccount()));
        service.markPaid(r.getId(), "UTR1");
        assertThat(r.getState()).isEqualTo(CodRemittanceState.PAID);
        assertThat(c.getState()).isEqualTo(CodCollectionState.REMITTED);
        verify(notifier).remittancePaid(any(), eq("RMT/1"), eq(950L), eq("UTR1"));
    }

    @Test
    void markPaid_notFound_is404() {
        UUID id = UUID.randomUUID();
        when(remittances.findByIdForUpdate(id)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.markPaid(id, "U")).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void markPaid_notPending_is409() {
        CodRemittance r = rem(CodRemittanceState.PAID);
        when(remittances.findByIdForUpdate(r.getId())).thenReturn(Optional.of(r));
        assertThatThrownBy(() -> service.markPaid(r.getId(), "U")).isInstanceOf(ResponseStatusException.class);
    }

    // ── payout ──
    @Test
    void payout_settled_delegatesToMarkPaid() {
        CodRemittance r = rem(CodRemittanceState.PENDING);
        when(remittances.findByIdForUpdate(r.getId())).thenReturn(Optional.of(r));
        when(accounts.findById(r.getB2bAccountId())).thenReturn(Optional.of(payableAccount()));
        when(payouts.createPayout(any())).thenReturn(new PayoutPort.PayoutResult(true, "UTR9", "pref", "ok"));
        when(remittances.save(any())).thenAnswer(i -> i.getArgument(0));
        when(collections.findByRemittanceIdOrderByCreatedAtDesc(r.getId())).thenReturn(List.of());
        service.payout(r.getId());
        assertThat(r.getState()).isEqualTo(CodRemittanceState.PAID);
        assertThat(r.getUtr()).isEqualTo("UTR9");
    }

    @Test
    void payout_notSettled_is409() {
        CodRemittance r = rem(CodRemittanceState.PENDING);
        when(remittances.findByIdForUpdate(r.getId())).thenReturn(Optional.of(r));
        when(accounts.findById(r.getB2bAccountId())).thenReturn(Optional.of(payableAccount()));
        when(payouts.createPayout(any())).thenReturn(new PayoutPort.PayoutResult(false, null, "pref", "queued"));
        assertThatThrownBy(() -> service.payout(r.getId())).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void payout_notPending_is409() {
        CodRemittance r = rem(CodRemittanceState.PAID);
        when(remittances.findByIdForUpdate(r.getId())).thenReturn(Optional.of(r));
        assertThatThrownBy(() -> service.payout(r.getId())).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void payout_accountGone_is404_and_bankUnverified_is409() {
        CodRemittance r = rem(CodRemittanceState.PENDING);
        when(remittances.findByIdForUpdate(r.getId())).thenReturn(Optional.of(r));
        when(accounts.findById(r.getB2bAccountId())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.payout(r.getId())).isInstanceOf(ResponseStatusException.class);

        CodRemittance r2 = rem(CodRemittanceState.PENDING);
        when(remittances.findByIdForUpdate(r2.getId())).thenReturn(Optional.of(r2));
        when(accounts.findById(r2.getB2bAccountId())).thenReturn(Optional.of(new B2bAccount())); // no bank
        assertThatThrownBy(() -> service.payout(r2.getId())).isInstanceOf(ResponseStatusException.class);
    }
}
