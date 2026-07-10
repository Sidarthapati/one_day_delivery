package com.oneday.app.demo;

import org.springframework.context.annotation.Profile;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Demo-only ({@code @Profile("!prod")}) surface for the one-button intercity flow (see
 * {@link DemoFullDayService}). The live event log + map still come from the shared
 * {@code /api/demo/run-events} / {@code /api/demo/run-status} (routing's DemoExecutionService); this
 * adds order placement, the full-day run trigger, and a coarse macro-phase status for the tracker.
 */
@RestController
@RequestMapping("/api/demo/full-day")
@Profile("!prod")
public class DemoFullDayController {

    private final DemoFullDayService service;

    DemoFullDayController(DemoFullDayService service) {
        this.service = service;
    }

    /** Place N real intercity bookings A→B (→ ShipmentCreatedEvent → M5 assigns each pickup DA). */
    @PostMapping("/order")
    public DemoFullDayService.PlaceResult order(
            @RequestParam UUID originCityId, @RequestParam String originCity,
            @RequestParam(defaultValue = "560001") String originPin,
            @RequestParam UUID destCityId, @RequestParam String destCity,
            @RequestParam(defaultValue = "400001") String destPin,
            @RequestParam(defaultValue = "5") int count,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        try {
            return service.placeOrders(originCityId, originCity, originPin,
                    destCityId, destCity, destPin, count, date);
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    /** Run the whole chain in one go for the parcels booked A→B. */
    @PostMapping("/run")
    public DemoFullDayService.Progress run(
            @RequestParam UUID originCityId, @RequestParam String originCity,
            @RequestParam UUID destCityId, @RequestParam String destCity,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(defaultValue = "60") int speed) {
        try {
            return service.run(originCityId, originCity, destCityId, destCity, date, speed);
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    /** Coarse macro-phase for the phase tracker (First-mile → Origin hub → Flight → Dest hub → Last-mile). */
    @GetMapping("/status")
    public DemoFullDayService.Progress status() {
        return service.status();
    }
}
