package com.oneday.grid.dto.response;

import java.util.UUID;

public record ServiceabilityResponse(
        UUID cityId,
        String pincode,
        boolean serviceable,
        UUID hexId
) {}
