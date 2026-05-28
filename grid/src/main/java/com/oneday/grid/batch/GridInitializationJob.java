package com.oneday.grid.batch;

import com.oneday.grid.service.GridService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

// Admin-triggered via POST /grid/admin/init?city_id=...&city_code=...
// Idempotent guard: GridService.initializeGrid will throw if the city already has a grid.
@Component
public class GridInitializationJob {

    private static final Logger log = LoggerFactory.getLogger(GridInitializationJob.class);

    private final GridService gridService;
    private final OsrmMatrixRefreshJob osrmMatrixRefreshJob;

    GridInitializationJob(GridService gridService, OsrmMatrixRefreshJob osrmMatrixRefreshJob) {
        this.gridService = gridService;
        this.osrmMatrixRefreshJob = osrmMatrixRefreshJob;
    }

    public void initialize(UUID cityId, String cityCode) {
        log.info("GridInitializationJob starting for cityId={} cityCode={}", cityId, cityCode);
        gridService.initializeGrid(cityId, cityCode);
        log.info("Grid rows persisted for cityId={}; triggering OSRM matrix refresh", cityId);
        osrmMatrixRefreshJob.refresh(cityId);
        log.info("GridInitializationJob complete for cityId={}", cityId);
    }
}
