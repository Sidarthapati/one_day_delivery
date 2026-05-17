package com.oneday.auth.service.impl;

import com.oneday.auth.domain.OnboardingRequest;
import com.oneday.auth.domain.Role;
import com.oneday.auth.domain.User;
import com.oneday.auth.dto.request.LoginRequest;
import com.oneday.auth.dto.request.OnboardingSubmitRequest;
import com.oneday.auth.dto.response.LoginResponse;
import com.oneday.auth.dto.response.OnboardingRequestResponse;
import com.oneday.auth.repository.ApiKeyRepository;
import com.oneday.auth.repository.OnboardingRequestRepository;
import com.oneday.auth.repository.RoleAuditLogRepository;
import com.oneday.auth.repository.RoleRepository;
import com.oneday.auth.repository.UserRepository;
import com.oneday.auth.service.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * End-to-end flow: B2B and B2C users sign up via onboarding,
 * admin approves both, both successfully log in.
 *
 * Uses a real BCryptPasswordEncoder so that the password encoded during
 * submit() is actually verifiable during login() — the critical invariant
 * the approval flow depends on.
 */
@ExtendWith(MockitoExtension.class)
class OnboardingLoginFlowTest {

    // ── Repository mocks ──────────────────────────────────────────────────────
    @Mock private OnboardingRequestRepository onboardingRequestRepository;
    @Mock private UserRepository userRepository;
    @Mock private RoleRepository roleRepository;
    @Mock private RoleAuditLogRepository auditLogRepository;
    @Mock private ApiKeyRepository apiKeyRepository;
    @Mock private JwtService jwtService;

    // Real encoder — BCrypt round-trip must actually work across the flow
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    private OnboardingServiceImpl onboardingService;
    private AuthServiceImpl authService;

    // In-memory "database" shared across both services via the same mock repos
    private final Map<UUID, OnboardingRequest> requestsDb = new HashMap<>();
    private final Map<String, User>            usersDb    = new HashMap<>();

    private static final UUID ADMIN_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        onboardingService = new OnboardingServiceImpl(
                onboardingRequestRepository, userRepository, roleRepository, passwordEncoder, auditLogRepository);
        authService = new AuthServiceImpl(
                userRepository, roleRepository, apiKeyRepository, auditLogRepository,
                jwtService, passwordEncoder);

        // No email collisions for either table in this test
        when(userRepository.existsByEmail(anyString())).thenReturn(false);
        when(onboardingRequestRepository.existsByEmail(anyString())).thenReturn(false);

        // save(OnboardingRequest) → assign ID, store, return
        when(onboardingRequestRepository.save(any(OnboardingRequest.class))).thenAnswer(inv -> {
            OnboardingRequest req = inv.getArgument(0);
            if (req.getId() == null) ReflectionTestUtils.setField(req, "id", UUID.randomUUID());
            requestsDb.put(req.getId(), req);
            return req;
        });

        // findById(UUID) → look up stored request
        when(onboardingRequestRepository.findById(any(UUID.class))).thenAnswer(inv ->
                Optional.ofNullable(requestsDb.get((UUID) inv.getArgument(0))));

        // save(User) → assign ID, store by email, return
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            User user = inv.getArgument(0);
            if (user.getId() == null) ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
            usersDb.put(user.getEmail(), user);
            return user;
        });

        // findByEmail(email) → look up stored user (used by login)
        when(userRepository.findByEmail(anyString())).thenAnswer(inv ->
                Optional.ofNullable(usersDb.get((String) inv.getArgument(0))));

        // JwtService stubs
        when(jwtService.createToken(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            return "jwt-" + u.getRole().getName() + "-" + u.getEmail();
        });
        when(jwtService.expiryFor(any(User.class))).thenReturn(Instant.now().plusSeconds(28800));
    }

    @Test
    void b2bAndB2cUsersSignUp_adminApprovesBoths_bothSuccessfullyLogin() {

        // ── Roles ─────────────────────────────────────────────────────────────
        Role b2bRole = buildRole("B2B_USER");
        Role b2cRole = buildRole("B2C_CUSTOMER");
        when(roleRepository.findByName("B2B_USER")).thenReturn(Optional.of(b2bRole));
        when(roleRepository.findByName("B2C_CUSTOMER")).thenReturn(Optional.of(b2cRole));

        // ── Step 1: B2B user submits onboarding request ───────────────────────
        OnboardingRequestResponse b2bReq = onboardingService.submit(
                new OnboardingSubmitRequest("b2b@acme.in", "Acme Corp", "B2bPass1!", "B2B_USER"));

        assertThat(b2bReq.status()).isEqualTo("PENDING");
        assertThat(b2bReq.email()).isEqualTo("b2b@acme.in");
        assertThat(b2bReq.requestedRole()).isEqualTo("B2B_USER");
        assertThat(b2bReq.id()).isNotNull();

        // ── Step 2: B2C user submits onboarding request ───────────────────────
        OnboardingRequestResponse b2cReq = onboardingService.submit(
                new OnboardingSubmitRequest("b2c@retail.in", "Retail Customer", "B2cPass1!", "B2C_CUSTOMER"));

        assertThat(b2cReq.status()).isEqualTo("PENDING");
        assertThat(b2cReq.email()).isEqualTo("b2c@retail.in");
        assertThat(b2cReq.requestedRole()).isEqualTo("B2C_CUSTOMER");
        assertThat(b2cReq.id()).isNotNull();

        // ── Step 3: Admin approves B2B request ───────────────────────────────
        onboardingService.approve(b2bReq.id(), ADMIN_ID);

        OnboardingRequest approvedB2b = requestsDb.get(b2bReq.id());
        assertThat(approvedB2b.getStatus()).isEqualTo("APPROVED");
        assertThat(approvedB2b.getReviewedBy()).isEqualTo(ADMIN_ID);
        assertThat(approvedB2b.getReviewedAt()).isNotNull();

        // ── Step 4: Admin approves B2C request ───────────────────────────────
        onboardingService.approve(b2cReq.id(), ADMIN_ID);

        OnboardingRequest approvedB2c = requestsDb.get(b2cReq.id());
        assertThat(approvedB2c.getStatus()).isEqualTo("APPROVED");
        assertThat(approvedB2c.getReviewedBy()).isEqualTo(ADMIN_ID);

        // ── Verify user records were created correctly ─────────────────────────
        User b2bUser = usersDb.get("b2b@acme.in");
        assertThat(b2bUser).isNotNull();
        assertThat(b2bUser.getRole().getName()).isEqualTo("B2B_USER");
        assertThat(b2bUser.isActive()).isTrue();
        assertThat(b2bUser.isMustChangePassword()).isTrue();

        User b2cUser = usersDb.get("b2c@retail.in");
        assertThat(b2cUser).isNotNull();
        assertThat(b2cUser.getRole().getName()).isEqualTo("B2C_CUSTOMER");
        assertThat(b2cUser.isActive()).isTrue();
        assertThat(b2cUser.isMustChangePassword()).isTrue();

        // ── Step 5: B2B user logs in with the password they registered with ───
        LoginResponse b2bLogin = authService.login(
                new LoginRequest("b2b@acme.in", "B2bPass1!"));

        assertThat(b2bLogin.token()).isEqualTo("jwt-B2B_USER-b2b@acme.in");
        assertThat(b2bLogin.role()).isEqualTo("B2B_USER");
        assertThat(b2bLogin.cityId()).isNull();
        assertThat(b2bLogin.mustChangePassword()).isTrue();

        // ── Step 6: B2C user logs in with the password they registered with ───
        LoginResponse b2cLogin = authService.login(
                new LoginRequest("b2c@retail.in", "B2cPass1!"));

        assertThat(b2cLogin.token()).isEqualTo("jwt-B2C_CUSTOMER-b2c@retail.in");
        assertThat(b2cLogin.role()).isEqualTo("B2C_CUSTOMER");
        assertThat(b2cLogin.cityId()).isNull();
        assertThat(b2cLogin.mustChangePassword()).isTrue();
    }

    @Test
    void approvedUser_changesPassword_mustChangePasswordClearsAndNextLoginReflectsIt() {
        Role b2bRole = buildRole("B2B_USER");
        when(roleRepository.findByName("B2B_USER")).thenReturn(Optional.of(b2bRole));

        // ── Onboarding signup ─────────────────────────────────────────────────
        OnboardingRequestResponse req = onboardingService.submit(
                new OnboardingSubmitRequest("vendor@acme.in", "Acme Vendor", "TempPass1!", "B2B_USER"));

        // ── Admin approves ────────────────────────────────────────────────────
        onboardingService.approve(req.id(), ADMIN_ID);

        User user = usersDb.get("vendor@acme.in");
        assertThat(user.isMustChangePassword()).isTrue();

        // ── User logs in — mustChangePassword is true ─────────────────────────
        LoginResponse firstLogin = authService.login(new LoginRequest("vendor@acme.in", "TempPass1!"));
        assertThat(firstLogin.mustChangePassword()).isTrue();

        // Wire findById so changePassword() can find the user
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));

        // ── User changes their password ───────────────────────────────────────
        authService.changePassword(user.getId(), "TempPass1!", "NewSecure1!");

        assertThat(user.isMustChangePassword()).isFalse();

        // ── User logs in again — mustChangePassword is now false ──────────────
        when(jwtService.createToken(user)).thenReturn("jwt-after-change");
        LoginResponse secondLogin = authService.login(new LoginRequest("vendor@acme.in", "NewSecure1!"));
        assertThat(secondLogin.mustChangePassword()).isFalse();
        assertThat(secondLogin.token()).isEqualTo("jwt-after-change");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static Role buildRole(String name) {
        Role role = new Role();
        ReflectionTestUtils.setField(role, "id", UUID.randomUUID());
        role.setName(name);
        role.setDisplayName(name);
        role.setActive(true);
        role.setBuiltin(true);
        role.setCityScoped(false);
        role.setPermissions(new HashSet<>());
        return role;
    }
}
