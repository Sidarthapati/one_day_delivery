package com.oneday.auth.service.impl;

import com.oneday.auth.domain.OnboardingRequest;
import com.oneday.auth.domain.RoleAuditLog;
import com.oneday.auth.domain.User;
import com.oneday.auth.dto.request.OnboardingSubmitRequest;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
class OnboardingServiceImpl implements OnboardingService {

    private final OnboardingRequestRepository onboardingRepository;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final RoleAuditLogRepository auditLogRepository;

    OnboardingServiceImpl(OnboardingRequestRepository onboardingRepository,
                          UserRepository userRepository,
                          RoleRepository roleRepository,
                          PasswordEncoder passwordEncoder,
                          RoleAuditLogRepository auditLogRepository) {
        this.onboardingRepository = onboardingRepository;
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.passwordEncoder = passwordEncoder;
        this.auditLogRepository = auditLogRepository;
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

        var log = new RoleAuditLog();
        log.setActorId(actorId);
        log.setTargetUserId(user.getId());
        log.setAction("CREATE");
        log.setNewRole(role.getName());
        auditLogRepository.save(log);

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
