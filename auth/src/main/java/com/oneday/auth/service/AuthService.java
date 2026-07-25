package com.oneday.auth.service;

import com.oneday.auth.domain.User;
import com.oneday.auth.dto.request.*;
import com.oneday.auth.dto.response.ApiKeyCreateResponse;
import com.oneday.auth.dto.response.ApiKeyResponse;
import com.oneday.auth.dto.response.LoginResponse;
import com.oneday.auth.dto.response.OtpRequestResponse;

import java.util.List;
import java.util.UUID;

public interface AuthService {
    LoginResponse login(LoginRequest request);
    LoginResponse register(RegisterRequest request);
    LoginResponse loginWithGoogle(GoogleLoginRequest request);
    OtpRequestResponse requestOtp(OtpRequestRequest request);
    LoginResponse verifyOtp(OtpVerifyRequest request);
    User validateToken(String token);
    ApiKeyCreateResponse createApiKey(UUID userId, ApiKeyCreateRequest request);
    void revokeApiKey(UUID keyId, UUID requestingUserId);
    List<ApiKeyResponse> listApiKeys(UUID userId);
    void resetPassword(UUID targetId, String newPassword, UUID actorId);
    void changePassword(UUID userId, String currentPassword, String newPassword);
}
