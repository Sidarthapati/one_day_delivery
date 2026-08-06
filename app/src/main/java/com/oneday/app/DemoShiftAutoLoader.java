package com.oneday.app;

import com.oneday.dispatch.demo.DispatchDemoService;
import com.oneday.grid.service.GridService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

/**
 * Demo convenience ({@code @Profile("!prod")}): auto-loads the M5 shift for each demo city on startup,
 * so the Dispatch board + DA App show DAs immediately without a manual "Load shift" click. The shift
 * roster is in-memory and is wiped on every restart — this re-seeds it.
 *
 * <p>Best-effort: a city with no approved territories for today simply loads 0 DAs (run Execution →
 * "Prepare today's plan" to create them). Runs after {@link GridSeeder} ({@code @Order(100)}) so the
 * grids exist first. A failure for one city never blocks the others or startup.</p>
 */
@Component
@Profile("!prod")
@Order(200)
class DemoShiftAutoLoader implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoShiftAutoLoader.class);

    private static final List<String> CITY_CODES =
            List.of("delhi", "mumbai", "bangalore", "hyderabad", "chennai");

    private final GridService gridService;
    private final DispatchDemoService dispatchDemoService;

    DemoShiftAutoLoader(GridService gridService, DispatchDemoService dispatchDemoService) {
        this.gridService = gridService;
        this.dispatchDemoService = dispatchDemoService;
    }

    @Override
    public void run(ApplicationArguments args) {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        for (String cityCode : CITY_CODES) {
            try {
                UUID cityId = gridService.resolveCityId(cityCode);
                int das = dispatchDemoService.loadShift(cityId, today).summary().das();
                if (das > 0) {
                    log.info("[shift-autoload] {} → {} DAs on shift for {}", cityCode, das, today);
                } else {
                    log.info("[shift-autoload] {} → no approved territories for {} (run Execution → Prepare)", cityCode, today);
                }
            } catch (Exception e) {
                log.warn("[shift-autoload] could not load shift for {}: {}", cityCode, e.toString());
            }
        }
    }
}
