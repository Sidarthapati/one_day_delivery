package com.oneday.hub.api;

import com.oneday.hub.dto.*;
import com.oneday.hub.service.BagService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/** Operator console: flight bags — open/add/reassign-stand/seal/manifest (§14.2). */
@RestController
@RequestMapping("/hub/{hubId}/bags")
public class HubBagController {

    private final BagService bagService;

    HubBagController(BagService bagService) {
        this.bagService = bagService;
    }

    @PostMapping
    public ResponseEntity<BagResponse> openBag(@PathVariable UUID hubId,
                                               @RequestBody @Valid OpenBagRequest request) {
        var bag = bagService.openBag(new BagService.OpenBagCommand(
                hubId, hubId, request.flightNo(), request.flightDate(), request.originHub(),
                request.destHub(), request.bagCutoff()));
        return ResponseEntity.status(HttpStatus.CREATED).body(BagResponse.from(bag));
    }

    @PostMapping("/{bagId}/add")
    public BagResponse addParcel(@PathVariable UUID hubId, @PathVariable UUID bagId,
                                 @RequestBody @Valid AddParcelRequest request) {
        bagService.addParcel(bagId, request.shipmentRef());
        return BagResponse.from(bagService.bag(bagId));
    }

    @PostMapping("/{bagId}/reassign-stand")
    public BagResponse reassignStand(@PathVariable UUID hubId, @PathVariable UUID bagId,
                                     @RequestBody @Valid ReassignStandRequest request) {
        return BagResponse.from(bagService.reassignStand(
                bagId, request.newStandId(), request.actorId(), request.reason()));
    }

    @PostMapping("/{bagId}/seal")
    public SealResponse seal(@PathVariable UUID hubId, @PathVariable UUID bagId) {
        return SealResponse.from(bagService.seal(bagId));
    }

    @PostMapping("/{bagId}/dispatch")
    public BagResponse dispatch(@PathVariable UUID hubId, @PathVariable UUID bagId) {
        return BagResponse.from(bagService.dispatch(bagId));
    }

    @GetMapping("/{bagId}/manifest")
    public ManifestResponse manifest(@PathVariable UUID hubId, @PathVariable UUID bagId) {
        return ManifestResponse.from(bagService.currentManifest(bagId));
    }
}
