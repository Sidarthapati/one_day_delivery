package com.oneday.auth.service;

import com.oneday.auth.dto.request.OnboardingSubmitRequest;
import com.oneday.auth.dto.response.OnboardingRequestResponse;

import java.util.List;
import java.util.UUID;

public interface OnboardingService {
    OnboardingRequestResponse submit(OnboardingSubmitRequest request);
    List<OnboardingRequestResponse> listAll();
    void approve(UUID requestId, UUID actorId);
    void reject(UUID requestId, String reason, UUID actorId);
}
