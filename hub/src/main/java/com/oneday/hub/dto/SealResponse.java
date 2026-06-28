package com.oneday.hub.dto;

import com.oneday.hub.service.BagService;

/** Result of sealing a bag: the now-SEALED bag + its generated manifest. */
public record SealResponse(BagResponse bag, ManifestResponse manifest) {

    public static SealResponse from(BagService.SealResult r) {
        return new SealResponse(BagResponse.from(r.bag()), ManifestResponse.from(r.manifest()));
    }
}
