package com.oneday.auth.dto.request;

import jakarta.validation.constraints.NotBlank;

public record ApiKeyCreateRequest(@NotBlank String label) {}
