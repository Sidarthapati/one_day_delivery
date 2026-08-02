package com.oneday.orders.service.impl;

import com.oneday.orders.domain.B2bAccount;
import com.oneday.orders.dto.BrandingRequest;
import com.oneday.orders.dto.TrackBranding;
import com.oneday.orders.repository.B2bAccountRepository;
import com.oneday.orders.service.BrandingService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@Service
class BrandingServiceImpl implements BrandingService {

    private final B2bAccountRepository accounts;

    BrandingServiceImpl(B2bAccountRepository accounts) {
        this.accounts = accounts;
    }

    @Override
    @Transactional(readOnly = true)
    public TrackBranding get(UUID accountId) {
        return TrackBranding.from(require(accountId));
    }

    @Override
    @Transactional
    public TrackBranding update(UUID accountId, BrandingRequest r) {
        B2bAccount a = accounts.findByIdForUpdate(accountId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found"));
        a.setBrandName(trim(r.getBrandName()));
        a.setBrandColor(trim(r.getBrandColor()));
        a.setBrandLogoUrl(trim(r.getBrandLogoUrl()));
        a.setSupportEmail(trim(r.getSupportEmail()));
        a.setSupportPhone(trim(r.getSupportPhone()));
        return TrackBranding.from(accounts.save(a));
    }

    private B2bAccount require(UUID accountId) {
        return accounts.findById(accountId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found"));
    }

    private static String trim(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
