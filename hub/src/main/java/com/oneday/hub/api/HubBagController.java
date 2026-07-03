package com.oneday.hub.api;

import com.oneday.hub.dto.*;
import com.oneday.hub.service.FlightBagService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/** Operator console: flight bags — open/add/reassign-stand/seal/manifest (§14.2). */
@RestController
@RequestMapping("/hub/{hubId}/bags")
public class HubBagController {

    private final FlightBagService flightBagService;

    HubBagController(FlightBagService flightBagService) {
        this.flightBagService = flightBagService;
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

    @PostMapping("/{bagId}/dispatch")
    public BagResponse dispatch(@PathVariable UUID hubId, @PathVariable UUID bagId) {
        return BagResponse.from(flightBagService.dispatch(bagId));
    }

    @GetMapping("/{bagId}/manifest")
    public ManifestResponse manifest(@PathVariable UUID hubId, @PathVariable UUID bagId) {
        return ManifestResponse.from(flightBagService.currentManifest(bagId));
    }
}
