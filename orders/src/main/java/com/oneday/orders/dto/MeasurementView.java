package com.oneday.orders.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A recorded measurement, for the DA app (post-scan verdict) and the ops/dispute console.
 * {@code evidenceUrls} is populated only for console reads (short-lived presigned GETs); the app
 * gets the verdict fields it needs to show "matches / over-declared".
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MeasurementView(
        UUID id,
        String source,
        String method,
        String status,
        boolean ok,
        Double lengthCm,
        Double widthCm,
        Double heightCm,
        Integer volumetricWeightGrams,
        Float confidence,
        Short declaredLengthCm,
        Short declaredWidthCm,
        Short declaredHeightCm,
        boolean overDeclared,
        String discrepancyDetail,
        List<String> evidenceUrls,
        Instant createdAt) {}
