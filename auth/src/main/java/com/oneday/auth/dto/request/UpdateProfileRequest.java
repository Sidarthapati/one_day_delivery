package com.oneday.auth.dto.request;

import jakarta.validation.constraints.NotBlank;

public record UpdateProfileRequest(@NotBlank String name) {}
