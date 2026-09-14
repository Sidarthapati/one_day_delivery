package com.oneday.auth.dto.request;

import com.oneday.common.domain.Shift;

/**
 * Wizard step 2 — preferred base. City is our current "station" scope; {@code stationLabel} is a
 * free-text branch name (no station entity yet). Shift becomes the DA's roster shift on provisioning.
 */
public record CandidateLocationRequest(
        String cityId,
        String stationLabel,
        Shift shift
) {}
