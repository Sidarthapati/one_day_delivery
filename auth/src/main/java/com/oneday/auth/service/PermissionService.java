package com.oneday.auth.service;

import com.oneday.auth.dto.response.PermissionCheckResponse;

import java.util.UUID;

public interface PermissionService {
    PermissionCheckResponse canDo(UUID userId, String action, String cityId);
}
