package com.oneday.airline.api;

import com.oneday.airline.consolidator.ConsolidatorFlightRepository;
import com.oneday.airline.domain.Awb;
import com.oneday.airline.domain.AwbStatus;
import com.oneday.airline.domain.FlightInstance;
import com.oneday.airline.dto.AwbIntakeRequest;
import com.oneday.airline.dto.AwbParcelResponse;
import com.oneday.airline.dto.AwbResponse;
import com.oneday.airline.dto.FlightScheduleResponse;
import com.oneday.airline.dto.FlightStatusResponse;
import com.oneday.airline.repository.AwbParcelRepository;
import com.oneday.airline.repository.AwbRepository;
import com.oneday.airline.repository.FlightInstanceRepository;
import com.oneday.airline.service.AirlineCustodyService;
import com.oneday.airline.service.AwbGroundService;
import com.oneday.airline.service.AwbIntakeService;
import com.oneday.airline.service.provider.FlightProviderPort;
import com.oneday.common.kafka.enums.ScanEventType;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Minimal read views (the lane timetable, a booking by the bag/flight it was booked from, a hub's
 * day of bookings) plus the two ground-crew confirmations (§9).
 */
@RestController
@RequestMapping("/airline")
public class AirlineController {

    private final ConsolidatorFlightRepository consolidatorFlightRepository;
    private final FlightInstanceRepository flightInstanceRepository;
    private final AwbRepository awbRepository;
    private final AwbParcelRepository awbParcelRepository;
    private final AwbGroundService awbGroundService;
    private final AwbIntakeService awbIntakeService;
    private final AirlineCustodyService airlineCustodyService;
    private final FlightProviderPort flightProviderPort;

    AirlineController(ConsolidatorFlightRepository consolidatorFlightRepository,
                       FlightInstanceRepository flightInstanceRepository,
                       AwbRepository awbRepository, AwbParcelRepository awbParcelRepository,
                       AwbGroundService awbGroundService, AwbIntakeService awbIntakeService,
                       AirlineCustodyService airlineCustodyService, FlightProviderPort flightProviderPort) {
        this.consolidatorFlightRepository = consolidatorFlightRepository;
        this.flightInstanceRepository = flightInstanceRepository;
        this.awbRepository = awbRepository;
        this.awbParcelRepository = awbParcelRepository;
        this.awbGroundService = awbGroundService;
        this.awbIntakeService = awbIntakeService;
        this.airlineCustodyService = airlineCustodyService;
        this.flightProviderPort = flightProviderPort;
    }

    /** The consolidator's schedule for a lane on a given date — it's a dated calendar now, not a
     *  recurring pattern, so the date is required. */
    @GetMapping("/lanes/{originHub}/{destHub}/schedule")
    public List<FlightScheduleResponse> schedule(@PathVariable String originHub, @PathVariable String destHub,
                                                 @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return consolidatorFlightRepository.findLegs(originHub, destHub, date).stream()
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

    /**
     * The flight's current status for the GHA console. Once our own {@code flight_instance} has actually
     * moved (DEPARTED/LANDED/CANCELLED) we report that — the same progress the customer's tracking shows —
     * since the consolidator's word only ever covers ON_TIME/DELAYED. While still SCHEDULED (or if no
     * instance exists yet) we fall back to the consolidator, which carries the pre-departure disruption info.
     */
    @GetMapping("/flights/{flightNo}/{flightDate}/status")
    public FlightStatusResponse flightStatus(@PathVariable String flightNo,
                                             @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate flightDate) {
        return flightInstanceRepository.findByFlightNoAndFlightDate(flightNo, flightDate)
                .filter(fi -> fi.getStatus() != com.oneday.airline.domain.FlightInstanceStatus.SCHEDULED)
                .map(FlightStatusResponse::from)
                .orElseGet(() -> FlightStatusResponse.from(flightNo, flightDate,
                        flightProviderPort.status(flightNo, flightDate)));
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

    /**
     * The real AWB Bhagwati hands us for a flight (admin entry now; a WhatsApp form later — see
     * {@code WhatsAppAwbWebhookController}). Stamps it on every booked bag on that plane. 404 if the
     * flight has no booked AWB yet.
     */
    @PostMapping("/flights/{flightNo}/{flightDate}/awb")
    public Map<String, Object> assignAwb(@PathVariable String flightNo,
                                         @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate flightDate,
                                         @Valid @RequestBody AwbIntakeRequest request) {
        int updated = awbIntakeService.assignRealAwb(flightNo, flightDate, request.awbNo());
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "No booked AWB for flight " + flightNo + " (" + flightDate + ")");
        }
        return Map.of("status", "ok", "flight_no", flightNo, "flight_date", flightDate.toString(),
                "awb_no", request.awbNo(), "bags_updated", updated);
    }

    // ── Airport custody actions (gap G1): one AWB = one plane, so each fires the scan for every parcel ──
    /** Handed to Bhagwati / dispatched from origin hub → every parcel advances to DISPATCHED_TO_AIRPORT. */
    @PostMapping("/awb/{awbId}/dispatched-to-airport")
    public Map<String, Object> dispatchedToAirport(@PathVariable UUID awbId) {
        return custody(awbId, ScanEventType.HUB_ORIGIN_OUT);
    }

    /** Accepted by the ground handler / into the cargo terminal → AT_AIRPORT. */
    @PostMapping("/awb/{awbId}/gha-accepted")
    public Map<String, Object> ghaAccepted(@PathVariable UUID awbId) {
        return custody(awbId, ScanEventType.GHA_ACCEPTANCE);
    }

    /** Collected at the destination airport / on the shuttle to the dest hub → DISPATCHED_TO_HUB. */
    @PostMapping("/awb/{awbId}/dest-shuttle-in")
    public Map<String, Object> destShuttleIn(@PathVariable UUID awbId) {
        return custody(awbId, ScanEventType.DEST_SHUTTLE_IN);
    }

    /** Received back at the destination hub → AT_DEST_HUB (dest sort + delivery assignment follow). */
    @PostMapping("/awb/{awbId}/dest-received")
    public Map<String, Object> destReceived(@PathVariable UUID awbId) {
        return custody(awbId, ScanEventType.HUB_DEST_IN);
    }

    private Map<String, Object> custody(UUID awbId, ScanEventType type) {
        int parcels = airlineCustodyService.record(awbId, type);
        if (parcels == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No parcels on AWB " + awbId);
        }
        return Map.of("status", "ok", "awb_id", awbId, "scan", type.name(), "parcels", parcels);
    }

    /** Bhagwati warehouse handoff: our goods have been physically handed to the consolidator's dock. */
    @PostMapping("/awb/{awbId}/handed-over")
    public AwbResponse handedOver(@PathVariable UUID awbId) {
        return toResponse(awbGroundService.handOver(awbId));
    }

    /** Loaded onto the aircraft (consolidator confirmation). Both are timestamps for later reporting. */
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
