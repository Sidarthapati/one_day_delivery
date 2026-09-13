package com.oneday.auth.service.impl;

import com.oneday.auth.domain.DaOnboardingCandidate;
import com.oneday.auth.domain.DaProfile;
import com.oneday.auth.domain.OnboardingStatus;
import com.oneday.auth.dto.request.CandidateLocationRequest;
import com.oneday.auth.dto.request.CandidatePersonalRequest;
import com.oneday.auth.dto.request.DaOnboardingInviteRequest;
import com.oneday.auth.dto.request.RegisterDaRequest;
import com.oneday.auth.dto.response.DaResponse;
import com.oneday.auth.exception.EmailAlreadyExistsException;
import com.oneday.auth.exception.OnboardingValidationException;
import com.oneday.auth.config.BgvProperties;
import com.oneday.auth.repository.DaOnboardingCandidateRepository;
import com.oneday.auth.repository.DaProfileRepository;
import com.oneday.auth.repository.UserRepository;
import com.oneday.auth.service.BgvService;
import com.oneday.auth.service.DaRegistrationService;
import com.oneday.auth.service.OnboardingStateMachine;
import com.oneday.common.domain.Shift;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the DA onboarding funnel service — invite guarding, submit validation, the
 * editable-only-in-DRAFT guard, and the approve → provision path (real DA created + employee id
 * stamped + candidate ONBOARDED). Repositories + the DA-registration seam are mocked; the real
 * {@link OnboardingStateMachine} is used so transition legality is exercised end to end.
 */
class DaOnboardingServiceImplTest {

    private DaOnboardingCandidateRepository candidateRepo;
    private DaProfileRepository daProfileRepo;
    private UserRepository userRepo;
    private DaRegistrationService daRegistration;
    private BgvService bgvService;
    private BgvProperties bgvProps;
    private DaOnboardingServiceImpl service;

    @BeforeEach
    void setUp() {
        candidateRepo = mock(DaOnboardingCandidateRepository.class);
        daProfileRepo = mock(DaProfileRepository.class);
        userRepo = mock(UserRepository.class);
        daRegistration = mock(DaRegistrationService.class);
        bgvService = mock(BgvService.class);
        bgvProps = new BgvProperties();   // enabled=true by default
        // The real BgvService moves the candidate into BGV_IN_PROGRESS; simulate that here.
        org.mockito.Mockito.doAnswer(inv -> {
            ((DaOnboardingCandidate) inv.getArgument(0)).setStatus(OnboardingStatus.BGV_IN_PROGRESS);
            return null;
        }).when(bgvService).initiateForCandidate(any(DaOnboardingCandidate.class));
        when(candidateRepo.save(any(DaOnboardingCandidate.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        service = new DaOnboardingServiceImpl(candidateRepo, daProfileRepo, userRepo,
                daRegistration, new OnboardingStateMachine(), bgvService, bgvProps,
                "http://localhost:3000/onboarding");
    }

    @Test
    void invite_rejectsExistingEmail() {
        when(userRepo.existsByEmail("dupe@test.in")).thenReturn(true);
        assertThatThrownBy(() -> service.createInvite(
                new DaOnboardingInviteRequest("dupe@test.in", "A", "B", null, "DEL"), UUID.randomUUID()))
                .isInstanceOf(EmailAlreadyExistsException.class);
        verify(candidateRepo, never()).save(any());
    }

    @Test
    void invite_createsDraftWithTokenAndInviteUrl() {
        var res = service.createInvite(
                new DaOnboardingInviteRequest("New.Person@Test.in", "New", "Person", "+9199", "DEL"),
                UUID.randomUUID());
        assertThat(res.status()).isEqualTo(OnboardingStatus.DRAFT);
        assertThat(res.inviteToken()).isNotBlank();
        assertThat(res.inviteUrl()).isEqualTo("http://localhost:3000/onboarding/" + res.inviteToken());
    }

    @Test
    void submit_missingRequiredFields_throws422() {
        var c = draft();          // no city / shift set
        c.setFirstName("A");
        c.setLastName("B");
        when(candidateRepo.findByInviteToken("tok")).thenReturn(Optional.of(c));
        assertThatThrownBy(() -> service.submit("tok"))
                .isInstanceOf(OnboardingValidationException.class)
                .hasMessageContaining("cityId")
                .hasMessageContaining("shift");
        assertThat(c.getStatus()).isEqualTo(OnboardingStatus.DRAFT);
    }

    @Test
    void submit_complete_withBgvEnabled_movesToBgvInProgress() {
        var c = completeDraft();
        when(candidateRepo.findByInviteToken("tok")).thenReturn(Optional.of(c));

        var res = service.submit("tok");

        assertThat(res.status()).isEqualTo(OnboardingStatus.BGV_IN_PROGRESS);
        assertThat(c.getSubmittedAt()).isNotNull();
        verify(bgvService).initiateForCandidate(c);
    }

    @Test
    void submit_complete_withBgvDisabled_movesToPendingApproval() {
        bgvProps.setEnabled(false);
        var c = completeDraft();
        when(candidateRepo.findByInviteToken("tok")).thenReturn(Optional.of(c));

        var res = service.submit("tok");

        assertThat(res.status()).isEqualTo(OnboardingStatus.PENDING_APPROVAL);
        verify(bgvService, never()).initiateForCandidate(any());
    }

    @Test
    void submit_withoutAgreementOrTraining_throws422() {
        var c = draft();
        c.setFirstName("A");
        c.setLastName("B");
        c.setCityId("DEL");
        c.setShift(Shift.SHIFT_1);   // no agreement / training
        when(candidateRepo.findByInviteToken("tok")).thenReturn(Optional.of(c));
        assertThatThrownBy(() -> service.submit("tok"))
                .isInstanceOf(OnboardingValidationException.class)
                .hasMessageContaining("agreement")
                .hasMessageContaining("training");
    }

    @Test
    void acceptAgreementAndTraining_recordTimestamps() {
        var c = draft();
        when(candidateRepo.findByInviteToken("tok")).thenReturn(Optional.of(c));
        service.acceptAgreement("tok", null);
        service.acknowledgeTraining("tok");
        assertThat(c.getAgreementAcceptedAt()).isNotNull();
        assertThat(c.getAgreementDocKey()).isEqualTo("agreement:v1");
        assertThat(c.getTrainingAckAt()).isNotNull();
    }

    @Test
    void editAfterSubmit_isRejected() {
        var c = draft();
        c.setStatus(OnboardingStatus.PENDING_APPROVAL);
        when(candidateRepo.findByInviteToken("tok")).thenReturn(Optional.of(c));
        assertThatThrownBy(() -> service.updatePersonal("tok",
                new CandidatePersonalRequest("X", "Y", null, null, null, null, null)))
                .isInstanceOf(OnboardingValidationException.class);
    }

    @Test
    void updateLocation_savesCityShiftLabel() {
        var c = draft();
        when(candidateRepo.findByInviteToken("tok")).thenReturn(Optional.of(c));
        var res = service.updateLocation("tok",
                new CandidateLocationRequest("DEL", "Delhi North Hub", Shift.SHIFT_2));
        assertThat(res.cityId()).isEqualTo("DEL");
        assertThat(res.stationLabel()).isEqualTo("Delhi North Hub");
        assertThat(res.shift()).isEqualTo(Shift.SHIFT_2);
    }

    @Test
    void approve_provisionsDa_stampsEmployeeId_andOnboards() {
        var c = draft();
        c.setFirstName("Riya");
        c.setLastName("Kumar");
        c.setCityId("del");                 // lower-case → employee id upper-cases it
        c.setShift(Shift.SHIFT_1);
        c.setStatus(OnboardingStatus.PENDING_APPROVAL);
        UUID candidateId = UUID.randomUUID();
        UUID newDaId = UUID.randomUUID();
        UUID actor = UUID.randomUUID();

        when(candidateRepo.findById(candidateId)).thenReturn(Optional.of(c));
        when(daRegistration.register(any(RegisterDaRequest.class), any()))
                .thenReturn(new DaResponse(newDaId, "Riya Kumar", "riya@test.in", null, "del",
                        Shift.SHIFT_1, LocalDate.now(), null, true, "TempPass123"));
        var profile = new DaProfile();
        when(daProfileRepo.findById(newDaId)).thenReturn(Optional.of(profile));
        when(candidateRepo.nextEmployeeSeq()).thenReturn(1001L);

        DaResponse da = service.approve(candidateId, actor);

        assertThat(da.daId()).isEqualTo(newDaId);
        assertThat(da.tempPassword()).isEqualTo("TempPass123");
        assertThat(profile.getEmployeeId()).isEqualTo("GD-DEL-1001");
        assertThat(c.getStatus()).isEqualTo(OnboardingStatus.ONBOARDED);
        assertThat(c.getProvisionedUserId()).isEqualTo(newDaId);
        assertThat(c.getEmployeeId()).isEqualTo("GD-DEL-1001");
        assertThat(c.getApprovedBy()).isEqualTo(actor);
        verify(daRegistration).register(any(RegisterDaRequest.class), any());
    }

    @Test
    void approve_fromDraft_isIllegal() {
        var c = draft();                     // still DRAFT — cannot be approved
        UUID id = UUID.randomUUID();
        when(candidateRepo.findById(id)).thenReturn(Optional.of(c));
        assertThatThrownBy(() -> service.approve(id, UUID.randomUUID()))
                .isInstanceOf(com.oneday.auth.exception.InvalidOnboardingTransitionException.class);
        verify(daRegistration, never()).register(any(), any());
    }

    @Test
    void reject_setsReasonAndRejectedStatus() {
        var c = draft();
        c.setStatus(OnboardingStatus.PENDING_APPROVAL);
        UUID id = UUID.randomUUID();
        when(candidateRepo.findById(id)).thenReturn(Optional.of(c));
        var res = service.reject(id, "Failed reference check", UUID.randomUUID());
        assertThat(res.status()).isEqualTo(OnboardingStatus.REJECTED);
        assertThat(res.rejectionReason()).isEqualTo("Failed reference check");
    }

    private static DaOnboardingCandidate draft() {
        var c = new DaOnboardingCandidate();
        c.setInviteToken("tok");
        c.setEmail("riya@test.in");
        c.setStatus(OnboardingStatus.DRAFT);
        return c;
    }

    /** A DRAFT candidate with every field required to submit (incl. agreement + training accepted). */
    private static DaOnboardingCandidate completeDraft() {
        var c = draft();
        c.setFirstName("A");
        c.setLastName("B");
        c.setCityId("DEL");
        c.setShift(Shift.SHIFT_1);
        c.setAgreementAcceptedAt(java.time.Instant.now());
        c.setTrainingAckAt(java.time.Instant.now());
        return c;
    }
}
