package com.oneday.assets.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/** Transfer an asset directly from the current DA to another DA (colleague handoff). */
public record TransferRequest(@NotNull UUID toDaId, String reason) {}
