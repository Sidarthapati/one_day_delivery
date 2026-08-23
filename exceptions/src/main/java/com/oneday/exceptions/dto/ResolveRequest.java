package com.oneday.exceptions.dto;

import com.oneday.exceptions.domain.ResolveAction;
import jakarta.validation.constraints.NotNull;

/** The problem-solve action a manager/agent takes on a case, with optional free-text notes. */
public record ResolveRequest(@NotNull ResolveAction action, String notes) {
}
