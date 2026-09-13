package com.oneday.auth.service;

import com.oneday.auth.domain.DaOnboardingCandidate;
import com.oneday.auth.dto.response.BgvCheckResponse;

import java.util.List;
import java.util.UUID;

/**
 * Runs the per-check background verification for an onboarding candidate behind the vendor-neutral
 * {@code BgvPort} (mock today). Initiation happens on submit; checks are advanced to a verdict by the
 * poll job or an on-demand reviewer refresh, which then moves the candidate into the approval queue.
 */
public interface BgvService {

    /** Open the BGV case for a just-submitted candidate: create IN_PROGRESS checks + move to BGV_IN_PROGRESS. */
    void initiateForCandidate(DaOnboardingCandidate candidate);

    /** Poll this candidate's in-flight checks now; advance the candidate if all checks have resolved. */
    List<BgvCheckResponse> pollCandidate(UUID candidateId);

    List<BgvCheckResponse> listByCandidate(UUID candidateId);

    /** Poll every candidate with in-flight checks (the scheduled job entry point). */
    void pollAllPending();
}
