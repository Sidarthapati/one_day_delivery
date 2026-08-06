package com.oneday.auth.service.impl;

import com.oneday.auth.config.OtpProperties;
import com.oneday.auth.domain.ApiKey;
import com.oneday.auth.domain.AuthProvider;
import com.oneday.auth.domain.OtpChallenge;
import com.oneday.auth.domain.Role;
import com.oneday.auth.domain.RoleAuditLog;
import com.oneday.auth.domain.User;
import com.oneday.auth.dto.request.ApiKeyCreateRequest;
import com.oneday.auth.dto.request.GoogleLoginRequest;
import com.oneday.auth.dto.request.LoginRequest;
import com.oneday.auth.dto.request.OtpRequestRequest;
import com.oneday.auth.dto.request.OtpVerifyRequest;
import com.oneday.auth.dto.request.RegisterRequest;
import com.oneday.auth.dto.response.ApiKeyCreateResponse;
import com.oneday.auth.dto.response.ApiKeyResponse;
import com.oneday.auth.dto.response.LoginResponse;
import com.oneday.auth.dto.response.OtpRequestResponse;
import com.oneday.auth.exception.ApiKeyCapExceededException;
import com.oneday.auth.exception.BadCredentialsException;
import com.oneday.auth.exception.EmailAlreadyExistsException;
import com.oneday.auth.exception.ForbiddenException;
import com.oneday.auth.exception.InvalidOtpException;
import com.oneday.auth.exception.RoleNotFoundException;
import com.oneday.auth.exception.UserNotFoundException;
import com.oneday.auth.repository.ApiKeyRepository;
import com.oneday.auth.repository.OtpChallengeRepository;
import com.oneday.auth.repository.RoleAuditLogRepository;
import com.oneday.auth.repository.RoleRepository;
import com.oneday.auth.repository.UserRepository;
import com.oneday.auth.service.AuthService;
import com.oneday.auth.service.GoogleTokenVerifier;
import com.oneday.auth.service.JwtService;
import com.oneday.auth.service.OtpSender;
import io.jsonwebtoken.JwtException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Service
class AuthServiceImpl implements AuthService {

    private static final int API_KEY_CAP = 10;
    private static final int RAW_KEY_BYTES = 32;

    // BCrypt cost 4 for short-lived OTPs — the TTL + single-use + attempt cap are the real
    // guarantees. Mirrors orders' PickupOtpService; cost 10 would add ~100ms with no gain here.
    private static final BCryptPasswordEncoder OTP_ENCODER = new BCryptPasswordEncoder(4);

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final ApiKeyRepository apiKeyRepository;
    private final RoleAuditLogRepository auditLogRepository;
    private final OtpChallengeRepository otpRepository;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;
    private final GoogleTokenVerifier googleTokenVerifier;
    private final OtpSender otpSender;
    private final OtpProperties otpProperties;
    private final SecureRandom secureRandom = new SecureRandom();

    AuthServiceImpl(UserRepository userRepository,
            RoleRepository roleRepository,
            ApiKeyRepository apiKeyRepository,
            RoleAuditLogRepository auditLogRepository,
            OtpChallengeRepository otpRepository,
            JwtService jwtService,
            PasswordEncoder passwordEncoder,
            GoogleTokenVerifier googleTokenVerifier,
            OtpSender otpSender,
            OtpProperties otpProperties) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.apiKeyRepository = apiKeyRepository;
        this.auditLogRepository = auditLogRepository;
        this.otpRepository = otpRepository;
        this.jwtService = jwtService;
        this.passwordEncoder = passwordEncoder;
        this.googleTokenVerifier = googleTokenVerifier;
        this.otpSender = otpSender;
        this.otpProperties = otpProperties;
    }

    @Override
    @Transactional
    public LoginResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(request.email())
                .filter(User::isActive)
                .orElseThrow(BadCredentialsException::new);

        // Social / phone-OTP accounts have no password — reject password login for them.
        if (user.getPasswordHash() == null
                || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new BadCredentialsException();
        }

        return toLoginResponse(user);
    }

    @Override
    @Transactional
    public LoginResponse register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            throw new EmailAlreadyExistsException(request.email());
        }

        var c2cRole = roleRepository.findByName("C2C_CUSTOMER")
                .orElseThrow(() -> new RoleNotFoundException("C2C_CUSTOMER role not seeded"));

        var user = new User();
        user.setEmail(request.email());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setName(request.name());
        user.setPhone(request.phone());
        user.setRole(c2cRole);
        user.setActive(true);
        user.setMustChangePassword(false);
        user = userRepository.save(user);

        writeAuditLog(user.getId(), user.getId(), "CREATE", null, "C2C_CUSTOMER", null, null);
        return toLoginResponse(user);
    }

    @Override
    @Transactional
    public LoginResponse loginWithGoogle(GoogleLoginRequest request) {
        GoogleTokenVerifier.GoogleUser g = googleTokenVerifier.verify(request.idToken());

        // Existing account (linked by email, however it was first created) → just log in.
        User user = userRepository.findByEmail(g.email()).orElse(null);
        if (user == null) {
            user = new User();
            user.setEmail(g.email());
            user.setName(g.name() != null && !g.name().isBlank() ? g.name() : g.email());
            user.setAuthProvider(AuthProvider.GOOGLE);
            user.setProviderSubject(g.subject());
            user.setRole(customerRole());
            user.setActive(true);
            user.setMustChangePassword(false);
            user = userRepository.save(user);
            writeAuditLog(user.getId(), user.getId(), "CREATE", null, "C2C_CUSTOMER", null, "google");
        }
        if (!user.isActive()) {
            throw new BadCredentialsException();
        }
        return toLoginResponse(user);
    }

    @Override
    @Transactional
    public OtpRequestResponse requestOtp(OtpRequestRequest request) {
        // Replace any prior challenge for this phone so only the latest code is valid.
        otpRepository.deleteByPhone(request.phone());

        String code = generateNumericOtp(otpProperties.getLength());
        OtpChallenge challenge = new OtpChallenge();
        challenge.setPhone(request.phone());
        challenge.setOtpHash(OTP_ENCODER.encode(code));
        challenge.setExpiresAt(Instant.now().plusSeconds(otpProperties.getTtlSeconds()));
        challenge.setAttempts((short) 0);
        challenge.setConsumed(false);
        otpRepository.save(challenge);

        otpSender.send(request.phone(), code);
        // Response never reveals whether the phone maps to an existing user.
        return new OtpRequestResponse(true, otpProperties.getTtlSeconds());
    }

    @Override
    @Transactional
    public LoginResponse verifyOtp(OtpVerifyRequest request) {
        OtpChallenge challenge = otpRepository
                .findTopByPhoneAndConsumedFalseOrderByCreatedAtDesc(request.phone())
                .orElseThrow(() -> new InvalidOtpException("No active code — request a new one"));

        if (challenge.getExpiresAt().isBefore(Instant.now())) {
            throw new InvalidOtpException("Code has expired — request a new one");
        }
        if (challenge.getAttempts() >= otpProperties.getMaxAttempts()) {
            throw new InvalidOtpException("Too many attempts — request a new one");
        }
        if (!OTP_ENCODER.matches(request.otp(), challenge.getOtpHash())) {
            challenge.setAttempts((short) (challenge.getAttempts() + 1));
            otpRepository.save(challenge);
            throw new InvalidOtpException("Incorrect code");
        }

        challenge.setConsumed(true);
        otpRepository.save(challenge);

        // Find-or-create the account keyed by phone.
        User user = userRepository.findByPhone(request.phone()).orElse(null);
        if (user == null) {
            user = new User();
            user.setPhone(request.phone());
            user.setName(request.phone()); // placeholder; user can set a display name later
            user.setAuthProvider(AuthProvider.PHONE_OTP);
            user.setRole(customerRole());
            user.setActive(true);
            user.setMustChangePassword(false);
            user = userRepository.save(user);
            writeAuditLog(user.getId(), user.getId(), "CREATE", null, "C2C_CUSTOMER", null, "phone-otp");
        }
        if (!user.isActive()) {
            throw new BadCredentialsException();
        }
        return toLoginResponse(user);
    }

    private Role customerRole() {
        return roleRepository.findByName("C2C_CUSTOMER")
                .orElseThrow(() -> new RoleNotFoundException("C2C_CUSTOMER role not seeded"));
    }

    private String generateNumericOtp(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(secureRandom.nextInt(10));
        }
        return sb.toString();
    }

    @Override
    @Transactional(readOnly = true)
    public User validateToken(String token) {
        UUID userId;
        try {
            var claims = jwtService.parseToken(token);
            userId = UUID.fromString(claims.getSubject());
        } catch (JwtException | IllegalArgumentException e) {
            throw new BadCredentialsException();
        }
        return userRepository.findActiveByIdWithRole(userId)
                .orElseThrow(() -> new UserNotFoundException("User not found or inactive"));
    }

    @Override
    @Transactional
    public ApiKeyCreateResponse createApiKey(UUID userId, ApiKeyCreateRequest request) {
        if (apiKeyRepository.countByUserIdAndActiveTrue(userId) >= API_KEY_CAP) {
            throw new ApiKeyCapExceededException();
        }

        byte[] bytes = new byte[RAW_KEY_BYTES];
        secureRandom.nextBytes(bytes);
        String rawKey = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        String keyHash = sha256Hex(rawKey);

        var user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException("User not found"));

        var apiKey = new ApiKey();
        apiKey.setKeyHash(keyHash);
        apiKey.setUser(user);
        apiKey.setLabel(request.label());
        apiKey.setActive(true);
        apiKey = apiKeyRepository.save(apiKey);

        return new ApiKeyCreateResponse(apiKey.getId(), apiKey.getLabel(), rawKey, apiKey.getCreatedAt());
    }

    @Override
    @Transactional
    public void revokeApiKey(UUID keyId, UUID requestingUserId) {
        var apiKey = apiKeyRepository.findById(keyId)
                .orElseThrow(() -> new UserNotFoundException("API key not found"));

        boolean isOwner = apiKey.getUser().getId().equals(requestingUserId);
        boolean isAdmin = userRepository.findById(requestingUserId)
                .map(u -> "ADMIN".equals(u.getRole().getName()))
                .orElse(false);

        if (!isOwner && !isAdmin) {
            throw new ForbiddenException("You can only revoke your own API keys");
        }

        apiKey.setActive(false);
        apiKeyRepository.save(apiKey);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ApiKeyResponse> listApiKeys(UUID userId) {
        return apiKeyRepository.findAllByUserId(userId).stream()
                .map(k -> new ApiKeyResponse(k.getId(), k.getLabel(), k.isActive(),
                        k.getLastUsedAt(), k.getCreatedAt()))
                .toList();
    }

    @Override
    @Transactional
    public void resetPassword(UUID targetId, String newPassword, UUID actorId) {
        var target = userRepository.findById(targetId)
                .orElseThrow(() -> new UserNotFoundException("User not found"));
        target.setPasswordHash(passwordEncoder.encode(newPassword));
        target.setMustChangePassword(true);
        userRepository.save(target);
        writeAuditLog(actorId, targetId, "PASSWORD_RESET", null, null, target.getCityId(), null);
    }

    @Override
    @Transactional
    public void changePassword(UUID userId, String currentPassword, String newPassword) {
        var user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException("User not found"));
        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new BadCredentialsException();
        }
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setMustChangePassword(false);
        userRepository.save(user);
    }

    private LoginResponse toLoginResponse(User user) {
        String token = jwtService.createToken(user);
        Instant expiresAt = jwtService.expiryFor(user);
        return new LoginResponse(token, expiresAt, user.getRole().getName(),
                user.getCityId(), user.getName(), user.getPhone(), user.isMustChangePassword());
    }

    private void writeAuditLog(UUID actorId, UUID targetUserId, String action,
            String previousRole, String newRole, String cityId, String reason) {
        var log = new RoleAuditLog();
        log.setActorId(actorId);
        log.setTargetUserId(targetUserId);
        log.setAction(action);
        log.setPreviousRole(previousRole);
        log.setNewRole(newRole);
        log.setCityId(cityId);
        log.setReason(reason);
        auditLogRepository.save(log);
    }

    private static String sha256Hex(String input) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }
}
