package com.oneday.auth.service;

import com.oneday.auth.domain.OnboardingStatus;
import com.oneday.auth.dto.request.CandidateBankRequest;
import com.oneday.auth.dto.request.CandidateLocationRequest;
import com.oneday.auth.dto.request.CandidatePersonalRequest;
import com.oneday.auth.dto.request.DaOnboardingInviteRequest;
import com.oneday.auth.dto.response.CandidateResponse;
import com.oneday.auth.dto.response.DaResponse;
import com.oneday.auth.dto.response.OnboardingFunnelResponse;
import com.oneday.auth.dto.response.OnboardingInviteResponse;

import java.util.List;
import java.util.UUID;

/**
 * Self-service DA onboarding funnel: an admin/station-manager opens an invite, the applicant completes a
 * multi-step wizard by token, and a reviewer approves — provisioning the candidate into a real DA.
 */
public interface DaOnboardingService {

    // ── Admin ────────────────────────────────────────────────────────────────────
    OnboardingInviteResponse createInvite(DaOnboardingInviteRequest request, UUID actorId);

    List<CandidateResponse> listCandidates(OnboardingStatus status);

    /** Stage counts + conversion rate for the funnel dashboard. */
    OnboardingFunnelResponse funnel();

    CandidateResponse getCandidate(UUID candidateId);

    /** Re-issue the invite link for a candidate that hasn't completed (not ONBOARDED/REJECTED). */
    OnboardingInviteResponse resendInvite(UUID candidateId);

    /** Approve a PENDING_APPROVAL candidate → provision a real DA. Returns the created DA (with temp password). */
    DaResponse approve(UUID candidateId, UUID actorId);

    CandidateResponse reject(UUID candidateId, String reason, UUID actorId);

    // ── Applicant (by invite token) ───────────────────────────────────────────────
    CandidateResponse getByToken(String inviteToken);

    CandidateResponse updatePersonal(String inviteToken, CandidatePersonalRequest request);

    CandidateResponse updateLocation(String inviteToken, CandidateLocationRequest request);

    CandidateResponse updateBank(String inviteToken, CandidateBankRequest request);

    /** Click-to-accept the e-agreement (records the version + timestamp). */
    CandidateResponse acceptAgreement(String inviteToken, String version);

    /** Acknowledge the training videos were watched. */
    CandidateResponse acknowledgeTraining(String inviteToken);

    /** Applicant submits the completed wizard → BGV, then the pre-approval queue. */
    CandidateResponse submit(String inviteToken);
}
