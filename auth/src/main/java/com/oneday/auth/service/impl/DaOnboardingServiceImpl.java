package com.oneday.auth.service.impl;

import com.oneday.auth.domain.DaOnboardingCandidate;
import com.oneday.auth.domain.DaProfile;
import com.oneday.auth.domain.OnboardingStatus;
import com.oneday.auth.dto.request.CandidateBankRequest;
import com.oneday.auth.dto.request.CandidateLocationRequest;
import com.oneday.auth.dto.request.CandidatePersonalRequest;
import com.oneday.auth.dto.request.DaOnboardingInviteRequest;
import com.oneday.auth.dto.request.RegisterDaRequest;
import com.oneday.auth.dto.response.CandidateResponse;
import com.oneday.auth.dto.response.DaResponse;
import com.oneday.auth.dto.response.OnboardingFunnelResponse;
import com.oneday.auth.dto.response.OnboardingInviteResponse;
import com.oneday.auth.exception.EmailAlreadyExistsException;
import com.oneday.auth.exception.OnboardingCandidateNotFoundException;
import com.oneday.auth.exception.OnboardingValidationException;
import com.oneday.auth.config.BgvProperties;
import com.oneday.auth.repository.DaOnboardingCandidateRepository;
import com.oneday.auth.repository.DaProfileRepository;
import com.oneday.auth.repository.UserRepository;
import com.oneday.auth.service.BgvService;
import com.oneday.auth.service.DaOnboardingService;
import com.oneday.auth.service.DaRegistrationService;
import com.oneday.auth.service.OnboardingStateMachine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

@Service
class DaOnboardingServiceImpl implements DaOnboardingService {

    private static final Logger LOG = LoggerFactory.getLogger(DaOnboardingServiceImpl.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final DaOnboardingCandidateRepository candidateRepository;
    private final DaProfileRepository daProfileRepository;
    private final UserRepository userRepository;
    private final DaRegistrationService daRegistrationService;
    private final OnboardingStateMachine stateMachine;
    private final BgvService bgvService;
    private final BgvProperties bgvProps;

    /** Web base for the applicant wizard; the invite token is appended. */
    private final String inviteBaseUrl;

    DaOnboardingServiceImpl(DaOnboardingCandidateRepository candidateRepository,
                            DaProfileRepository daProfileRepository,
                            UserRepository userRepository,
                            DaRegistrationService daRegistrationService,
                            OnboardingStateMachine stateMachine,
                            BgvService bgvService,
                            BgvProperties bgvProps,
                            @Value("${godspeed.onboarding.invite-base-url:http://localhost:3000/onboard}")
                            String inviteBaseUrl) {
        this.candidateRepository = candidateRepository;
        this.daProfileRepository = daProfileRepository;
        this.userRepository = userRepository;
        this.daRegistrationService = daRegistrationService;
        this.stateMachine = stateMachine;
        this.bgvService = bgvService;
        this.bgvProps = bgvProps;
        this.inviteBaseUrl = inviteBaseUrl;
    }

    // ── Admin ────────────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public OnboardingInviteResponse createInvite(DaOnboardingInviteRequest request, UUID actorId) {
        String email = request.email().trim().toLowerCase();
        if (userRepository.existsByEmail(email) || candidateRepository.existsByEmail(email)) {
            throw new EmailAlreadyExistsException(email);
        }

        var candidate = new DaOnboardingCandidate();
        candidate.setInviteToken(generateToken());
        candidate.setStatus(OnboardingStatus.DRAFT);
        candidate.setEmail(email);
        candidate.setFirstName(request.firstName());
        candidate.setLastName(request.lastName());
        candidate.setPhone(request.phone());
        candidate.setCityId(request.cityId());
        candidate = candidateRepository.save(candidate);

        LOG.info("Opened DA onboarding candidate {} (invited by {})", candidate.getId(), actorId);
        return new OnboardingInviteResponse(
                candidate.getId(), candidate.getInviteToken(),
                inviteUrl(candidate.getInviteToken()), candidate.getStatus());
    }

    @Override
    @Transactional(readOnly = true)
    public List<CandidateResponse> listCandidates(OnboardingStatus status) {
        var candidates = status == null
                ? candidateRepository.findAllByOrderByCreatedAtDesc()
                : candidateRepository.findByStatusOrderByCreatedAtDesc(status);
        return candidates.stream().map(this::toResponse).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public OnboardingFunnelResponse funnel() {
        var counts = new java.util.EnumMap<OnboardingStatus, Long>(OnboardingStatus.class);
        for (Object[] row : candidateRepository.countByStatusGrouped()) {
            counts.put((OnboardingStatus) row[0], (Long) row[1]);
        }
        long total = counts.values().stream().mapToLong(Long::longValue).sum();
        long inBgv = c(counts, OnboardingStatus.BGV_IN_PROGRESS) + c(counts, OnboardingStatus.BGV_CLEAR)
                + c(counts, OnboardingStatus.BGV_INSUFFICIENT);
        long onboarded = c(counts, OnboardingStatus.ONBOARDED) + c(counts, OnboardingStatus.APPROVED);
        double conversion = total == 0 ? 0.0 : (double) onboarded / total;
        return new OnboardingFunnelResponse(
                total,
                c(counts, OnboardingStatus.DRAFT),
                c(counts, OnboardingStatus.SUBMITTED),
                inBgv,
                c(counts, OnboardingStatus.PENDING_APPROVAL),
                onboarded,
                c(counts, OnboardingStatus.REJECTED),
                conversion);
    }

    @Override
    @Transactional(readOnly = true)
    public CandidateResponse getCandidate(UUID candidateId) {
        return toResponse(requireCandidate(candidateId));
    }

    @Override
    @Transactional(readOnly = true)
    public OnboardingInviteResponse resendInvite(UUID candidateId) {
        var candidate = requireCandidate(candidateId);
        if (candidate.getStatus() == OnboardingStatus.ONBOARDED
                || candidate.getStatus() == OnboardingStatus.REJECTED) {
            throw new OnboardingValidationException(
                    "Cannot resend an invite for a completed candidate (status " + candidate.getStatus() + ")");
        }
        return new OnboardingInviteResponse(candidate.getId(), candidate.getInviteToken(),
                inviteUrl(candidate.getInviteToken()), candidate.getStatus());
    }

    private static long c(java.util.Map<OnboardingStatus, Long> counts, OnboardingStatus s) {
        return counts.getOrDefault(s, 0L);
    }

    @Override
    @Transactional
    public DaResponse approve(UUID candidateId, UUID actorId) {
        var candidate = requireCandidate(candidateId);
        stateMachine.assertCanTransition(candidate.getStatus(), OnboardingStatus.APPROVED);

        // Provision through the same audited seam the admin "add DA" path uses: creates the
        // DELIVERY_ASSOCIATE user (mustChangePassword=true, temp password) + the da_profile.
        var registerRequest = new RegisterDaRequest(
                candidate.getFirstName(),
                candidate.getLastName(),
                candidate.getEmail(),
                candidate.getPhone(),
                candidate.getCityId(),
                candidate.getShift(),
                LocalDate.now(),   // contract starts on approval
                null,              // open-ended
                candidate.getAadhaar(),
                candidate.getPan(),
                null,              // pan doc pointer — real document handling lands in S2
                null);             // null → temp password generated + returned
        DaResponse da = daRegistrationService.register(registerRequest, actorId);

        // Assign the human-friendly employee id and stamp it on the HR record.
        String employeeId = buildEmployeeId(candidate.getCityId());
        DaProfile profile = daProfileRepository.findById(da.daId())
                .orElseThrow(() -> new IllegalStateException("da_profile missing after provisioning " + da.daId()));
        profile.setEmployeeId(employeeId);
        daProfileRepository.save(profile);

        candidate.setStatus(OnboardingStatus.APPROVED);
        candidate.setApprovedBy(actorId);
        candidate.setApprovedAt(Instant.now());
        candidate.setProvisionedUserId(da.daId());
        candidate.setEmployeeId(employeeId);
        // No BGV/e-sign gate to clear in v1 — go straight to ONBOARDED.
        stateMachine.assertCanTransition(candidate.getStatus(), OnboardingStatus.ONBOARDED);
        candidate.setStatus(OnboardingStatus.ONBOARDED);
        candidateRepository.save(candidate);

        LOG.info("Onboarded DA candidate {} → user {} ({}), approved by {}",
                candidate.getId(), da.daId(), employeeId, actorId);
        return da;
    }

    @Override
    @Transactional
    public CandidateResponse reject(UUID candidateId, String reason, UUID actorId) {
        var candidate = requireCandidate(candidateId);
        stateMachine.assertCanTransition(candidate.getStatus(), OnboardingStatus.REJECTED);
        candidate.setStatus(OnboardingStatus.REJECTED);
        candidate.setRejectionReason(reason);
        candidate.setApprovedBy(actorId);
        candidate.setApprovedAt(Instant.now());
        candidateRepository.save(candidate);
        return toResponse(candidate);
    }

    // ── Applicant (by invite token) ───────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public CandidateResponse getByToken(String inviteToken) {
        return toResponse(requireByToken(inviteToken));
    }

    @Override
    @Transactional
    public CandidateResponse updatePersonal(String inviteToken, CandidatePersonalRequest request) {
        var candidate = requireEditable(inviteToken);
        candidate.setFirstName(request.firstName());
        candidate.setLastName(request.lastName());
        candidate.setPhone(request.phone());
        candidate.setDob(request.dob());
        candidate.setAadhaar(request.aadhaar());
        candidate.setPan(request.pan());
        candidate.setDrivingLicense(request.drivingLicense());
        return toResponse(candidateRepository.save(candidate));
    }

    @Override
    @Transactional
    public CandidateResponse updateLocation(String inviteToken, CandidateLocationRequest request) {
        var candidate = requireEditable(inviteToken);
        candidate.setCityId(request.cityId());
        candidate.setStationLabel(request.stationLabel());
        candidate.setShift(request.shift());
        return toResponse(candidateRepository.save(candidate));
    }

    @Override
    @Transactional
    public CandidateResponse updateBank(String inviteToken, CandidateBankRequest request) {
        var candidate = requireEditable(inviteToken);
        candidate.setBankAccountNumber(request.bankAccountNumber());
        candidate.setIfsc(request.ifsc());
        candidate.setAccountHolderName(request.accountHolderName());
        return toResponse(candidateRepository.save(candidate));
    }

    @Override
    @Transactional
    public CandidateResponse acceptAgreement(String inviteToken, String version) {
        var candidate = requireEditable(inviteToken);
        candidate.setAgreementAcceptedAt(Instant.now());
        candidate.setAgreementDocKey("agreement:" + (version == null || version.isBlank() ? "v1" : version.trim()));
        return toResponse(candidateRepository.save(candidate));
    }

    @Override
    @Transactional
    public CandidateResponse acknowledgeTraining(String inviteToken) {
        var candidate = requireEditable(inviteToken);
        candidate.setTrainingAckAt(Instant.now());
        return toResponse(candidateRepository.save(candidate));
    }

    @Override
    @Transactional
    public CandidateResponse submit(String inviteToken) {
        var candidate = requireByToken(inviteToken);
        if (candidate.getStatus() != OnboardingStatus.DRAFT) {
            throw new OnboardingValidationException(
                    "Candidate has already been submitted (status " + candidate.getStatus() + ")");
        }
        validateComplete(candidate);

        // DRAFT → SUBMITTED, then kick off BGV. The poll job / reviewer refresh advances BGV_IN_PROGRESS
        // → PENDING_APPROVAL once every check has a verdict. With BGV disabled, skip straight to the queue.
        stateMachine.assertCanTransition(OnboardingStatus.DRAFT, OnboardingStatus.SUBMITTED);
        candidate.setStatus(OnboardingStatus.SUBMITTED);
        candidate.setSubmittedAt(Instant.now());
        if (bgvProps.isEnabled()) {
            bgvService.initiateForCandidate(candidate);   // → BGV_IN_PROGRESS + per-check rows
        } else {
            stateMachine.assertCanTransition(OnboardingStatus.SUBMITTED, OnboardingStatus.PENDING_APPROVAL);
            candidate.setStatus(OnboardingStatus.PENDING_APPROVAL);
        }
        return toResponse(candidateRepository.save(candidate));
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────

    private DaOnboardingCandidate requireCandidate(UUID id) {
        return candidateRepository.findById(id)
                .orElseThrow(() -> new OnboardingCandidateNotFoundException("Candidate not found: " + id));
    }

    private DaOnboardingCandidate requireByToken(String token) {
        return candidateRepository.findByInviteToken(token)
                .orElseThrow(() -> new OnboardingCandidateNotFoundException("Invalid or expired invite"));
    }

    /** Load a candidate that is still editable (DRAFT); once submitted the wizard is read-only. */
    private DaOnboardingCandidate requireEditable(String token) {
        var candidate = requireByToken(token);
        if (candidate.getStatus() != OnboardingStatus.DRAFT) {
            throw new OnboardingValidationException(
                    "Onboarding can only be edited before submission (status " + candidate.getStatus() + ")");
        }
        return candidate;
    }

    /** Fields required to provision a DA on approval (RegisterDaRequest needs city + shift). */
    private void validateComplete(DaOnboardingCandidate c) {
        StringBuilder missing = new StringBuilder();
        if (isBlank(c.getFirstName())) appendMissing(missing, "firstName");
        if (isBlank(c.getLastName())) appendMissing(missing, "lastName");
        if (isBlank(c.getCityId())) appendMissing(missing, "cityId");
        if (c.getShift() == null) appendMissing(missing, "shift");
        if (c.getAgreementAcceptedAt() == null) appendMissing(missing, "agreement");
        if (c.getTrainingAckAt() == null) appendMissing(missing, "training");
        if (missing.length() > 0) {
            throw new OnboardingValidationException("Cannot submit — missing: " + missing);
        }
    }

    private static void appendMissing(StringBuilder sb, String field) {
        sb.append(sb.length() > 0 ? ", " : "").append(field);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private String buildEmployeeId(String cityId) {
        String city = cityId == null ? "GEN" : cityId.trim().toUpperCase();
        return "GD-" + city + "-" + candidateRepository.nextEmployeeSeq();
    }

    private String inviteUrl(String token) {
        String base = inviteBaseUrl.endsWith("/") ? inviteBaseUrl.substring(0, inviteBaseUrl.length() - 1)
                : inviteBaseUrl;
        return base + "/" + token;
    }

    private static String generateToken() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private CandidateResponse toResponse(DaOnboardingCandidate c) {
        return new CandidateResponse(
                c.getId(), c.getInviteToken(), c.getStatus(),
                c.getFirstName(), c.getLastName(), c.getEmail(), c.getPhone(), c.getDob(),
                c.getAadhaar(), c.getPan(), c.getDrivingLicense(),
                c.getCityId(), c.getStationLabel(), c.getShift(),
                c.getBankAccountNumber(), c.getIfsc(), c.getAccountHolderName(),
                c.getAgreementAcceptedAt(), c.getTrainingAckAt(),
                c.getSubmittedAt(), c.getApprovedBy(), c.getApprovedAt(), c.getRejectionReason(),
                c.getProvisionedUserId(), c.getEmployeeId(),
                c.getCreatedAt(), c.getUpdatedAt());
    }
}
