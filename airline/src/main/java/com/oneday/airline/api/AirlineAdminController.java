package com.oneday.airline.api;

import com.oneday.airline.schedule.AeroDataBoxScheduleIngestService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * Ops triggers for M9. {@code /schedule/refresh} runs the AeroDataBox schedule ingest on demand (the
 * "click a button" companion to the monthly cron). The ingest bean only exists when
 * {@code airline.aerodatabox.enabled=true}, so this returns 409 when the feed is off rather than
 * pretending to work. Auth is the app-level filter (same as the rest of {@code /airline}).
 */
@RestController
@RequestMapping("/airline/admin")
class AirlineAdminController {

    private final ObjectProvider<AeroDataBoxScheduleIngestService> ingest;

    AirlineAdminController(ObjectProvider<AeroDataBoxScheduleIngestService> ingest) {
        this.ingest = ingest;
    }

    @PostMapping("/schedule/refresh")
    Map<String, Object> refreshSchedule() {
        AeroDataBoxScheduleIngestService service = ingest.getIfAvailable();
        if (service == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "AeroDataBox schedule feed is disabled (airline.aerodatabox.enabled=false)");
        }
        int written = service.refresh();
        return Map.of("status", "ok", "legsUpserted", written);
    }
}
