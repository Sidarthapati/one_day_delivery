package com.oneday.auth.service.impl;

import com.oneday.auth.domain.DaBgvCheck;
import com.oneday.auth.domain.DaOnboardingCandidate;
import com.oneday.auth.domain.OnboardingStatus;
import com.oneday.auth.repository.DaBgvCheckRepository;
import com.oneday.auth.repository.DaOnboardingCandidateRepository;
import com.oneday.auth.service.OnboardingStateMachine;
import com.oneday.common.port.BgvPort;
import com.oneday.common.port.dto.bgv.BgvCheckResult;
import com.oneday.common.port.dto.bgv.BgvCheckStatus;
import com.oneday.common.port.dto.bgv.BgvCheckType;
import com.oneday.common.port.dto.bgv.BgvInitiateResult;
import com.oneday.common.port.dto.bgv.BgvSubject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BgvServiceImplTest {

    private BgvPort bgvPort;
    private DaBgvCheckRepository checkRepo;
    private DaOnboardingCandidateRepository candidateRepo;
    private BgvServiceImpl service;

    @BeforeEach
    void setUp() {
        bgvPort = mock(BgvPort.class);
        checkRepo = mock(DaBgvCheckRepository.class);
        candidateRepo = mock(DaOnboardingCandidateRepository.class);
        when(checkRepo.save(any(DaBgvCheck.class))).thenAnswer(i -> i.getArgument(0));
        service = new BgvServiceImpl(bgvPort, checkRepo, candidateRepo, new OnboardingStateMachine());
    }

    @Test
    void initiate_createsSixChecks_andMovesCandidateInProgress() {
        var candidate = submittedCandidate();
        when(bgvPort.initiate(any(BgvSubject.class), anyList()))
                .thenReturn(new BgvInitiateResult("mock-G-ref"));

        service.initiateForCandidate(candidate);

        verify(checkRepo, times(6)).save(any(DaBgvCheck.class));
        assertThat(candidate.getStatus()).isEqualTo(OnboardingStatus.BGV_IN_PROGRESS);
    }

    @Test
    void poll_allGreen_advancesCandidateToPendingApproval() {
        UUID candidateId = UUID.randomUUID();
        var checks = inProgressChecks("mock-G-ref");
        when(checkRepo.findByCandidateIdOrderByCheckType(candidateId)).thenReturn(checks);
        when(bgvPort.poll(any(), any(BgvCheckType.class)))
                .thenReturn(new BgvCheckResult(BgvCheckStatus.GREEN, "Clear"));
        var candidate = inProgressCandidate();
        when(candidateRepo.findById(candidateId)).thenReturn(Optional.of(candidate));

        service.pollCandidate(candidateId);

        assertThat(checks).allMatch(c -> c.getStatus() == BgvCheckStatus.GREEN);
        assertThat(candidate.getStatus()).isEqualTo(OnboardingStatus.PENDING_APPROVAL);
    }

    @Test
    void poll_withRed_stillAdvancesToPendingApproval_forReviewer() {
        UUID candidateId = UUID.randomUUID();
        var checks = inProgressChecks("mock-R-ref");
        when(checkRepo.findByCandidateIdOrderByCheckType(candidateId)).thenReturn(checks);
        when(bgvPort.poll(any(), any(BgvCheckType.class)))
                .thenReturn(new BgvCheckResult(BgvCheckStatus.RED, "Adverse"));
        var candidate = inProgressCandidate();
        when(candidateRepo.findById(candidateId)).thenReturn(Optional.of(candidate));

        service.pollCandidate(candidateId);

        assertThat(candidate.getStatus()).isEqualTo(OnboardingStatus.PENDING_APPROVAL);
    }

    private static DaOnboardingCandidate submittedCandidate() {
        var c = new DaOnboardingCandidate();
        c.setFirstName("Riya");
        c.setLastName("Kumar");
        c.setPan("ABCDE1234F");
        c.setStatus(OnboardingStatus.SUBMITTED);
        return c;
    }

    private static DaOnboardingCandidate inProgressCandidate() {
        var c = new DaOnboardingCandidate();
        c.setStatus(OnboardingStatus.BGV_IN_PROGRESS);
        return c;
    }

    private static List<DaBgvCheck> inProgressChecks(String ref) {
        var list = new ArrayList<DaBgvCheck>();
        for (BgvCheckType t : BgvCheckType.values()) {
            var c = new DaBgvCheck();
            c.setCheckType(t);
            c.setStatus(BgvCheckStatus.IN_PROGRESS);
            c.setVendorRef(ref);
            list.add(c);
        }
        return list;
    }
}
