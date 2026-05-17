package com.oneday.auth.service.impl;

import com.oneday.auth.domain.OnboardingRequest;
import com.oneday.auth.domain.Role;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OnboardingServiceImplTest {

    @Mock private OnboardingRequestRepository onboardingRepository;
    @Mock private UserRepository userRepository;
    @Mock private RoleRepository roleRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private RoleAuditLogRepository auditLogRepository;

    @InjectMocks private OnboardingServiceImpl service;

    private UUID adminId;
    private UUID requestId;
    private Role b2bRole;

    @BeforeEach
    void setup() {
        adminId = UUID.randomUUID();
        requestId = UUID.randomUUID();
        b2bRole = realRole("B2B_USER", false);
    }

    // ── SUBMIT ────────────────────────────────────────────────────────────────

    @Test
    void submit_newB2bRequest_savesPendingAndReturnsResponse() {
        when(userRepository.existsByEmail("corp@example.com")).thenReturn(false);
        when(onboardingRepository.existsByEmail("corp@example.com")).thenReturn(false);
        when(passwordEncoder.encode("Secure1234!")).thenReturn("$2a$hash");

        OnboardingRequest saved = fakeRequest(requestId, "corp@example.com", "B2B_USER", "PENDING");
        when(onboardingRepository.save(any(OnboardingRequest.class))).thenReturn(saved);

        OnboardingRequestResponse resp = service.submit(
                new OnboardingSubmitRequest("corp@example.com", "Corp User", "Secure1234!", "B2B_USER"));

        assertThat(resp.email()).isEqualTo("corp@example.com");
        assertThat(resp.requestedRole()).isEqualTo("B2B_USER");
        assertThat(resp.status()).isEqualTo("PENDING");

        ArgumentCaptor<OnboardingRequest> captor = ArgumentCaptor.forClass(OnboardingRequest.class);
        verify(onboardingRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("PENDING");
        assertThat(captor.getValue().getPasswordHash()).isEqualTo("$2a$hash");
    }

    @Test
    void submit_emailExistsInUsersTable_throwsEmailAlreadyExists() {
        when(userRepository.existsByEmail("dup@example.com")).thenReturn(true);

        assertThatThrownBy(() -> service.submit(
                new OnboardingSubmitRequest("dup@example.com", "User", "Secure1234!", "B2B_USER")))
                .isInstanceOf(EmailAlreadyExistsException.class);
    }

    @Test
    void submit_emailExistsInOnboardingRequests_throwsEmailAlreadyExists() {
        when(userRepository.existsByEmail("pending@example.com")).thenReturn(false);
        when(onboardingRepository.existsByEmail("pending@example.com")).thenReturn(true);

        assertThatThrownBy(() -> service.submit(
                new OnboardingSubmitRequest("pending@example.com", "User", "Secure1234!", "B2C_CUSTOMER")))
                .isInstanceOf(EmailAlreadyExistsException.class);
    }

    // ── LIST ALL ──────────────────────────────────────────────────────────────

    @Test
    void listAll_returnsAllRequestsNewestFirst() {
        OnboardingRequest r1 = fakeRequest(UUID.randomUUID(), "a@test.com", "B2B_USER", "PENDING");
        OnboardingRequest r2 = fakeRequest(UUID.randomUUID(), "b@test.com", "B2C_CUSTOMER", "APPROVED");
        when(onboardingRepository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(r1, r2));

        List<OnboardingRequestResponse> result = service.listAll();

        assertThat(result).hasSize(2);
        assertThat(result.get(0).email()).isEqualTo("a@test.com");
        assertThat(result.get(0).requestedRole()).isEqualTo("B2B_USER");
        assertThat(result.get(1).status()).isEqualTo("APPROVED");
    }

    @Test
    void listAll_noRequests_returnsEmpty() {
        when(onboardingRepository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of());

        assertThat(service.listAll()).isEmpty();
    }

    // ── APPROVE ───────────────────────────────────────────────────────────────

    @Test
    void approve_pendingRequest_createsUserAndMarksApproved() {
        OnboardingRequest req = fakeRequest(requestId, "corp@example.com", "B2B_USER", "PENDING");
        req.setName("Corp User");
        req.setPasswordHash("$2a$hash");
        when(onboardingRepository.findById(requestId)).thenReturn(Optional.of(req));
        when(userRepository.existsByEmail("corp@example.com")).thenReturn(false);
        when(roleRepository.findByName("B2B_USER")).thenReturn(Optional.of(b2bRole));
        User savedUser = savedUser(UUID.randomUUID(), "corp@example.com", b2bRole);
        when(userRepository.save(any(User.class))).thenReturn(savedUser);

        service.approve(requestId, adminId);

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userCaptor.capture());
        assertThat(userCaptor.getValue().getEmail()).isEqualTo("corp@example.com");
        assertThat(userCaptor.getValue().getPasswordHash()).isEqualTo("$2a$hash");
        assertThat(userCaptor.getValue().isMustChangePassword()).isTrue();
        assertThat(userCaptor.getValue().isActive()).isTrue();

        assertThat(req.getStatus()).isEqualTo("APPROVED");
        assertThat(req.getReviewedBy()).isEqualTo(adminId);
        assertThat(req.getReviewedAt()).isNotNull();
    }

    @Test
    void approve_pendingRequest_writesCreateAuditLog() {
        UUID newUserId = UUID.randomUUID();
        OnboardingRequest req = fakeRequest(requestId, "corp@example.com", "B2B_USER", "PENDING");
        when(onboardingRepository.findById(requestId)).thenReturn(Optional.of(req));
        when(userRepository.existsByEmail("corp@example.com")).thenReturn(false);
        when(roleRepository.findByName("B2B_USER")).thenReturn(Optional.of(b2bRole));
        when(userRepository.save(any(User.class))).thenReturn(savedUser(newUserId, "corp@example.com", b2bRole));

        service.approve(requestId, adminId);

        ArgumentCaptor<RoleAuditLog> logCaptor = ArgumentCaptor.forClass(RoleAuditLog.class);
        verify(auditLogRepository).save(logCaptor.capture());
        RoleAuditLog log = logCaptor.getValue();
        assertThat(log.getActorId()).isEqualTo(adminId);
        assertThat(log.getTargetUserId()).isEqualTo(newUserId);
        assertThat(log.getAction()).isEqualTo("CREATE");
        assertThat(log.getNewRole()).isEqualTo("B2B_USER");
    }

    @Test
    void approve_requestNotFound_throwsOnboardingRequestNotFoundException() {
        when(onboardingRepository.findById(requestId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.approve(requestId, adminId))
                .isInstanceOf(OnboardingRequestNotFoundException.class);
    }

    @Test
    void approve_alreadyApproved_throwsAlreadyProcessed() {
        OnboardingRequest req = fakeRequest(requestId, "corp@example.com", "B2B_USER", "APPROVED");
        when(onboardingRepository.findById(requestId)).thenReturn(Optional.of(req));

        assertThatThrownBy(() -> service.approve(requestId, adminId))
                .isInstanceOf(OnboardingRequestAlreadyProcessedException.class);
    }

    @Test
    void approve_alreadyRejected_throwsAlreadyProcessed() {
        OnboardingRequest req = fakeRequest(requestId, "corp@example.com", "B2B_USER", "REJECTED");
        when(onboardingRepository.findById(requestId)).thenReturn(Optional.of(req));

        assertThatThrownBy(() -> service.approve(requestId, adminId))
                .isInstanceOf(OnboardingRequestAlreadyProcessedException.class);
    }

    @Test
    void approve_emailAlreadyInUsersTable_throwsEmailAlreadyExists() {
        OnboardingRequest req = fakeRequest(requestId, "corp@example.com", "B2B_USER", "PENDING");
        when(onboardingRepository.findById(requestId)).thenReturn(Optional.of(req));
        when(userRepository.existsByEmail("corp@example.com")).thenReturn(true);

        assertThatThrownBy(() -> service.approve(requestId, adminId))
                .isInstanceOf(EmailAlreadyExistsException.class);
    }

    @Test
    void approve_roleNotFound_throwsRoleNotFoundException() {
        OnboardingRequest req = fakeRequest(requestId, "corp@example.com", "B2B_USER", "PENDING");
        when(onboardingRepository.findById(requestId)).thenReturn(Optional.of(req));
        when(userRepository.existsByEmail("corp@example.com")).thenReturn(false);
        when(roleRepository.findByName("B2B_USER")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.approve(requestId, adminId))
                .isInstanceOf(RoleNotFoundException.class);
    }

    @Test
    void approve_inactiveRole_throwsRoleNotFoundException() {
        OnboardingRequest req = fakeRequest(requestId, "corp@example.com", "B2B_USER", "PENDING");
        when(onboardingRepository.findById(requestId)).thenReturn(Optional.of(req));
        when(userRepository.existsByEmail("corp@example.com")).thenReturn(false);
        Role inactiveRole = realRole("B2B_USER", false);
        inactiveRole.setActive(false);
        when(roleRepository.findByName("B2B_USER")).thenReturn(Optional.of(inactiveRole));

        assertThatThrownBy(() -> service.approve(requestId, adminId))
                .isInstanceOf(RoleNotFoundException.class);
    }

    // ── REJECT ────────────────────────────────────────────────────────────────

    @Test
    void reject_pendingRequest_marksRejectedWithReason() {
        OnboardingRequest req = fakeRequest(requestId, "corp@example.com", "B2B_USER", "PENDING");
        when(onboardingRepository.findById(requestId)).thenReturn(Optional.of(req));

        service.reject(requestId, "No service agreement in place", adminId);

        assertThat(req.getStatus()).isEqualTo("REJECTED");
        assertThat(req.getRejectionReason()).isEqualTo("No service agreement in place");
        assertThat(req.getReviewedBy()).isEqualTo(adminId);
        assertThat(req.getReviewedAt()).isNotNull();
    }

    @Test
    void reject_pendingRequest_nullReason_marksRejected() {
        OnboardingRequest req = fakeRequest(requestId, "corp@example.com", "B2B_USER", "PENDING");
        when(onboardingRepository.findById(requestId)).thenReturn(Optional.of(req));

        service.reject(requestId, null, adminId);

        assertThat(req.getStatus()).isEqualTo("REJECTED");
        assertThat(req.getRejectionReason()).isNull();
    }

    @Test
    void reject_requestNotFound_throwsOnboardingRequestNotFoundException() {
        when(onboardingRepository.findById(requestId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.reject(requestId, "reason", adminId))
                .isInstanceOf(OnboardingRequestNotFoundException.class);
    }

    @Test
    void reject_alreadyProcessed_throwsAlreadyProcessed() {
        OnboardingRequest req = fakeRequest(requestId, "corp@example.com", "B2B_USER", "APPROVED");
        when(onboardingRepository.findById(requestId)).thenReturn(Optional.of(req));

        assertThatThrownBy(() -> service.reject(requestId, "reason", adminId))
                .isInstanceOf(OnboardingRequestAlreadyProcessedException.class);
    }

    // ── HELPERS ───────────────────────────────────────────────────────────────

    static OnboardingRequest fakeRequest(UUID id, String email, String requestedRole, String status) {
        OnboardingRequest req = new OnboardingRequest();
        ReflectionTestUtils.setField(req, "id", id);
        req.setEmail(email);
        req.setName("Test User");
        req.setRequestedRole(requestedRole);
        req.setPasswordHash("$2a$hash");
        req.setStatus(status);
        return req;
    }

    static User savedUser(UUID id, String email, Role role) {
        User user = new User();
        ReflectionTestUtils.setField(user, "id", id);
        user.setEmail(email);
        user.setRole(role);
        user.setActive(true);
        user.setMustChangePassword(true);
        return user;
    }

    static Role realRole(String name, boolean cityScoped) {
        Role role = new Role();
        ReflectionTestUtils.setField(role, "id", UUID.randomUUID());
        role.setName(name);
        role.setDisplayName(name);
        role.setCityScoped(cityScoped);
        role.setBuiltin(true);
        role.setActive(true);
        role.setPermissions(new HashSet<>());
        return role;
    }
}
