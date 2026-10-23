package com.oneday.orders.service.impl;

import com.oneday.orders.domain.B2bAccount;
import com.oneday.orders.domain.BankVerificationState;
import com.oneday.orders.dto.BankAccountRequest;
import com.oneday.orders.dto.BankAccountResponse;
import com.oneday.orders.repository.B2bAccountRepository;
import com.oneday.orders.service.PayoutPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BankAccountServiceImplTest {

    @Mock B2bAccountRepository accounts;
    @Mock PayoutPort payouts;
    @InjectMocks BankAccountServiceImpl service;

    private static BankAccountRequest req(String acctNo) {
        return new BankAccountRequest(acctNo, "hdfc0001234", "Acme Corp", "HDFC", "ops@acme.com");
    }

    @Test
    void get_notFound_is404() {
        UUID id = UUID.randomUUID();
        when(accounts.findById(id)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.get(id)).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void get_noBankYet_returnsNone() {
        UUID id = UUID.randomUUID();
        when(accounts.findById(id)).thenReturn(Optional.of(new B2bAccount())); // masked null, state null→NONE
        BankAccountResponse r = service.get(id);
        assertThat(r.onFile()).isFalse();
        assertThat(r.verificationState()).isEqualTo("NONE");
    }

    @Test
    void submit_verified_marksPayableWithTimestamp_andMasks() {
        UUID id = UUID.randomUUID();
        B2bAccount a = new B2bAccount();
        a.setAccountName("Acme Corp");
        when(accounts.findByIdForUpdate(id)).thenReturn(Optional.of(a));
        when(payouts.verifyBankAccount(any(), eq("Acme Corp"))).thenReturn(
                new PayoutPort.VerificationOutcome(BankVerificationState.VERIFIED, "pd_1", "ok"));
        when(accounts.save(any())).thenAnswer(i -> i.getArgument(0));

        BankAccountResponse r = service.submit(id, req("123456789 0")); // space stripped → 10 digits
        assertThat(r.payable()).isTrue();
        assertThat(r.verificationState()).isEqualTo("VERIFIED");
        assertThat(r.accountMasked()).isEqualTo("XXXXXX7890");
        assertThat(a.getBankVerifiedAt()).isNotNull();
    }

    @Test
    void submit_failed_notPayable_noTimestamp() {
        UUID id = UUID.randomUUID();
        B2bAccount a = new B2bAccount();
        a.setAccountName("Acme Corp");
        when(accounts.findByIdForUpdate(id)).thenReturn(Optional.of(a));
        when(payouts.verifyBankAccount(any(), any())).thenReturn(
                new PayoutPort.VerificationOutcome(BankVerificationState.FAILED, null, "bounced"));
        when(accounts.save(any())).thenAnswer(i -> i.getArgument(0));

        BankAccountResponse r = service.submit(id, req("12")); // short → mask returns as-is
        assertThat(r.payable()).isFalse();
        assertThat(r.accountMasked()).isEqualTo("12");
        assertThat(a.getBankVerifiedAt()).isNull();
    }

    @Test
    void submit_notFound_is404() {
        UUID id = UUID.randomUUID();
        when(accounts.findByIdForUpdate(id)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.submit(id, req("123456789")))
                .isInstanceOf(ResponseStatusException.class);
    }
}
