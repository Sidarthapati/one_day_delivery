package com.oneday.orders.service.impl;

import com.oneday.common.port.B2bProvisioningPort;
import com.oneday.common.port.dto.B2bProvisioningRequest;
import com.oneday.orders.domain.B2bAccount;
import com.oneday.orders.domain.B2bAccountMember;
import com.oneday.orders.domain.B2bVerificationStatus;
import com.oneday.orders.domain.MemberRole;
import com.oneday.orders.repository.B2bAccountMemberRepository;
import com.oneday.orders.repository.B2bAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * M4's implementation of {@link B2bProvisioningPort}: creates the {@code B2bAccount} when M1
 * approves a business onboarding. Idempotent on {@code ownerUserId}. The account lands ACTIVE when
 * both GSTIN and PAN verified during onboarding, otherwise MANUAL_REVIEW (ADMIN activates later).
 */
@Service
class B2bProvisioningAdapter implements B2bProvisioningPort {

    private static final Logger log = LoggerFactory.getLogger(B2bProvisioningAdapter.class);

    private final B2bAccountRepository accounts;
    private final B2bAccountMemberRepository members;

    B2bProvisioningAdapter(B2bAccountRepository accounts, B2bAccountMemberRepository members) {
        this.accounts = accounts;
        this.members = members;
    }

    @Override
    @Transactional
    public UUID provisionAccount(B2bProvisioningRequest r) {
        var existing = accounts.findByOwnerUserId(r.ownerUserId());
        if (existing.isPresent()) {
            log.info("B2B account already provisioned for owner {} -> {}", r.ownerUserId(), existing.get().getId());
            return existing.get().getId();
        }

        boolean kycClean = r.gstinVerified() && r.panVerified();
        var status = kycClean ? B2bVerificationStatus.ACTIVE : B2bVerificationStatus.MANUAL_REVIEW;

        var a = new B2bAccount();
        a.setAccountName(r.companyName());
        a.setBillingEmail(r.billingEmail());
        a.setGstin(r.gstin());
        a.setPan(r.pan());
        a.setBusinessType(r.businessType());
        a.setCityId(r.cityId());
        a.setCreditLimitPaise(r.creditLimitPaise());
        a.setOutstandingBalancePaise(0L);
        a.setPaymentTermsDays(r.paymentTermsDays());
        a.setOwnerUserId(r.ownerUserId());
        a.setGstinVerified(r.gstinVerified());
        a.setPanVerified(r.panVerified());
        a.setKycSubmittedAt(Instant.now());
        a.setVerificationStatus(status);
        a.setIsActive(kycClean);
        if (kycClean) {
            a.setActivatedAt(Instant.now());
        }
        a = accounts.save(a);

        // The owner is the account's first member, so membership-based resolution works from creation.
        B2bAccountMember owner = new B2bAccountMember();
        owner.setB2bAccountId(a.getId());
        owner.setUserId(r.ownerUserId());
        owner.setRole(MemberRole.OWNER);
        owner.setEmail(r.billingEmail());
        members.save(owner);

        log.info("Provisioned B2B account {} for owner {} (status={})", a.getId(), r.ownerUserId(), status);
        return a.getId();
    }
}
