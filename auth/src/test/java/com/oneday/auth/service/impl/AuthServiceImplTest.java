package com.oneday.auth.service.impl;

import com.oneday.auth.domain.ApiKey;
import com.oneday.auth.domain.Role;
import com.oneday.auth.domain.RoleAuditLog;
import com.oneday.auth.domain.User;
import com.oneday.auth.dto.request.ApiKeyCreateRequest;
import com.oneday.auth.dto.request.LoginRequest;
import com.oneday.auth.dto.request.RegisterRequest;
import com.oneday.auth.dto.response.ApiKeyCreateResponse;
import com.oneday.auth.dto.response.ApiKeyResponse;
import com.oneday.auth.dto.response.LoginResponse;
import com.oneday.auth.exception.ApiKeyCapExceededException;
import com.oneday.auth.exception.BadCredentialsException;
import com.oneday.auth.exception.EmailAlreadyExistsException;
import com.oneday.auth.exception.ForbiddenException;
import com.oneday.auth.exception.RoleNotFoundException;
import com.oneday.auth.exception.UserNotFoundException;
import com.oneday.auth.repository.ApiKeyRepository;
import com.oneday.auth.repository.RoleAuditLogRepository;
import com.oneday.auth.repository.RoleRepository;
import com.oneday.auth.repository.UserRepository;
import com.oneday.auth.service.JwtService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.impl.DefaultClaims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceImplTest {

    @Mock private UserRepository userRepository;
    @Mock private RoleRepository roleRepository;
    @Mock private ApiKeyRepository apiKeyRepository;
    @Mock private RoleAuditLogRepository auditLogRepository;
    @Mock private JwtService jwtService;
    @Mock private PasswordEncoder passwordEncoder;

    @InjectMocks private AuthServiceImpl service;

    private UUID adminId;
    private Role adminRole;
    private Role b2cRole;
    private User adminUser;

    @BeforeEach
    void setup() {
        adminId = UUID.randomUUID();
        adminRole = realRole("ADMIN", false);
        b2cRole = realRole("C2C_CUSTOMER", false);
        adminUser = realUser(adminId, "admin@oneday.in", adminRole, null);
    }

    // ── LOGIN ─────────────────────────────────────────────────────────────────

    @Test
    void login_validCredentials_returnsTokenAndRole() {
        when(userRepository.findByEmail("admin@oneday.in")).thenReturn(Optional.of(adminUser));
        when(passwordEncoder.matches("Admin1234!", "$2a$hash")).thenReturn(true);
        when(jwtService.createToken(adminUser)).thenReturn("jwt-token");
        when(jwtService.expiryFor(adminUser)).thenReturn(Instant.now().plusSeconds(28800));

        LoginResponse resp = service.login(new LoginRequest("admin@oneday.in", "Admin1234!"));

        assertThat(resp.token()).isEqualTo("jwt-token");
        assertThat(resp.role()).isEqualTo("ADMIN");
        assertThat(resp.cityId()).isNull();
        assertThat(resp.mustChangePassword()).isFalse();
    }

    @Test
    void login_emailNotFound_throwsBadCredentials() {
        when(userRepository.findByEmail(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.login(new LoginRequest("nobody@oneday.in", "pw")))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void login_inactiveUser_throwsBadCredentials() {
        User inactiveUser = realUser(adminId, "admin@oneday.in", adminRole, null);
        inactiveUser.setActive(false);
        when(userRepository.findByEmail("admin@oneday.in")).thenReturn(Optional.of(inactiveUser));

        assertThatThrownBy(() -> service.login(new LoginRequest("admin@oneday.in", "Admin1234!")))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void login_wrongPassword_throwsBadCredentials() {
        when(userRepository.findByEmail("admin@oneday.in")).thenReturn(Optional.of(adminUser));
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(false);

        assertThatThrownBy(() -> service.login(new LoginRequest("admin@oneday.in", "wrongPassword")))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void login_sameExceptionTypeForWrongEmailAndWrongPassword() {
        when(userRepository.findByEmail("nobody@test.com")).thenReturn(Optional.empty());
        when(userRepository.findByEmail("admin@oneday.in")).thenReturn(Optional.of(adminUser));
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(false);

        assertThatThrownBy(() -> service.login(new LoginRequest("nobody@test.com", "pw")))
                .isExactlyInstanceOf(BadCredentialsException.class);
        assertThatThrownBy(() -> service.login(new LoginRequest("admin@oneday.in", "wrong")))
                .isExactlyInstanceOf(BadCredentialsException.class);
    }

    // ── SELF-REGISTER (C2C) ───────────────────────────────────────────────────

    @Test
    void register_newUser_createsC2cCustomerAndReturnsToken() {
        when(userRepository.existsByEmail("riya@example.com")).thenReturn(false);
        when(roleRepository.findByName("C2C_CUSTOMER")).thenReturn(Optional.of(b2cRole));

        User saved = realUser(UUID.randomUUID(), "riya@example.com", b2cRole, null);
        when(userRepository.save(any(User.class))).thenReturn(saved);
        when(auditLogRepository.save(any(RoleAuditLog.class))).thenReturn(new RoleAuditLog());
        when(jwtService.createToken(saved)).thenReturn("new-token");
        when(jwtService.expiryFor(saved)).thenReturn(Instant.now().plusSeconds(28800));

        LoginResponse resp = service.register(new RegisterRequest("riya@example.com", "Secret1234", "Riya"));

        assertThat(resp.token()).isEqualTo("new-token");
        assertThat(resp.role()).isEqualTo("C2C_CUSTOMER");
        verify(userRepository).save(any(User.class));
        verify(auditLogRepository).save(any(RoleAuditLog.class));
    }

    @Test
    void register_duplicateEmail_throwsEmailAlreadyExists() {
        when(userRepository.existsByEmail("dup@example.com")).thenReturn(true);

        assertThatThrownBy(() -> service.register(new RegisterRequest("dup@example.com", "Secret1234", "User")))
                .isInstanceOf(EmailAlreadyExistsException.class);
    }

    @Test
    void register_c2cRoleNotSeeded_throwsRoleNotFound() {
        when(userRepository.existsByEmail(anyString())).thenReturn(false);
        when(roleRepository.findByName("C2C_CUSTOMER")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.register(new RegisterRequest("user@test.com", "Secret1234", "User")))
                .isInstanceOf(RoleNotFoundException.class);
    }

    // ── API KEY CREATION ──────────────────────────────────────────────────────

    @Test
    void createApiKey_firstKey_returnsRawKeyOnce() {
        when(apiKeyRepository.countByUserIdAndActiveTrue(adminId)).thenReturn(0L);
        when(userRepository.findById(adminId)).thenReturn(Optional.of(adminUser));

        ApiKey saved = realApiKey(adminUser, "oms-prod");
        when(apiKeyRepository.save(any(ApiKey.class))).thenReturn(saved);

        ApiKeyCreateResponse resp = service.createApiKey(adminId, new ApiKeyCreateRequest("oms-prod"));

        assertThat(resp.label()).isEqualTo("oms-prod");
        assertThat(resp.rawKey()).isNotBlank();
        // 32 random bytes base64-url-no-padding = 43 chars
        assertThat(resp.rawKey()).hasSize(43);
    }

    @Test
    void createApiKey_rawKeysAreUniqueAcrossCalls() {
        when(apiKeyRepository.countByUserIdAndActiveTrue(adminId)).thenReturn(0L);
        when(userRepository.findById(adminId)).thenReturn(Optional.of(adminUser));

        ApiKey saved1 = realApiKey(adminUser, "key-1");
        ApiKey saved2 = realApiKey(adminUser, "key-2");
        when(apiKeyRepository.save(any(ApiKey.class))).thenReturn(saved1, saved2);

        ApiKeyCreateResponse r1 = service.createApiKey(adminId, new ApiKeyCreateRequest("key-1"));
        ApiKeyCreateResponse r2 = service.createApiKey(adminId, new ApiKeyCreateRequest("key-2"));

        assertThat(r1.rawKey()).isNotEqualTo(r2.rawKey());
    }

    @Test
    void createApiKey_capAt10_throwsApiKeyCapExceeded() {
        when(apiKeyRepository.countByUserIdAndActiveTrue(adminId)).thenReturn(10L);

        assertThatThrownBy(() -> service.createApiKey(adminId, new ApiKeyCreateRequest("extra")))
                .isInstanceOf(ApiKeyCapExceededException.class);
    }

    @Test
    void createApiKey_exactly9Keys_allowsCreation() {
        when(apiKeyRepository.countByUserIdAndActiveTrue(adminId)).thenReturn(9L);
        when(userRepository.findById(adminId)).thenReturn(Optional.of(adminUser));
        when(apiKeyRepository.save(any(ApiKey.class))).thenReturn(realApiKey(adminUser, "key-10"));

        ApiKeyCreateResponse resp = service.createApiKey(adminId, new ApiKeyCreateRequest("key-10"));
        assertThat(resp).isNotNull();
    }

    // ── API KEY REVOCATION ────────────────────────────────────────────────────

    @Test
    void revokeApiKey_ownerRevokesOwnKey_success() {
        UUID keyId = UUID.randomUUID();
        ApiKey apiKey = realApiKey(adminUser, "my-key");
        ReflectionTestUtils.setField(apiKey, "id", keyId);
        when(apiKeyRepository.findById(keyId)).thenReturn(Optional.of(apiKey));

        service.revokeApiKey(keyId, adminId);

        assertThat(apiKey.isActive()).isFalse();
        verify(apiKeyRepository).save(apiKey);
    }

    @Test
    void revokeApiKey_adminRevokesOthersKey_success() {
        UUID keyId = UUID.randomUUID();
        UUID b2bUserId = UUID.randomUUID();
        Role b2bRole = realRole("B2B_USER", false);
        User b2bUser = realUser(b2bUserId, "farhan@farhankart.com", b2bRole, null);

        ApiKey apiKey = realApiKey(b2bUser, "oms-prod");
        ReflectionTestUtils.setField(apiKey, "id", keyId);
        when(apiKeyRepository.findById(keyId)).thenReturn(Optional.of(apiKey));
        when(userRepository.findById(adminId)).thenReturn(Optional.of(adminUser));

        service.revokeApiKey(keyId, adminId);

        assertThat(apiKey.isActive()).isFalse();
    }

    @Test
    void revokeApiKey_nonOwnerNonAdmin_throwsForbidden() {
        UUID keyId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        UUID attackerId = UUID.randomUUID();

        Role ownerRole = realRole("B2B_USER", false);
        User owner = realUser(ownerId, "owner@test.com", ownerRole, null);
        ApiKey apiKey = realApiKey(owner, "key");
        ReflectionTestUtils.setField(apiKey, "id", keyId);
        when(apiKeyRepository.findById(keyId)).thenReturn(Optional.of(apiKey));

        Role daRole = realRole("DELIVERY_ASSOCIATE", true);
        User attacker = realUser(attackerId, "da@test.com", daRole, "MUM");
        when(userRepository.findById(attackerId)).thenReturn(Optional.of(attacker));

        assertThatThrownBy(() -> service.revokeApiKey(keyId, attackerId))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void revokeApiKey_keyNotFound_throwsUserNotFound() {
        when(apiKeyRepository.findById(any(UUID.class))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.revokeApiKey(UUID.randomUUID(), adminId))
                .isInstanceOf(UserNotFoundException.class);
    }

    // ── LIST API KEYS ─────────────────────────────────────────────────────────

    @Test
    void listApiKeys_returnsAllKeysForUser() {
        ApiKey k1 = realApiKey(adminUser, "oms-prod");
        ApiKey k2 = realApiKey(adminUser, "staging");
        k2.setActive(false);
        k2.setLastUsedAt(Instant.now());

        when(apiKeyRepository.findAllByUserId(adminId)).thenReturn(List.of(k1, k2));

        List<ApiKeyResponse> keys = service.listApiKeys(adminId);

        assertThat(keys).hasSize(2);
        assertThat(keys.get(0).label()).isEqualTo("oms-prod");
        assertThat(keys.get(0).active()).isTrue();
        assertThat(keys.get(1).label()).isEqualTo("staging");
        assertThat(keys.get(1).active()).isFalse();
    }

    @Test
    void listApiKeys_noKeys_returnsEmpty() {
        when(apiKeyRepository.findAllByUserId(adminId)).thenReturn(List.of());

        assertThat(service.listApiKeys(adminId)).isEmpty();
    }

    // ── PASSWORD MANAGEMENT ───────────────────────────────────────────────────

    @Test
    void resetPassword_success_setsHashAndMustChangeFlag() {
        UUID targetId = UUID.randomUUID();
        User target = realUser(targetId, "arjun@oneday.in", adminRole, null);
        when(userRepository.findById(targetId)).thenReturn(Optional.of(target));
        when(passwordEncoder.encode("NewPass1!")).thenReturn("new-hash");

        service.resetPassword(targetId, "NewPass1!", adminId);

        assertThat(target.getPasswordHash()).isEqualTo("new-hash");
        assertThat(target.isMustChangePassword()).isTrue();
        verify(userRepository).save(target);
        verify(auditLogRepository).save(any(RoleAuditLog.class));
    }

    @Test
    void resetPassword_targetNotFound_throwsUserNotFound() {
        when(userRepository.findById(any(UUID.class))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resetPassword(UUID.randomUUID(), "NewPass1!", adminId))
                .isInstanceOf(UserNotFoundException.class);
    }

    @Test
    void changePassword_success_clearsForceFlag() {
        when(userRepository.findById(adminId)).thenReturn(Optional.of(adminUser));
        when(passwordEncoder.matches("old-pass", "$2a$hash")).thenReturn(true);
        when(passwordEncoder.encode("new-pass")).thenReturn("new-hash");

        service.changePassword(adminId, "old-pass", "new-pass");

        assertThat(adminUser.getPasswordHash()).isEqualTo("new-hash");
        assertThat(adminUser.isMustChangePassword()).isFalse();
    }

    @Test
    void changePassword_whenMustChangePasswordTrue_clearsFlag() {
        // Simulates an onboarding-approved user forced to change on first login
        User approvedUser = realUser(UUID.randomUUID(), "new@vendor.in", adminRole, null);
        approvedUser.setMustChangePassword(true);
        when(userRepository.findById(approvedUser.getId())).thenReturn(Optional.of(approvedUser));
        when(passwordEncoder.matches("temp-pass", "$2a$hash")).thenReturn(true);
        when(passwordEncoder.encode("new-pass")).thenReturn("new-hash");

        service.changePassword(approvedUser.getId(), "temp-pass", "new-pass");

        assertThat(approvedUser.isMustChangePassword()).isFalse();
        assertThat(approvedUser.getPasswordHash()).isEqualTo("new-hash");
    }

    @Test
    void changePassword_wrongCurrentPassword_throwsBadCredentials() {
        when(userRepository.findById(adminId)).thenReturn(Optional.of(adminUser));
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(false);

        assertThatThrownBy(() -> service.changePassword(adminId, "wrong", "new-pass"))
                .isInstanceOf(BadCredentialsException.class);
    }

    // ── TOKEN VALIDATION ──────────────────────────────────────────────────────

    @Test
    void validateToken_validToken_returnsActiveUser() {
        Claims claims = realClaims(adminId.toString());
        when(jwtService.parseToken("valid.jwt.token")).thenReturn(claims);
        when(userRepository.findActiveByIdWithRole(adminId)).thenReturn(Optional.of(adminUser));

        User result = service.validateToken("valid.jwt.token");

        assertThat(result).isEqualTo(adminUser);
    }

    @Test
    void validateToken_jwtException_throwsBadCredentials() {
        when(jwtService.parseToken(anyString())).thenThrow(new io.jsonwebtoken.JwtException("expired"));

        assertThatThrownBy(() -> service.validateToken("bad.token"))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void validateToken_malformedSubject_throwsBadCredentials() {
        Claims claims = realClaims("not-a-uuid");
        when(jwtService.parseToken(anyString())).thenReturn(claims);

        assertThatThrownBy(() -> service.validateToken("bad.token"))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void validateToken_userDeactivatedAfterTokenIssued_throwsUserNotFound() {
        Claims claims = realClaims(adminId.toString());
        when(jwtService.parseToken("valid.jwt.token")).thenReturn(claims);
        when(userRepository.findActiveByIdWithRole(adminId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.validateToken("valid.jwt.token"))
                .isInstanceOf(UserNotFoundException.class);
    }

    // ── HELPERS ───────────────────────────────────────────────────────────────

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

    static User realUser(UUID id, String email, Role role, String cityId) {
        User user = new User();
        ReflectionTestUtils.setField(user, "id", id);
        user.setEmail(email);
        user.setPasswordHash("$2a$hash");
        user.setName("Test User");
        user.setRole(role);
        user.setCityId(cityId);
        user.setActive(true);
        user.setMustChangePassword(false);
        return user;
    }

    private static ApiKey realApiKey(User owner, String label) {
        ApiKey key = new ApiKey();
        ReflectionTestUtils.setField(key, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(key, "createdAt", Instant.now());
        key.setKeyHash("sha256-hash-" + label);
        key.setUser(owner);
        key.setLabel(label);
        key.setActive(true);
        return key;
    }

    private static Claims realClaims(String subject) {
        Map<String, Object> map = new HashMap<>();
        map.put(Claims.SUBJECT, subject);
        return new DefaultClaims(map);
    }
}
