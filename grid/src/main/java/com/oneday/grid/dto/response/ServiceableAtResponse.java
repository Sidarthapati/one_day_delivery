package com.oneday.grid.dto.response;

import java.util.UUID;

/**
 * Coordinate-based serviceability: resolves a WGS84 point to whichever city's H3 grid
 * contains it. {@code serviceable} is true only when the containing hex is active.
 * All city/hex fields are null when the point falls outside every seeded city grid.
 */
public record ServiceableAtResponse(
        boolean serviceable,
        String cityCode,
        UUID cityId,
        UUID hexId,
        String h3Index
) {}
