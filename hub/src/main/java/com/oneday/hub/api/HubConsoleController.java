package com.oneday.hub.api;

import com.oneday.hub.dto.LoadResponse;
import com.oneday.hub.dto.ParcelLocationResponse;
import com.oneday.hub.service.HubLoadService;
import com.oneday.hub.service.ParcelLocatorService;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/** Operator console: live overload snapshot + parcel-locator (§14.2, §11). */
@RestController
@RequestMapping("/hub/{hubId}")
public class HubConsoleController {

    private final HubLoadService loadService;
    private final ParcelLocatorService parcelLocatorService;

    HubConsoleController(HubLoadService loadService, ParcelLocatorService parcelLocatorService) {
        this.loadService = loadService;
        this.parcelLocatorService = parcelLocatorService;
    }

    /** Live load snapshot for the current wave (computes, persists, alerts if overloaded). */
    @GetMapping("/load")
    public LoadResponse load(@PathVariable UUID hubId) {
        return LoadResponse.from(loadService.snapshot(hubId));
    }

    /** Resolve a scanned parcel to its current stand (which shelf holds this box right now). */
    @PostMapping("/parcels/{parcelId}/resolve")
    public ParcelLocationResponse resolve(@PathVariable UUID hubId, @PathVariable UUID parcelId) {
        return ParcelLocationResponse.from(parcelLocatorService.locate(parcelId));
    }
}
