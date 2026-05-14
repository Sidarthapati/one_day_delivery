package com.oneday.auth.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.Set;

public record CreateRoleRequest(
        @NotBlank String name,
        @NotBlank String displayName,
        boolean cityScoped,
        @NotEmpty Set<String> permissions
) {}
