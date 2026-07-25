package com.oneday.auth.service.impl;

import com.oneday.auth.config.OtpProperties;
import com.oneday.auth.domain.AuthProvider;
import com.oneday.auth.domain.OtpChallenge;
import com.oneday.auth.domain.Role;
import com.oneday.auth.domain.User;
import com.oneday.auth.dto.request.GoogleLoginRequest;
import com.oneday.auth.dto.request.OtpRequestRequest;
import com.oneday.auth.dto.request.OtpVerifyRequest;
import com.oneday.auth.dto.response.LoginResponse;
import com.oneday.auth.dto.response.OtpRequestResponse;
import com.oneday.auth.exception.InvalidOtpException;
import com.oneday.auth.repository.ApiKeyRepository;
import com.oneday.auth.repository.OtpChallengeRepository;
import com.oneday.auth.repository.RoleAuditLogRepository;
import com.oneday.auth.repository.RoleRepository;
import com.oneday.auth.repository.UserRepository;
import com.oneday.auth.service.GoogleTokenVerifier;
import com.oneday.auth.service.JwtService;
import com.oneday.auth.service.OtpSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Unit tests for the Google + phone-OTP sign-in flows (repositories & Google verifier mocked). */
class AuthServiceSocialOtpTest {

    private static final BCryptPasswordEncoder OTP_ENCODER = new BCryptPasswordEncoder(4);

    private UserRepository userRepository;
    private OtpChallengeRepository otpRepository;
    private RoleRepository roleRepository;
    private RoleAuditLogRepository auditLogRepository;
    private JwtService jwtService;
    private GoogleTokenVerifier googleTokenVerifier;
    private OtpSender otpSender;
    private AuthServiceImpl service;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        otpRepository = mock(OtpChallengeRepository.class);
        roleRepository = mock(RoleRepository.class);
        auditLogRepository = mock(RoleAuditLogRepository.class);
        ApiKeyRepository apiKeyRepository = mock(ApiKeyRepository.class);
        jwtService = mock(JwtService.class);
        PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
        googleTokenVerifier = mock(GoogleTokenVerifier.class);
        otpSender = mock(OtpSender.class);

        Role c2c = new Role();
        c2c.setName("C2C_CUSTOMER");
        when(roleRepository.findByName("C2C_CUSTOMER")).thenReturn(Optional.of(c2c));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(jwtService.createToken(any(User.class))).thenReturn("signed-jwt");
        when(jwtService.expiryFor(any(User.class))).thenReturn(Instant.now().plusSeconds(3600));

        service = new AuthServiceImpl(userRepository, roleRepository, apiKeyRepository,
                auditLogRepository, otpRepository, jwtService, passwordEncoder,
                googleTokenVerifier, otpSender, new OtpProperties());
    }

    @Test
    void google_newUser_isCreatedAsC2cWithProviderAndTokenReturned() {
        when(googleTokenVerifier.verify("gtoken"))
                .thenReturn(new GoogleTokenVerifier.GoogleUser("jo@gmail.com", "google-sub-123", "Jo Rider"));
        when(userRepository.findByEmail("jo@gmail.com")).thenReturn(Optional.empty());

        LoginResponse res = service.loginWithGoogle(new GoogleLoginRequest("gtoken"));

        assertThat(res.token()).isEqualTo("signed-jwt");
        assertThat(res.role()).isEqualTo("C2C_CUSTOMER");
        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(saved.capture());
        assertThat(saved.getValue().getEmail()).isEqualTo("jo@gmail.com");
        assertThat(saved.getValue().getAuthProvider()).isEqualTo(AuthProvider.GOOGLE);
        assertThat(saved.getValue().getProviderSubject()).isEqualTo("google-sub-123");
        assertThat(saved.getValue().getPasswordHash()).isNull();
    }

    @Test
    void google_existingUser_logsInWithoutCreatingAnother() {
        User existing = new User();
        existing.setEmail("jo@gmail.com");
        existing.setName("Jo");
        existing.setActive(true);
        Role r = new Role(); r.setName("C2C_CUSTOMER"); existing.setRole(r);
        when(googleTokenVerifier.verify("gtoken"))
                .thenReturn(new GoogleTokenVerifier.GoogleUser("jo@gmail.com", "sub", "Jo"));
        when(userRepository.findByEmail("jo@gmail.com")).thenReturn(Optional.of(existing));

        LoginResponse res = service.loginWithGoogle(new GoogleLoginRequest("gtoken"));

        assertThat(res.token()).isEqualTo("signed-jwt");
        verify(userRepository, never()).save(any());
    }

    @Test
    void otpRequest_replacesPriorChallenge_savesHashedCode_andSends() {
        OtpRequestResponse res = service.requestOtp(new OtpRequestRequest("+919876543210"));

        assertThat(res.sent()).isTrue();
        assertThat(res.expiresInSeconds()).isEqualTo(300);
        verify(otpRepository).deleteByPhone("+919876543210");
        ArgumentCaptor<OtpChallenge> ch = ArgumentCaptor.forClass(OtpChallenge.class);
        verify(otpRepository).save(ch.capture());
        assertThat(ch.getValue().getOtpHash()).startsWith("$2a$04$"); // stored as a bcrypt(4) hash, never cleartext
        assertThat(ch.getValue().isConsumed()).isFalse();
        verify(otpSender).send(eq("+919876543210"), anyString());
    }

    @Test
    void otpVerify_correctCode_createsUserByPhone_marksConsumed_andReturnsToken() {
        OtpChallenge challenge = new OtpChallenge();
        challenge.setPhone("+919876543210");
        challenge.setOtpHash(OTP_ENCODER.encode("123456"));
        challenge.setExpiresAt(Instant.now().plusSeconds(120));
        when(otpRepository.findTopByPhoneAndConsumedFalseOrderByCreatedAtDesc("+919876543210"))
                .thenReturn(Optional.of(challenge));
        when(userRepository.findByPhone("+919876543210")).thenReturn(Optional.empty());

        LoginResponse res = service.verifyOtp(new OtpVerifyRequest("+919876543210", "123456"));

        assertThat(res.token()).isEqualTo("signed-jwt");
        assertThat(challenge.isConsumed()).isTrue();
        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(saved.capture());
        assertThat(saved.getValue().getPhone()).isEqualTo("+919876543210");
        assertThat(saved.getValue().getAuthProvider()).isEqualTo(AuthProvider.PHONE_OTP);
        assertThat(saved.getValue().getEmail()).isNull();
    }

    @Test
    void otpVerify_wrongCode_incrementsAttempts_andThrows() {
        OtpChallenge challenge = new OtpChallenge();
        challenge.setPhone("+919876543210");
        challenge.setOtpHash(OTP_ENCODER.encode("123456"));
        challenge.setExpiresAt(Instant.now().plusSeconds(120));
        when(otpRepository.findTopByPhoneAndConsumedFalseOrderByCreatedAtDesc("+919876543210"))
                .thenReturn(Optional.of(challenge));

        assertThatThrownBy(() -> service.verifyOtp(new OtpVerifyRequest("+919876543210", "000000")))
                .isInstanceOf(InvalidOtpException.class);
        assertThat(challenge.getAttempts()).isEqualTo((short) 1);
        verify(userRepository, never()).save(any());
    }

    @Test
    void otpVerify_expiredCode_throws() {
        OtpChallenge challenge = new OtpChallenge();
        challenge.setPhone("+919876543210");
        challenge.setOtpHash(OTP_ENCODER.encode("123456"));
        challenge.setExpiresAt(Instant.now().minusSeconds(1));
        when(otpRepository.findTopByPhoneAndConsumedFalseOrderByCreatedAtDesc("+919876543210"))
                .thenReturn(Optional.of(challenge));

        assertThatThrownBy(() -> service.verifyOtp(new OtpVerifyRequest("+919876543210", "123456")))
                .isInstanceOf(InvalidOtpException.class);
    }
}
