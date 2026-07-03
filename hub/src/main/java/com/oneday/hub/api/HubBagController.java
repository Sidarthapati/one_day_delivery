package com.oneday.hub.api;

import com.oneday.hub.dto.*;
import com.oneday.hub.service.BagReassignmentService;
import com.oneday.hub.service.FlightBagService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Operator console: flight bags — open/add/reassign-stand/seal/reassign-flight/manifest (§14.2). */
@RestController
@RequestMapping("/hub/{hubId}/bags")
public class HubBagController {

    private final FlightBagService flightBagService;
    private final BagReassignmentService bagReassignmentService;

    HubBagController(FlightBagService flightBagService, BagReassignmentService bagReassignmentService) {
        this.flightBagService = flightBagService;
        this.bagReassignmentService = bagReassignmentService;
    }

    /** The day's flight bags at this hub — the live origin directory (which stand holds which flight). */
    @GetMapping
    public List<BagResponse> bags(@PathVariable UUID hubId,
                                  @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        LocalDate day = date != null ? date : LocalDate.now();
        return flightBagService.bagsForDate(hubId, day).stream().map(BagResponse::from).toList();
    }

    @PostMapping
    public ResponseEntity<BagResponse> openBag(@PathVariable UUID hubId,
                                               @RequestBody @Valid OpenBagRequest request) {
        var bag = flightBagService.openBag(new FlightBagService.OpenBagCommand(
                hubId, hubId, request.flightNo(), request.flightDate(), request.originHub(),
                request.destHub(), request.bagCutoff()));
        return ResponseEntity.status(HttpStatus.CREATED).body(BagResponse.from(bag));
    }

    @PostMapping("/{bagId}/add")
    public BagResponse addParcel(@PathVariable UUID hubId, @PathVariable UUID bagId,
                                 @RequestBody @Valid AddParcelRequest request) {
        flightBagService.addParcel(bagId, request.shipmentRef());
        return BagResponse.from(flightBagService.bag(bagId));
    }

    @PostMapping("/{bagId}/reassign-stand")
    public BagResponse reassignStand(@PathVariable UUID hubId, @PathVariable UUID bagId,
                                     @RequestBody @Valid ReassignStandRequest request) {
        return BagResponse.from(flightBagService.reassignStand(
                bagId, request.newStandId(), request.actorId(), request.reason()));
    }

    @PostMapping("/{bagId}/seal")
    public SealResponse seal(@PathVariable UUID hubId, @PathVariable UUID bagId) {
        return SealResponse.from(flightBagService.seal(bagId));
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
        return BagResponse.from(flightBagService.dispatch(bagId));
    }

    @GetMapping("/{bagId}/manifest")
    public ManifestResponse manifest(@PathVariable UUID hubId, @PathVariable UUID bagId) {
        return ManifestResponse.from(flightBagService.currentManifest(bagId));
    }
}
