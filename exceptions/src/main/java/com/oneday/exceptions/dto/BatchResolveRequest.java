package com.oneday.exceptions.dto;

import com.oneday.exceptions.domain.ResolveAction;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

/**
 * Apply one problem-solve action to many cases at once — a DA no-show or a missed-flight bag lands many
 * failures together, so ops act on them in one go. v1 keys on case-ids (the queue UI already carries
 * them); tracking-id entry is a follow-up (see issue #128).
 */
public record BatchResolveRequest(
        @NotNull ResolveAction action,
        @NotEmpty List<UUID> caseIds,
        String notes) {
}
