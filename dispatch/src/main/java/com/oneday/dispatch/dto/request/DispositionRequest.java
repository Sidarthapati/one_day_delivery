package com.oneday.dispatch.dto.request;

import com.oneday.dispatch.domain.DispositionCategory;
import com.oneday.dispatch.domain.DispositionReason;

/**
 * A DA raising a disposition. {@code minutes} is the requested duration (BREAK / AUXILIARY); ignored
 * for DAY_OFF. {@code note} is optional free text (used mainly for AUXILIARY "what work").
 */
public record DispositionRequest(
        DispositionCategory category,
        DispositionReason reason,
        Integer minutes,
        String note) {
}
