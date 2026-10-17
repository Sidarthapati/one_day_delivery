package com.oneday.auth.service.impl;

import com.oneday.auth.domain.DaBgvCheck;
import com.oneday.auth.domain.DaOnboardingCandidate;
import com.oneday.auth.domain.OnboardingStatus;
import com.oneday.auth.dto.response.BgvCheckResponse;
import com.oneday.auth.repository.DaBgvCheckRepository;
import com.oneday.auth.repository.DaOnboardingCandidateRepository;
import com.oneday.auth.service.BgvService;
import com.oneday.auth.service.OnboardingStateMachine;
import com.oneday.common.port.BgvPort;
import com.oneday.common.port.dto.bgv.BgvCheckResult;
import com.oneday.common.port.dto.bgv.BgvCheckStatus;
import com.oneday.common.port.dto.bgv.BgvCheckType;
import com.oneday.common.port.dto.bgv.BgvInitiateResult;
import com.oneday.common.port.dto.bgv.BgvSubject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
class BgvServiceImpl implements BgvService {

    private static final Logger LOG = LoggerFactory.getLogger(BgvServiceImpl.class);
    /** The full IDfy-style check set run for every candidate. */
    private static final List<BgvCheckType> CHECKS = List.of(BgvCheckType.values());

    private final BgvPort bgvPort;
    private final DaBgvCheckRepository checkRepository;
    private final DaOnboardingCandidateRepository candidateRepository;
    private final OnboardingStateMachine stateMachine;

    BgvServiceImpl(BgvPort bgvPort,
                   DaBgvCheckRepository checkRepository,
                   DaOnboardingCandidateRepository candidateRepository,
                   OnboardingStateMachine stateMachine) {
        this.bgvPort = bgvPort;
        this.checkRepository = checkRepository;
        this.candidateRepository = candidateRepository;
        this.stateMachine = stateMachine;
    }

    @Override
    @Transactional
    public void initiateForCandidate(DaOnboardingCandidate candidate) {
        BgvSubject subject = new BgvSubject(
                fullName(candidate), candidate.getPan(), candidate.getDrivingLicense(),
                candidate.getAadhaar(), candidate.getDob() == null ? null : candidate.getDob().toString(),
                candidate.getStationLabel());
        BgvInitiateResult init = bgvPort.initiate(subject, CHECKS);

        Instant now = Instant.now();
        for (BgvCheckType type : CHECKS) {
            var check = new DaBgvCheck();
            check.setCandidateId(candidate.getId());
            check.setCheckType(type);
            check.setStatus(BgvCheckStatus.IN_PROGRESS);
            check.setVendorRef(init.vendorRef());
            check.setInitiatedAt(now);
            checkRepository.save(check);
        }

        stateMachine.assertCanTransition(candidate.getStatus(), OnboardingStatus.BGV_IN_PROGRESS);
        candidate.setStatus(OnboardingStatus.BGV_IN_PROGRESS);
        LOG.info("Initiated BGV for candidate {} (ref {})", candidate.getId(), init.vendorRef());
    }

    @Override
    @Transactional
    public List<BgvCheckResponse> pollCandidate(UUID candidateId) {
        var checks = checkRepository.findByCandidateIdOrderByCheckType(candidateId);
        Instant now = Instant.now();
        for (DaBgvCheck check : checks) {
            if (check.getStatus().isTerminal()) continue;
            BgvCheckResult result = bgvPort.poll(check.getVendorRef(), check.getCheckType());
            check.setStatus(result.status());
            check.setMessage(result.message());
            if (result.status().isTerminal()) {
                check.setCompletedAt(now);
            }
            checkRepository.save(check);
        }
        maybeAdvanceCandidate(candidateId, checks);
        return toResponses(checkRepository.findByCandidateIdOrderByCheckType(candidateId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<BgvCheckResponse> listByCandidate(UUID candidateId) {
        return toResponses(checkRepository.findByCandidateIdOrderByCheckType(candidateId));
    }

    @Override
    @Transactional
    public void pollAllPending() {
        for (UUID candidateId : checkRepository.findCandidateIdsWithStatus(BgvCheckStatus.IN_PROGRESS)) {
            try {
                pollCandidate(candidateId);
            } catch (Exception e) {
                LOG.warn("BGV poll failed for candidate {}: {}", candidateId, e.getMessage());
            }
        }
    }

    /** Once every check has a verdict, move the candidate BGV_IN_PROGRESS → (BGV_CLEAR|BGV_INSUFFICIENT) → queue. */
    private void maybeAdvanceCandidate(UUID candidateId, List<DaBgvCheck> checks) {
        boolean allTerminal = checks.stream().allMatch(c -> c.getStatus().isTerminal());
        if (!allTerminal) return;

        var candidate = candidateRepository.findById(candidateId).orElse(null);
        if (candidate == null || candidate.getStatus() != OnboardingStatus.BGV_IN_PROGRESS) return;

        boolean allPass = checks.stream().allMatch(c -> c.getStatus().isPass());
        OnboardingStatus verdict = allPass ? OnboardingStatus.BGV_CLEAR : OnboardingStatus.BGV_INSUFFICIENT;
        stateMachine.assertCanTransition(candidate.getStatus(), verdict);
        candidate.setStatus(verdict);
        // Either verdict lands in the reviewer queue; the per-check cards inform the approve/reject call.
        stateMachine.assertCanTransition(candidate.getStatus(), OnboardingStatus.PENDING_APPROVAL);
        candidate.setStatus(OnboardingStatus.PENDING_APPROVAL);
        candidateRepository.save(candidate);
        LOG.info("BGV resolved for candidate {} → {} → PENDING_APPROVAL", candidateId, verdict);
    }

    private static String fullName(DaOnboardingCandidate c) {
        return ((c.getFirstName() == null ? "" : c.getFirstName()) + " "
                + (c.getLastName() == null ? "" : c.getLastName())).trim();
    }

    private static List<BgvCheckResponse> toResponses(List<DaBgvCheck> checks) {
        return checks.stream()
                .map(c -> new BgvCheckResponse(c.getId(), c.getCheckType(), c.getStatus(),
                        c.getMessage(), c.getInitiatedAt(), c.getCompletedAt()))
                .toList();
    }
}
