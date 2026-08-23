package com.oneday.exceptions.dto;

import java.util.List;
import java.util.UUID;

/**
 * Per-case outcome of a batch resolve. Each case is applied in its own transaction, so a partial failure
 * (a closed or missing case) doesn't roll back the rest — hence a per-id result list rather than 204.
 */
public record BatchResolveResponse(List<Item> results) {

    public enum Status { OK, ALREADY_CLOSED, NOT_FOUND, ERROR }

    public record Item(UUID caseId, Status status, String message) {
    }
}
