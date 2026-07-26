package com.oneday.orders.service.impl;

import com.oneday.orders.domain.B2bAccount;
import com.oneday.orders.dto.B2bAccountResponse;
import com.oneday.orders.repository.B2bAccountRepository;
import com.oneday.orders.service.B2bAccountService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@Service
class B2bAccountServiceImpl implements B2bAccountService {

    private final B2bAccountRepository accounts;

    B2bAccountServiceImpl(B2bAccountRepository accounts) {
        this.accounts = accounts;
    }

    @Override
    @Transactional(readOnly = true)
    public B2bAccountResponse getMine(UUID ownerUserId) {
        B2bAccount a = accounts.findByOwnerUserId(ownerUserId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No business account for this user"));
        long available = Math.max(0L, a.getCreditLimitPaise() - a.getOutstandingBalancePaise());
        return new B2bAccountResponse(
                a.getId(),
                a.getAccountName(),
                a.getVerificationStatus().name(),
                Boolean.TRUE.equals(a.getIsActive()),
                a.getGstin(),
                Boolean.TRUE.equals(a.getGstinVerified()),
                Boolean.TRUE.equals(a.getPanVerified()),
                Boolean.TRUE.equals(a.getBankVerified()),
                a.getBusinessType(),
                a.getBillingEmail(),
                a.getCityId(),
                a.getCreditLimitPaise(),
                a.getOutstandingBalancePaise(),
                available,
                a.getPaymentTermsDays(),
                a.getRejectionReason());
    }
}
