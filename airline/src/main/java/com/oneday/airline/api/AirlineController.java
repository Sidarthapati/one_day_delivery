package com.oneday.airline.api;

import com.oneday.airline.domain.Awb;
import com.oneday.airline.domain.AwbStatus;
import com.oneday.airline.domain.FlightInstance;
import com.oneday.airline.dto.AwbParcelResponse;
import com.oneday.airline.dto.AwbResponse;
import com.oneday.airline.dto.FlightScheduleResponse;
import com.oneday.airline.dto.FlightStatusResponse;
import com.oneday.airline.repository.AwbParcelRepository;
import com.oneday.airline.repository.AwbRepository;
import com.oneday.airline.repository.FlightInstanceRepository;
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

import java.time.Instant;
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
    private final FlightInstanceRepository flightInstanceRepository;
    private final AwbRepository awbRepository;
    private final AwbParcelRepository awbParcelRepository;
    private final AwbGroundService awbGroundService;
    private final FlightProviderPort flightProviderPort;

    AirlineController(FlightScheduleRepository flightScheduleRepository, FlightInstanceRepository flightInstanceRepository,
                       AwbRepository awbRepository, AwbParcelRepository awbParcelRepository,
                       AwbGroundService awbGroundService, FlightProviderPort flightProviderPort) {
        this.flightScheduleRepository = flightScheduleRepository;
        this.flightInstanceRepository = flightInstanceRepository;
        this.awbRepository = awbRepository;
        this.awbParcelRepository = awbParcelRepository;
        this.awbGroundService = awbGroundService;
        this.flightProviderPort = flightProviderPort;
    }

    @GetMapping("/lanes/{originHub}/{destHub}/schedule")
    public List<FlightScheduleResponse> schedule(@PathVariable String originHub, @PathVariable String destHub) {
        return flightScheduleRepository.findByOriginHubAndDestHubAndActiveTrue(originHub, destHub).stream()
                .map(FlightScheduleResponse::from)
                .toList();
    }

    @GetMapping("/awb/{id}")
    public AwbResponse awbById(@PathVariable UUID id) {
        return awbRepository.findById(id)
                .map(this::toResponse)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such AWB " + id));
    }

    @GetMapping("/awb/{id}/parcels")
    public List<AwbParcelResponse> awbParcels(@PathVariable UUID id) {
        return awbParcelRepository.findByAwbId(id).stream()
                .map(AwbParcelResponse::from)
                .toList();
    }

    @GetMapping("/awb/by-bag/{bagId}")
    public AwbResponse awbByBag(@PathVariable UUID bagId) {
        return awbRepository.findByBagIdAndStatus(bagId, AwbStatus.BOOKED)
                .map(this::toResponse)
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
                .map(this::toResponse)
                .toList();
    }

    /** Ground-crew console: the day's bookings out of a hub — batch-level facts only, no customer PII. */
    @GetMapping("/hubs/{originHub}/awbs")
    public List<AwbResponse> awbsForHub(@PathVariable String originHub,
                                        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return awbRepository.findByOriginHubAndFlightDate(originHub, date).stream()
                .map(this::toResponse)
                .toList();
    }

    @PostMapping("/awb/{awbId}/handed-over")
    public AwbResponse handedOver(@PathVariable UUID awbId) {
        return toResponse(awbGroundService.handOver(awbId));
    }

    @PostMapping("/awb/{awbId}/loaded")
    public AwbResponse loaded(@PathVariable UUID awbId) {
        return toResponse(awbGroundService.markLoaded(awbId));
    }

    /** Every AWB's cutoff lives on its flight_instance, not on the AWB row itself — resolved here so
     *  every response carries it without every caller having to know that. */
    private AwbResponse toResponse(Awb awb) {
        Instant cutoff = flightInstanceRepository.findByFlightNoAndFlightDate(awb.getFlightNo(), awb.getFlightDate())
                .map(FlightInstance::getCutoff)
                .orElse(null);
        return AwbResponse.from(awb, cutoff);
    }
}
