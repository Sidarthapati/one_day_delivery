package com.oneday.app.demo;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Demo-only control surface for the M7 journey driver. Under {@code /api/**} so {@code DemoSecurityConfig}
 * (permitAll, !prod) already opens it — no auth needed for the demo site. {@code @Profile("!prod")}.
 */
@RestController
@RequestMapping("/api/demo/journey")
@Profile("!prod")
public class DemoJourneyController {

    private final DemoJourneyService service;

    DemoJourneyController(DemoJourneyService service) {
        this.service = service;
    }

    /** Start one compressed virtual-day run for a city-pair. Body: {originCity, destCity, count, speed}. */
    @PostMapping("/run-day")
    public DemoJourneyService.RunStatus runDay(@RequestBody RunDayRequest req) {
        return service.start(req.originCity(), req.destCity(),
                req.count() == null ? 5 : req.count(), req.speed() == null ? 60 : req.speed());
    }

    @PostMapping("/stop")
    public DemoJourneyService.RunStatus stop() {
        service.stop();
        return service.status();
    }

    /** Run-level rollup for the status panel. */
    @GetMapping("/run-status")
    public DemoJourneyService.RunStatus status() {
        return service.status();
    }

    /** Per-parcel journey tokens for the pipeline strip. */
    @GetMapping("/journeys")
    public List<DemoJourneyService.JourneyRecord> journeys() {
        return service.journeys();
    }

    /** Raw event feed, long-polled with {@code ?after=<seq>}. */
    @GetMapping("/run-events")
    public List<DemoLog.Entry> events(@RequestParam(defaultValue = "0") long after) {
        return service.events(after);
    }

    public record RunDayRequest(String originCity, String destCity, Integer count, Integer speed) {}
}
