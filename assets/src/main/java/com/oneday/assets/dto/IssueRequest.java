package com.oneday.assets.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/** Issue an asset to a delivery associate. */
public record IssueRequest(@NotNull UUID daId, String reason) {}
