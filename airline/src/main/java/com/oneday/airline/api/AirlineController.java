package com.oneday.airline.api;

import com.oneday.airline.domain.AwbStatus;
import com.oneday.airline.dto.AwbResponse;
import com.oneday.airline.dto.FlightScheduleResponse;
import com.oneday.airline.dto.FlightStatusResponse;
import com.oneday.airline.repository.AwbRepository;
import com.oneday.airline.repository.FlightScheduleRepository;
import com.oneday.airline.service.AwbGroundService;
import com.oneday.airline.service.provider.FlightProviderPort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Minimal read views (the lane timetable, a booking by the bag/flight it was booked from, a hub's
 * day of bookings) plus the two ground-crew confirmations (§9).
 */
@RestController
@RequestMapping("/airline")
public class AirlineController {

    private final FlightScheduleRepository flightScheduleRepository;
    private final AwbRepository awbRepository;
    private final AwbGroundService awbGroundService;
    private final FlightProviderPort flightProviderPort;

    AirlineController(FlightScheduleRepository flightScheduleRepository, AwbRepository awbRepository,
                       AwbGroundService awbGroundService, FlightProviderPort flightProviderPort) {
        this.flightScheduleRepository = flightScheduleRepository;
        this.awbRepository = awbRepository;
        this.awbGroundService = awbGroundService;
        this.flightProviderPort = flightProviderPort;
    }

    @GetMapping("/lanes/{originHub}/{destHub}/schedule")
    public List<FlightScheduleResponse> schedule(@PathVariable String originHub, @PathVariable String destHub) {
        return flightScheduleRepository.findByOriginHubAndDestHubAndActiveTrue(originHub, destHub).stream()
                .map(FlightScheduleResponse::from)
                .toList();
    }

    @GetMapping("/awb/by-bag/{bagId}")
    public AwbResponse awbByBag(@PathVariable UUID bagId) {
        return awbRepository.findByBagIdAndStatus(bagId, AwbStatus.BOOKED)
                .map(AwbResponse::from)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No booking for bag " + bagId));
    }

    /** The (simulated) vendor's current word on a flight — the same call the status poll job makes. */
    @GetMapping("/flights/{flightNo}/{flightDate}/status")
    public FlightStatusResponse flightStatus(@PathVariable String flightNo,
                                             @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate flightDate) {
        return FlightStatusResponse.from(flightNo, flightDate, flightProviderPort.status(flightNo, flightDate));
    }

    @GetMapping("/flights/{flightNo}/{flightDate}/awbs")
    public List<AwbResponse> awbsByFlight(@PathVariable String flightNo,
                                          @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate flightDate) {
        return awbRepository.findByFlightNoAndFlightDate(flightNo, flightDate).stream()
                .map(AwbResponse::from)
                .toList();
    }

    /** Ground-crew console: the day's bookings out of a hub — batch-level facts only, no customer PII. */
    @GetMapping("/hubs/{originHub}/awbs")
    public List<AwbResponse> awbsForHub(@PathVariable String originHub,
                                        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return awbRepository.findByOriginHubAndFlightDate(originHub, date).stream()
                .map(AwbResponse::from)
                .toList();
    }

    @PostMapping("/awb/{awbId}/handed-over")
    public AwbResponse handedOver(@PathVariable UUID awbId) {
        return AwbResponse.from(awbGroundService.handOver(awbId));
    }

    @PostMapping("/awb/{awbId}/loaded")
    public AwbResponse loaded(@PathVariable UUID awbId) {
        return AwbResponse.from(awbGroundService.markLoaded(awbId));
    }
}
