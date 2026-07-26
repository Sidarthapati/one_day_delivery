package com.oneday.auth.service.impl;

import com.oneday.auth.domain.OnboardingRequest;
import com.oneday.auth.domain.RoleAuditLog;
import com.oneday.auth.domain.User;
import com.oneday.auth.dto.request.BusinessOnboardingRequest;
import com.oneday.auth.dto.request.OnboardingSubmitRequest;
import com.oneday.auth.dto.response.BusinessOnboardingResponse;
import com.oneday.auth.dto.response.OnboardingRequestResponse;
import com.oneday.auth.exception.EmailAlreadyExistsException;
import com.oneday.auth.exception.OnboardingRequestAlreadyProcessedException;
import com.oneday.auth.exception.OnboardingRequestNotFoundException;
import com.oneday.auth.exception.RoleNotFoundException;
import com.oneday.auth.repository.OnboardingRequestRepository;
import com.oneday.auth.repository.RoleAuditLogRepository;
import com.oneday.auth.repository.RoleRepository;
import com.oneday.auth.repository.UserRepository;
import com.oneday.auth.service.OnboardingService;
import com.oneday.common.port.B2bProvisioningPort;
import com.oneday.common.port.KycPort;
import com.oneday.common.port.dto.B2bProvisioningRequest;
import com.oneday.common.port.dto.kyc.GstinResult;
import com.oneday.common.port.dto.kyc.PanResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
class OnboardingServiceImpl implements OnboardingService {

    private static final Logger LOG = LoggerFactory.getLogger(OnboardingServiceImpl.class);
    private static final String B2B_ROLE = "B2B_USER";

    private final OnboardingRequestRepository onboardingRepository;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final RoleAuditLogRepository auditLogRepository;
    private final KycPort kycPort;
    // Optional: implemented by orders (M4). Absent in an auth-only context → provisioning is skipped.
    private final ObjectProvider<B2bProvisioningPort> provisioningPort;

    OnboardingServiceImpl(OnboardingRequestRepository onboardingRepository,
                          UserRepository userRepository,
                          RoleRepository roleRepository,
                          PasswordEncoder passwordEncoder,
                          RoleAuditLogRepository auditLogRepository,
                          KycPort kycPort,
                          ObjectProvider<B2bProvisioningPort> provisioningPort) {
        this.onboardingRepository = onboardingRepository;
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.passwordEncoder = passwordEncoder;
        this.auditLogRepository = auditLogRepository;
        this.kycPort = kycPort;
        this.provisioningPort = provisioningPort;
    }

    @Override
    @Transactional
    public OnboardingRequestResponse submit(OnboardingSubmitRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            throw new EmailAlreadyExistsException(request.email());
        }
        if (onboardingRepository.existsByEmail(request.email())) {
            throw new EmailAlreadyExistsException(request.email());
        }

        var onboardingRequest = new OnboardingRequest();
        onboardingRequest.setEmail(request.email());
        onboardingRequest.setName(request.name());
        onboardingRequest.setPhone(request.phone());
        onboardingRequest.setRequestedRole(request.requestedRole());
        onboardingRequest.setPasswordHash(passwordEncoder.encode(request.password()));
        onboardingRequest.setStatus("PENDING");
        onboardingRequest = onboardingRepository.save(onboardingRequest);

        return toResponse(onboardingRequest);
    }

    @Override
    @Transactional
    public BusinessOnboardingResponse submitBusiness(BusinessOnboardingRequest request) {
        if (userRepository.existsByEmail(request.email()) || onboardingRepository.existsByEmail(request.email())) {
            throw new EmailAlreadyExistsException(request.email());
        }

        // Run KYC now (GSTIN + PAN) so the ADMIN reviews with verdicts in hand.
        GstinResult gstin = kycPort.verifyGstin(request.gstin());
        PanResult pan = kycPort.verifyPan(request.pan(), request.name());
        boolean needsReview = !gstin.verified() || !pan.verified();

        var r = new OnboardingRequest();
        r.setEmail(request.email());
        r.setName(request.name());
        r.setPhone(request.phone());
        r.setRequestedRole(B2B_ROLE);
        r.setPasswordHash(passwordEncoder.encode(request.password()));
        r.setStatus("PENDING");
        r.setCompanyName(request.companyName());
        r.setBusinessType(request.businessType());
        r.setGstin(request.gstin());
        r.setPan(request.pan());
        r.setBillingEmail(request.billingEmail());
        r.setCityId(request.cityId());
        r.setGstinVerified(gstin.verified());
        r.setGstinLegalName(gstin.legalName());
        r.setPanVerified(pan.verified());
        r.setKycMessage(needsReview ? kycMessage(gstin, pan) : "All checks passed");
        r = onboardingRepository.save(r);

        return new BusinessOnboardingResponse(
                r.getId(), r.getStatus(), gstin.verified(), gstin.legalName(),
                pan.verified(), needsReview, r.getKycMessage());
    }

    private static String kycMessage(GstinResult gstin, PanResult pan) {
        StringBuilder sb = new StringBuilder();
        if (!gstin.verified()) sb.append("GSTIN: ").append(gstin.message());
        if (!pan.verified()) sb.append(sb.length() > 0 ? " · " : "").append("PAN: ").append(pan.message());
        return sb.toString();
    }

    @Override
    @Transactional(readOnly = true)
    public List<OnboardingRequestResponse> listAll() {
        return onboardingRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(this::toResponse)
                .toList();
    }

    @Override
    @Transactional
    public void approve(UUID requestId, UUID actorId) {
        var onboardingRequest = onboardingRepository.findById(requestId)
                .orElseThrow(() -> new OnboardingRequestNotFoundException("Onboarding request not found"));

        if (!"PENDING".equals(onboardingRequest.getStatus())) {
            throw new OnboardingRequestAlreadyProcessedException();
        }

        if (userRepository.existsByEmail(onboardingRequest.getEmail())) {
            throw new EmailAlreadyExistsException(onboardingRequest.getEmail());
        }

        var role = roleRepository.findByName(onboardingRequest.getRequestedRole())
                .filter(r -> r.isActive())
                .orElseThrow(() -> new RoleNotFoundException("Role not found: " + onboardingRequest.getRequestedRole()));

        var user = new User();
        user.setEmail(onboardingRequest.getEmail());
        user.setName(onboardingRequest.getName());
        user.setPhone(onboardingRequest.getPhone());
        user.setPasswordHash(onboardingRequest.getPasswordHash());
        user.setRole(role);
        user.setActive(true);
        user.setMustChangePassword(true);
        user = userRepository.save(user);

        var auditLog = new RoleAuditLog();
        auditLog.setActorId(actorId);
        auditLog.setTargetUserId(user.getId());
        auditLog.setAction("CREATE");
        auditLog.setNewRole(role.getName());
        auditLogRepository.save(auditLog);

        // Business onboarding: provision the B2B account in orders (M4) via the port.
        if (B2B_ROLE.equals(onboardingRequest.getRequestedRole())) {
            B2bProvisioningPort port = provisioningPort.getIfAvailable();
            if (port != null) {
                port.provisionAccount(new B2bProvisioningRequest(
                        user.getId(),
                        onboardingRequest.getCompanyName(),
                        onboardingRequest.getBusinessType(),
                        onboardingRequest.getGstin(),
                        onboardingRequest.getPan(),
                        onboardingRequest.getBillingEmail(),
                        onboardingRequest.getCityId(),
                        Boolean.TRUE.equals(onboardingRequest.getGstinVerified()),
                        Boolean.TRUE.equals(onboardingRequest.getPanVerified()),
                        0L,       // credit limit — prepaid until a credit line is approved
                        (short) 0 // payment terms days
                ));
            } else {
                LOG.warn("B2bProvisioningPort unavailable — B2B account for user {} not provisioned",
                        user.getId());
            }
        }

        onboardingRequest.setStatus("APPROVED");
        onboardingRequest.setReviewedBy(actorId);
        onboardingRequest.setReviewedAt(Instant.now());
        onboardingRepository.save(onboardingRequest);
    }

    @Override
    @Transactional
    public void reject(UUID requestId, String reason, UUID actorId) {
        var onboardingRequest = onboardingRepository.findById(requestId)
                .orElseThrow(() -> new OnboardingRequestNotFoundException("Onboarding request not found"));

        if (!"PENDING".equals(onboardingRequest.getStatus())) {
            throw new OnboardingRequestAlreadyProcessedException();
        }

        onboardingRequest.setStatus("REJECTED");
        onboardingRequest.setRejectionReason(reason);
        onboardingRequest.setReviewedBy(actorId);
        onboardingRequest.setReviewedAt(Instant.now());
        onboardingRepository.save(onboardingRequest);
    }

    private OnboardingRequestResponse toResponse(OnboardingRequest r) {
        return new OnboardingRequestResponse(
                r.getId(), r.getEmail(), r.getName(), r.getRequestedRole(),
                r.getStatus(), r.getRejectionReason(), r.getReviewedBy(),
                r.getReviewedAt(), r.getCreatedAt());
    }
}
