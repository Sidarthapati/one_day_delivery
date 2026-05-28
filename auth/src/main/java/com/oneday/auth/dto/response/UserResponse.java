package com.oneday.auth.dto.response;

import java.util.UUID;

public record UserResponse(
        UUID id,
        String email,
        String name,
        String role,
        String cityId,
        boolean active
) {}
