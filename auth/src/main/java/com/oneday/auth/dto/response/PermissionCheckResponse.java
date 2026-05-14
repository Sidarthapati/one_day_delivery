package com.oneday.auth.dto.response;

public record PermissionCheckResponse(boolean allowed, String reason) {}
