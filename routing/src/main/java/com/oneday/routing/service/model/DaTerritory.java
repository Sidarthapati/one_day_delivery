package com.oneday.routing.service.model;

import java.util.List;
import java.util.UUID;

/**
 * A DA's territory for a planning date — M6's internal view of grid's input (the {@code
 * GridDataAdapter} maps grid DTOs onto this so nothing else in M6 touches grid types).
 */
public record DaTerritory(UUID daId, List<TerritoryHex> hexes) {}
