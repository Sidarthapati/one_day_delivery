package com.oneday.hub.api;

import com.oneday.hub.dto.LoadResponse;
import com.oneday.hub.dto.ParcelLocationResponse;
import com.oneday.hub.dto.StandResponse;
import com.oneday.hub.repository.StandRepository;
import com.oneday.hub.service.HubLoadService;
import com.oneday.hub.service.ParcelLocatorService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/** Operator console: live overload snapshot, stand directory, and parcel-locator (§14.2, §11). */
@RestController
@RequestMapping("/hub/{hubId}")
public class HubConsoleController {

    private final HubLoadService loadService;
    private final ParcelLocatorService parcelLocatorService;
    private final StandRepository standRepository;

    HubConsoleController(HubLoadService loadService, ParcelLocatorService parcelLocatorService,
                          StandRepository standRepository) {
        this.loadService = loadService;
        this.parcelLocatorService = parcelLocatorService;
        this.standRepository = standRepository;
    }

    /** Live load snapshot for the current wave (computes, persists, alerts if overloaded). */
    @GetMapping("/load")
    public LoadResponse load(@PathVariable UUID hubId) {
        return LoadResponse.from(loadService.snapshot(hubId));
    }

    /** The hub's physical stand directory — every stand's status, for the floor view and the
     *  reassign-stand picker (there was previously no way to discover a free stand at all). */
    @GetMapping("/stands")
    public List<StandResponse> stands(@PathVariable UUID hubId) {
        return standRepository.findByHubIdOrderByZoneAscStandNoAsc(hubId).stream()
                .map(StandResponse::from)
                .toList();
    }

    /** Resolve a scanned parcel to its current stand (which shelf holds this box right now). */
    @PostMapping("/parcels/{parcelId}/resolve")
    public ParcelLocationResponse resolve(@PathVariable UUID hubId, @PathVariable UUID parcelId) {
        return ParcelLocationResponse.from(parcelLocatorService.locate(parcelId));
    }
}
