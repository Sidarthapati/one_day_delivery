package com.oneday.hub.service.port;

import com.oneday.grid.dto.response.DaTerritoryResponse;
import com.oneday.grid.dto.response.TerritoryHexResponse;
import com.oneday.grid.service.GridService;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * Real M3 wiring for {@link TerritoryPort}: scans the day's ACTIVE DA territories
 * ({@code GridService.getDaTerritories}) for the one whose hexes include the parcel's dest hex.
 * A hex covered by multiple DAs resolves to the first match (v1 — n_das_on_hex &gt; 1 is rare).
 * M3 does not yet expose zones, so {@code zoneId} is null until Q13 lands.
 */
@Component
class GridTerritoryAdapter implements TerritoryPort {

    private final GridService gridService;

    GridTerritoryAdapter(GridService gridService) {
        this.gridService = gridService;
    }

    @Override
    public Optional<DaTerritory> territoryForHex(UUID cityId, UUID destHexId, LocalDate date) {
        if (destHexId == null) {
            return Optional.empty();
        }
        for (DaTerritoryResponse territory : gridService.getDaTerritories(cityId, date)) {
            for (TerritoryHexResponse hex : territory.hexes()) {
                if (destHexId.equals(hex.hexId())) {
                    return Optional.of(new DaTerritory(territory.daId(), null));
                }
            }
        }
        return Optional.empty();
    }
}
