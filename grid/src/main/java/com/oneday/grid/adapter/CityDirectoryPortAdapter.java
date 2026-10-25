package com.oneday.grid.adapter;

import com.oneday.common.port.CityDirectoryPort;
import com.oneday.grid.service.GridService;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.UUID;

/**
 * M3's implementation of {@link CityDirectoryPort}: normalises any accepted city identifier
 * (code, IATA alias, or UUID) to the canonical grid city code via {@link GridService}.
 */
@Component
class CityDirectoryPortAdapter implements CityDirectoryPort {

    private final GridService gridService;

    CityDirectoryPortAdapter(GridService gridService) {
        this.gridService = gridService;
    }

    @Override
    public Optional<String> toCityCode(String cityIdOrCode) {
        if (cityIdOrCode == null || cityIdOrCode.isBlank()) {
            return Optional.empty();
        }
        String value = cityIdOrCode.trim();

        UUID cityId;
        try {
            cityId = UUID.fromString(value);              // already a UUID
        } catch (IllegalArgumentException notUuid) {
            try {
                cityId = gridService.resolveCityId(value); // code or IATA alias → UUID (404 if unknown)
            } catch (ResponseStatusException unknownCode) {
                return Optional.empty();
            }
        }
        // UUID → canonical code (null if the UUID isn't one of the configured cities).
        return Optional.ofNullable(gridService.resolveCityCode(cityId));
    }
}
