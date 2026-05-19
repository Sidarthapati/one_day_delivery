package com.oneday.grid.service;

import com.oneday.grid.domain.Grid;
import com.oneday.grid.dto.response.ServiceabilityResponse;
import com.oneday.grid.dto.response.TileAtResponse;

import java.util.UUID;

public interface GridService {
    ServiceabilityResponse checkServiceability(UUID cityId, String pincode);
    TileAtResponse getTileAt(UUID cityId, double lat, double lon);

    // cityCode maps to classpath:serviceability/{cityCode}.yaml
    void initializeGrid(UUID cityId, String cityCode);

    Grid getGrid(UUID cityId);
}
