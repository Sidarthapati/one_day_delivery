package com.oneday.grid.dto.response;

import java.util.List;
import java.util.UUID;

/**
 * A DA's territory for a date: the hexes it serves (each with demand + corner vertices). This is
 * M6's primary planning input — DA → hexes → vertices in one query (the §6 inputs row "DA
 * territories"). Only ACTIVE assignments for the date are returned.
 */
public record DaTerritoryResponse(
        UUID daId,
        List<TerritoryHexResponse> hexes
) {}
