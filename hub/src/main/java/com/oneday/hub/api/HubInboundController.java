package com.oneday.hub.api;

import com.oneday.hub.domain.DeliveryBagItemStatus;
import com.oneday.hub.dto.BatchReceiveRequest;
import com.oneday.hub.dto.DeliveryBagResponse;
import com.oneday.hub.dto.ReceiveResponse;
import com.oneday.hub.dto.StagingResponse;
import com.oneday.hub.service.DeliveryBagService;
import com.oneday.hub.service.HubReceivingService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Operator console: the destination hub (§14.2, §8). Break a landed bag into its parcels (each runs
 * the inbound sort ladder), then view the resulting delivery bags (what a van loads) and the
 * per-parcel staging. City-scoped auth (M1) lands with the auth wiring.
 */
@RestController
@RequestMapping("/hub/{hubId}")
public class HubInboundController {

    private final HubReceivingService receivingService;
    private final DeliveryBagService deliveryBagService;

    HubInboundController(HubReceivingService receivingService, DeliveryBagService deliveryBagService) {
        this.receivingService = receivingService;
        this.deliveryBagService = deliveryBagService;
    }

    /** Break a landed bag: receive each parcel (mode AIRPORT derived from M4 state) → inbound sort. */
    @PostMapping("/inbound/break-bag")
    public ResponseEntity<List<ReceiveResponse>> breakBag(@PathVariable UUID hubId,
                                                          @RequestBody @Valid BatchReceiveRequest request) {
        List<ReceiveResponse> out = request.shipmentRefs().stream()
                .map(ref -> ReceiveResponse.from(receivingService.receive(hubId, ref)))
                .toList();
        return ResponseEntity.status(HttpStatus.CREATED).body(out);
    }

    /** The day's delivery bags (route/territory) at this hub — the live dest directory a van loads from. */
    @GetMapping("/delivery-bags")
    public List<DeliveryBagResponse> deliveryBags(
            @PathVariable UUID hubId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        LocalDate day = date != null ? date : LocalDate.now();
        return deliveryBagService.deliveryBags(hubId, day).stream().map(DeliveryBagResponse::from).toList();
    }

    /** Per-parcel staging view (default STAGED) for a city/hub. */
    @GetMapping("/staging")
    public List<StagingResponse> staging(@PathVariable UUID hubId,
                                         @RequestParam(required = false) DeliveryBagItemStatus status) {
        DeliveryBagItemStatus s = status != null ? status : DeliveryBagItemStatus.STAGED;
        return deliveryBagService.staging(hubId, s).stream().map(StagingResponse::from).toList();
    }
}
