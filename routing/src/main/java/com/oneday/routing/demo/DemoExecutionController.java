package com.oneday.routing.demo;

import org.springframework.context.annotation.Profile;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Demo-only "run the day" surface for the M6 execution view (see {@link DemoExecutionService}). Sits
 * alongside grid's {@code DemoController} under {@code /api/demo} (distinct paths). {@code @Profile("!prod")}.
 */
@RestController
@RequestMapping("/api/demo")
@Profile("!prod")
public class DemoExecutionController {

    private final DemoExecutionService service;

    DemoExecutionController(DemoExecutionService service) {
        this.service = service;
    }

    /** Start a run against the city's APPROVED plan for today: feed parcels over RabbitMQ, then drive the vans. */
    @PostMapping("/run-day")
    public DemoExecutionService.RunStatus runDay(
            @RequestParam UUID cityId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(defaultValue = "40") int deliveries,
            @RequestParam(defaultValue = "20") int collects,
            @RequestParam(defaultValue = "60") int speed) {
        try {
            return service.start(cityId, date, deliveries, collects, speed);
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    @PostMapping("/run-stop")
    public ResponseEntity<Void> stop() {
        service.stop();
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/run-status")
    public DemoExecutionService.RunStatus status() {
        return service.status();
    }

    /** Long-pollable feed: entries with seq > {@code after} (0 = everything buffered). */
    @GetMapping("/run-events")
    public List<DemoLog.Entry> events(@RequestParam(defaultValue = "0") long after) {
        return service.events(after);
    }
}
