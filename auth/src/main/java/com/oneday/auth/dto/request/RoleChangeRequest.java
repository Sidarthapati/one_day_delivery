package com.oneday.auth.dto.request;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record RoleChangeRequest(
        @NotNull UUID newRoleId,
        String reason
) {}
