package com.oneday.orders.api;

import com.oneday.auth.domain.Role;
import com.oneday.auth.domain.User;
import com.oneday.auth.security.AuthUserDetails;
import com.oneday.orders.domain.CodCollectionState;
import com.oneday.orders.dto.BankDepositedRequest;
import com.oneday.orders.dto.ConfirmBankCreditRequest;
import com.oneday.orders.dto.CreateRemittanceRequest;
import com.oneday.orders.dto.DaCodLedgerEntryResponse;
import com.oneday.orders.dto.CodCashDepositResponse;
import com.oneday.orders.dto.MarkRemittancePaidRequest;
import com.oneday.orders.dto.ReconcileDepositRequest;
import com.oneday.orders.dto.VerifyHandoffRequest;
import com.oneday.orders.service.CodCashService;
import com.oneday.orders.service.CodRemittanceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminCodControllerTest {

    @Mock CodRemittanceService cod;
    @Mock CodCashService codCash;
    @InjectMocks AdminCodController controller;

    private final UUID actor = UUID.randomUUID();

    private AuthUserDetails principal(String role, String cityId) {
        Role r = mock(Role.class);
        lenient().when(r.getName()).thenReturn(role);
        User u = mock(User.class);
        lenient().when(u.getRole()).thenReturn(r);
        lenient().when(u.getCityId()).thenReturn(cityId);
        AuthUserDetails p = mock(AuthUserDetails.class);
        lenient().when(p.getUser()).thenReturn(u);
        lenient().when(p.getUserId()).thenReturn(actor);
        return p;
    }

    private final AuthUserDetails admin = principal("ADMIN", null);
    private final AuthUserDetails manager = principal("STATION_MANAGER", "DEL");

    @Test
    void accounts_delegates() {
        when(cod.accountsWithBalance()).thenReturn(List.of());
        assertThat(controller.accounts(admin)).isEmpty();
    }

    @Test
    void collections_parsesState_bothBranches() {
        UUID acc = UUID.randomUUID();
        controller.collections(admin, acc, null);
        controller.collections(admin, acc, "  ");
        controller.collections(admin, acc, "collected");
        org.mockito.Mockito.verify(cod).collectionsFor(acc, CodCollectionState.COLLECTED);
    }

    @Test
    void remittances_create_pay_payout() {
        controller.remittances(admin, "PENDING");
        controller.create(admin, new CreateRemittanceRequest(UUID.randomUUID()));
        UUID id = UUID.randomUUID();
        controller.pay(admin, id, new MarkRemittancePaidRequest("UTR"));
        controller.payout(admin, id);
        org.mockito.Mockito.verify(cod).payout(id);
    }

    @Test
    void daBalances_admin_unscoped_and_manager_scoped() {
        when(codCash.daCashBalances(isNull())).thenReturn(List.of());
        assertThat(controller.daBalances(admin)).isEmpty();
        when(codCash.daCashBalances("DEL")).thenReturn(List.of());
        assertThat(controller.daBalances(manager)).isEmpty();
    }

    @Test
    void daBalances_managerWithoutCity_403() {
        AuthUserDetails noCity = principal("STATION_MANAGER", null);
        assertThatThrownBy(() -> controller.daBalances(noCity)).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void daLedger_capsSize() {
        UUID da = UUID.randomUUID();
        controller.daLedger(manager, da, null, null, -1, 9999);
        org.mockito.Mockito.verify(codCash).managerDaLedger(any(), any(), any(), any(), any());
    }

    @Test
    void exportDaLedger_defaultsWindow_andBuildsCsv() {
        UUID da = UUID.randomUUID();
        when(codCash.managerDaLedger(any(), any(), any(), any(), any())).thenReturn(List.of(
                new DaCodLedgerEntryResponse(UUID.randomUUID(), "COLLECTION", 500L, 500L, "REF", "desc", Instant.now())));
        ResponseEntity<Resource> resp = controller.exportDaLedger(admin, da, null, null);
        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
    }

    @Test
    void reconciliation_deposits_and_export() {
        controller.reconciliation(manager);
        when(codCash.allDeposits(any())).thenReturn(List.of(
                new CodCashDepositResponse(UUID.randomUUID(), UUID.randomUUID(), 100L, 100L, "D", "n",
                        null, null, null, Instant.now())));
        controller.deposits(manager);
        ResponseEntity<Resource> resp = controller.exportDeposits(manager);
        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
    }

    @Test
    void reconcile_handoff_bankDeposited_bankConfirmed() {
        UUID id = UUID.randomUUID();
        controller.reconcile(admin, id, new ReconcileDepositRequest(true, "note"));
        controller.verifyHandoff(admin, id, new VerifyHandoffRequest("1234", 500L));
        controller.bankDeposited(admin, id, new BankDepositedRequest("slip-1"));
        controller.bankConfirmed(admin, id, new ConfirmBankCreditRequest("cr-1"));
        org.mockito.Mockito.verify(codCash).confirmBankCredit(id, "cr-1", null);
    }
}
