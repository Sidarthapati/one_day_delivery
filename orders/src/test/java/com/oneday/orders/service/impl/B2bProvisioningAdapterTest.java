package com.oneday.orders.service.impl;

import com.oneday.common.port.dto.B2bProvisioningRequest;
import com.oneday.orders.domain.B2bAccount;
import com.oneday.orders.domain.B2bAccountMember;
import com.oneday.orders.domain.B2bVerificationStatus;
import com.oneday.orders.domain.MemberKycStatus;
import com.oneday.orders.domain.MemberRole;
import com.oneday.orders.repository.B2bAccountMemberRepository;
import com.oneday.orders.repository.B2bAccountRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class B2bProvisioningAdapterTest {

    @Mock B2bAccountRepository accounts;
    @Mock B2bAccountMemberRepository members;
    @InjectMocks B2bProvisioningAdapter adapter;

    private static B2bProvisioningRequest req(UUID owner, boolean gstinOk, boolean panOk) {
        return new B2bProvisioningRequest(owner, "Acme", "LLP", "GST1", "PAN1",
                "a@acme.com", "BLR", gstinOk, panOk, 5000000L, (short) 30);
    }

    @Test
    void provision_existing_isIdempotent() {
        UUID owner = UUID.randomUUID();
        B2bAccount existing = new B2bAccount();
        ReflectionTestUtils.setField(existing, "id", UUID.randomUUID());
        when(accounts.findByOwnerUserId(owner)).thenReturn(Optional.of(existing));

        assertThat(adapter.provisionAccount(req(owner, true, true))).isEqualTo(existing.getId());
        verify(members, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void provision_cleanKyc_activatesAndVerifiesOwner() {
        UUID owner = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        when(accounts.findByOwnerUserId(owner)).thenReturn(Optional.empty());
        when(accounts.save(any())).thenAnswer(i -> {
            B2bAccount a = i.getArgument(0);
            ReflectionTestUtils.setField(a, "id", accountId);
            return a;
        });

        UUID result = adapter.provisionAccount(req(owner, true, true));

        assertThat(result).isEqualTo(accountId);
        ArgumentCaptor<B2bAccount> acc = ArgumentCaptor.forClass(B2bAccount.class);
        verify(accounts).save(acc.capture());
        assertThat(acc.getValue().getVerificationStatus()).isEqualTo(B2bVerificationStatus.ACTIVE);
        assertThat(acc.getValue().getIsActive()).isTrue();
        assertThat(acc.getValue().getActivatedAt()).isNotNull();

        ArgumentCaptor<B2bAccountMember> mem = ArgumentCaptor.forClass(B2bAccountMember.class);
        verify(members).save(mem.capture());
        assertThat(mem.getValue().getRole()).isEqualTo(MemberRole.OWNER);
        assertThat(mem.getValue().getKycStatus()).isEqualTo(MemberKycStatus.VERIFIED);
        assertThat(mem.getValue().getB2bAccountId()).isEqualTo(accountId);
    }

    @Test
    void provision_dirtyKyc_goesToManualReview_ownerUnverified() {
        UUID owner = UUID.randomUUID();
        when(accounts.findByOwnerUserId(owner)).thenReturn(Optional.empty());
        when(accounts.save(any())).thenAnswer(i -> i.getArgument(0));

        adapter.provisionAccount(req(owner, true, false)); // pan not verified → not clean

        ArgumentCaptor<B2bAccount> acc = ArgumentCaptor.forClass(B2bAccount.class);
        verify(accounts).save(acc.capture());
        assertThat(acc.getValue().getVerificationStatus()).isEqualTo(B2bVerificationStatus.MANUAL_REVIEW);
        assertThat(acc.getValue().getIsActive()).isFalse();
        assertThat(acc.getValue().getActivatedAt()).isNull();

        ArgumentCaptor<B2bAccountMember> mem = ArgumentCaptor.forClass(B2bAccountMember.class);
        verify(members).save(mem.capture());
        assertThat(mem.getValue().getKycStatus()).isEqualTo(MemberKycStatus.UNVERIFIED);
    }
}
