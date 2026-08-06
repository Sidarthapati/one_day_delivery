package com.oneday.auth.service;

import com.oneday.auth.dto.request.BusinessOnboardingRequest;
import com.oneday.auth.dto.request.OnboardingSubmitRequest;
import com.oneday.auth.dto.response.BusinessOnboardingResponse;
import com.oneday.auth.dto.response.OnboardingRequestResponse;

import java.util.List;
import java.util.UUID;

public interface OnboardingService {
    OnboardingRequestResponse submit(OnboardingSubmitRequest request);

    /** Business (B2B_USER) self-onboarding: runs KYC (GSTIN + PAN) and records the request PENDING. */
    BusinessOnboardingResponse submitBusiness(BusinessOnboardingRequest request);

    List<OnboardingRequestResponse> listAll();
    void approve(UUID requestId, UUID actorId);
    void reject(UUID requestId, String reason, UUID actorId);
}
