package com.oneday.auth.dto.response;

import java.util.UUID;

public record RoleResponse(
        UUID id,
        String name,
        String displayName,
        boolean cityScoped,
        boolean builtin,
        boolean active
) {}
