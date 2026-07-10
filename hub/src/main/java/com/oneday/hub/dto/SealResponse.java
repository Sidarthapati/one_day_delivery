package com.oneday.hub.dto;

import com.oneday.hub.service.FlightBagService;

/** Result of sealing a bag: the now-SEALED bag + its generated manifest. */
public record SealResponse(BagResponse bag, ManifestResponse manifest) {

    public static SealResponse from(FlightBagService.SealResult r) {
        return new SealResponse(BagResponse.from(r.bag()), ManifestResponse.from(r.manifest()));
    }
}
