package com.oneday.hub.api;

import com.oneday.hub.domain.DeliveryBagItemStatus;
import com.oneday.hub.dto.BatchReceiveRequest;
import com.oneday.hub.dto.DeliveryBagResponse;
import com.oneday.hub.dto.DeliveryBagSealResponse;
import com.oneday.hub.dto.ReceiveResponse;
import com.oneday.hub.dto.StagingResponse;
import com.oneday.hub.repository.StandRepository;
import com.oneday.hub.service.DeliveryBagService;
import com.oneday.hub.service.HubReceivingService;
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
    private final StandRepository standRepository;

    HubInboundController(HubReceivingService receivingService, DeliveryBagService deliveryBagService,
                          StandRepository standRepository) {
        this.receivingService = receivingService;
        this.deliveryBagService = deliveryBagService;
        this.standRepository = standRepository;
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
        Map<UUID, String> standNos = standNoMap(hubId);
        return deliveryBagService.deliveryBags(hubId, day).stream()
                .map(b -> DeliveryBagResponse.from(b, b.getCurrentStandId() != null ? standNos.get(b.getCurrentStandId()) : null))
                .toList();
    }

    /**
     * Seal an open delivery bag → append-only load-list manifest (mirrors flight bags' seal). Nothing
     * currently calls {@link DeliveryBagService#seal}, so — unlike flight bags — this is the only
     * trigger for it: without an ops action here, a delivery bag can never leave OPEN.
     */
    @PostMapping("/delivery-bags/{bagId}/seal")
    public DeliveryBagSealResponse sealDeliveryBag(@PathVariable UUID hubId, @PathVariable UUID bagId) {
        var result = deliveryBagService.seal(bagId);
        UUID standId = result.bag().getCurrentStandId();
        String standNo = standId != null ? standNoMap(hubId).get(standId) : null;
        return DeliveryBagSealResponse.from(result, standNo);
    }

    /** Per-parcel staging view (default STAGED) for a city/hub. */
    @GetMapping("/staging")
    public List<StagingResponse> staging(@PathVariable UUID hubId,
                                         @RequestParam(required = false) DeliveryBagItemStatus status) {
        DeliveryBagItemStatus s = status != null ? status : DeliveryBagItemStatus.STAGED;
        Map<UUID, String> standNos = standNoMap(hubId);
        return deliveryBagService.staging(hubId, s).stream()
                .map(i -> StagingResponse.from(i, i.getStandId() != null ? standNos.get(i.getStandId()) : null))
                .toList();
    }

    /** One query per request, not per row — every list/lookup here is scoped to a single hub. */
    private Map<UUID, String> standNoMap(UUID hubId) {
        return standRepository.findByHubIdOrderByZoneAscStandNoAsc(hubId).stream()
                .collect(Collectors.toMap(s -> s.getId(), s -> s.getStandNo()));
    }
}
