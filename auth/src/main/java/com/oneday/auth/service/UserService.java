package com.oneday.auth.service;

import com.oneday.auth.dto.request.RegisterUserRequest;
import com.oneday.auth.dto.request.RoleChangeRequest;
import com.oneday.auth.dto.request.UpdateProfileRequest;
import com.oneday.auth.dto.response.AuditLogResponse;
import com.oneday.auth.dto.response.UserResponse;

import java.util.List;
import java.util.UUID;

public interface UserService {
    UserResponse register(RegisterUserRequest request, UUID actorId);
    void changeRole(UUID targetUserId, RoleChangeRequest request, UUID actorId);
    void deactivate(UUID userId, UUID actorId);
    void reactivate(UUID userId, UUID actorId);
    void updateProfile(UUID userId, UpdateProfileRequest request);
    UserResponse getUser(UUID userId);
    UserResponse getUserByEmail(String email);
    List<AuditLogResponse> getAuditLog(UUID userId);
}
