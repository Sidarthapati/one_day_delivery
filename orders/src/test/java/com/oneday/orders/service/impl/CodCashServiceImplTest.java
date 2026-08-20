package com.oneday.orders.service.impl;

import com.oneday.orders.domain.CodCashDeposit;
import com.oneday.orders.domain.CodCashDepositState;
import com.oneday.orders.dto.CodCashDepositResponse;
import com.oneday.orders.dto.RecordCodDepositRequest;
import com.oneday.orders.repository.CodCashDepositRepository;
import com.oneday.orders.repository.CodCollectionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Guards the COD deposit dedup fix (vuln-0012): a repeated (DA, deposit_ref) submit is idempotent. */
@ExtendWith(MockitoExtension.class)
class CodCashServiceImplTest {

    @Mock private CodCashDepositRepository deposits;
    @Mock private CodCollectionRepository collections;

    private CodCashServiceImpl service() {
        return new CodCashServiceImpl(deposits, collections);
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
    }
}
