package com.oneday.hub.dto;

import com.oneday.hub.service.DeliveryBagService;

/** Result of sealing a delivery bag: the now-SEALED bag + its generated load-list manifest. */
public record DeliveryBagSealResponse(DeliveryBagResponse bag, ManifestResponse manifest) {

    public static DeliveryBagSealResponse from(DeliveryBagService.SealResult r, String standNo) {
        return new DeliveryBagSealResponse(DeliveryBagResponse.from(r.bag(), standNo), ManifestResponse.from(r.manifest()));
    }
}
