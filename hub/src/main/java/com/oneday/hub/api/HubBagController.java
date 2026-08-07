package com.oneday.hub.api;

import com.oneday.hub.domain.FlightBag;
import com.oneday.hub.dto.*;
import com.oneday.hub.repository.StandRepository;
import com.oneday.hub.service.BagReassignmentService;
import com.oneday.hub.service.FlightBagService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/** Operator console: flight bags — open/add/reassign-stand/seal/reassign-flight/manifest (§14.2). */
@RestController
@RequestMapping("/hub/{hubId}/bags")
public class HubBagController {

    private final FlightBagService flightBagService;
    private final BagReassignmentService bagReassignmentService;
    private final StandRepository standRepository;

    HubBagController(FlightBagService flightBagService, BagReassignmentService bagReassignmentService,
                      StandRepository standRepository) {
        this.flightBagService = flightBagService;
        this.bagReassignmentService = bagReassignmentService;
        this.standRepository = standRepository;
    }

    /** The day's flight bags at this hub — the live origin directory (which stand holds which flight). */
    @GetMapping
    public List<BagResponse> bags(@PathVariable UUID hubId,
                                  @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        LocalDate day = date != null ? date : LocalDate.now();
        Map<UUID, String> standNos = standNoMap(hubId);
        return flightBagService.bagsForDate(hubId, day).stream().map(b -> toResponse(b, standNos)).toList();
    }

    @GetMapping("/{bagId}")
    public BagResponse bag(@PathVariable UUID hubId, @PathVariable UUID bagId) {
        return toResponse(flightBagService.bag(bagId), standNoMap(hubId));
    }

    @PostMapping
    public ResponseEntity<BagResponse> openBag(@PathVariable UUID hubId,
                                               @RequestBody @Valid OpenBagRequest request) {
        var bag = flightBagService.openBag(new FlightBagService.OpenBagCommand(
                hubId, hubId, request.flightNo(), request.flightDate(), request.originHub(),
                request.destHub(), request.bagCutoff()));
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(bag, standNoMap(hubId)));
    }

    @PostMapping("/{bagId}/add")
    public BagResponse addParcel(@PathVariable UUID hubId, @PathVariable UUID bagId,
                                 @RequestBody @Valid AddParcelRequest request) {
        flightBagService.addParcel(bagId, request.shipmentRef());
        return toResponse(flightBagService.bag(bagId), standNoMap(hubId));
    }

    @PostMapping("/{bagId}/reassign-stand")
    public BagResponse reassignStand(@PathVariable UUID hubId, @PathVariable UUID bagId,
                                     @RequestBody @Valid ReassignStandRequest request) {
        FlightBag bag = flightBagService.reassignStand(
                bagId, request.newStandId(), request.actorId(), request.reason());
        return toResponse(bag, standNoMap(hubId));
    }

    @PostMapping("/{bagId}/seal")
    public SealResponse seal(@PathVariable UUID hubId, @PathVariable UUID bagId) {
        var result = flightBagService.seal(bagId);
        UUID standId = result.bag().getCurrentStandId();
        String standNo = standId != null ? standNoMap(hubId).get(standId) : null;
        return SealResponse.from(result, standNo);
    }

    /** Execute an M9 flight reassignment (§9, M7-D-006) — the imperative form of FLIGHT_REASSIGNED. */
    @PostMapping("/reassign-flight")
    public ReassignResponse reassignFlight(@PathVariable UUID hubId,
                                           @RequestBody @Valid ReassignFlightRequest request) {
        return ReassignResponse.from(bagReassignmentService.reassign(
                new BagReassignmentService.FlightReassignmentCommand(
                        request.toFlightNo(), request.toFlightDate(), request.destHub(),
                        request.newCutoff(), request.fromFlightNo(), request.parcelIds(), request.reason())));
    }

    @PostMapping("/{bagId}/dispatch")
    public BagResponse dispatch(@PathVariable UUID hubId, @PathVariable UUID bagId) {
        return toResponse(flightBagService.dispatch(bagId), standNoMap(hubId));
    }

    @GetMapping("/{bagId}/manifest")
    public ManifestResponse manifest(@PathVariable UUID hubId, @PathVariable UUID bagId) {
        return ManifestResponse.from(flightBagService.currentManifest(bagId));
    }

    /** One query per request, not per bag — every bag list/lookup here is scoped to a single hub. */
    private Map<UUID, String> standNoMap(UUID hubId) {
        return standRepository.findByHubIdOrderByZoneAscStandNoAsc(hubId).stream()
                .collect(Collectors.toMap(s -> s.getId(), s -> s.getStandNo()));
    }

    private BagResponse toResponse(FlightBag b, Map<UUID, String> standNos) {
        String standNo = b.getCurrentStandId() != null ? standNos.get(b.getCurrentStandId()) : null;
        return BagResponse.from(b, standNo);
    }
}
