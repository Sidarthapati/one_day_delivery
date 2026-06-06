package com.oneday.app;

import com.oneday.grid.service.GridService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

/**
 * Seeds the 5 v1 city grids on startup (local/demo only) so M3 serviceability is live —
 * the booking map's serviceability verdict and the booking gate both resolve against real
 * H3 hexes. Idempotent: a city whose grid already exists is skipped, so restarts are cheap.
 */
@Component
@Profile("!prod")
@Order(100)
class GridSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(GridSeeder.class);

    // cityCode must match grid.cities config keys and serviceability/{cityCode}.geojson.
    private static final List<String> CITY_CODES =
            List.of("delhi", "mumbai", "bangalore", "hyderabad", "chennai");

    private final GridService gridService;

    GridSeeder(GridService gridService) {
        this.gridService = gridService;
    }

    @Override
    public void run(ApplicationArguments args) {
        for (String cityCode : CITY_CODES) {
            try {
                UUID cityId = gridService.resolveCityId(cityCode);
                gridService.initializeGrid(cityId, cityCode);
                log.info("[grid-seeder] seeded H3 grid for {}", cityCode);
            } catch (ResponseStatusException rse) {
                // 409 = grid already exists → normal idempotent restart case.
                if (rse.getStatusCode().value() == 409) {
                    log.info("[grid-seeder] grid already present for {} — skipping", cityCode);
                } else {
                    log.warn("[grid-seeder] could not seed {}: {}", cityCode, rse.getMessage());
                }
            } catch (Exception e) {
                log.warn("[grid-seeder] could not seed {}: {}", cityCode, e.toString());
            }
        }
    }
}
