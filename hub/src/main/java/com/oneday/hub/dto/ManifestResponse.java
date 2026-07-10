package com.oneday.hub.dto;

import com.oneday.hub.domain.BagManifest;

import java.time.Instant;
import java.util.UUID;

/** System-generated manifest view — the M9 handover artefact (§7.3). {@code parcels} is raw JSON. */
public record ManifestResponse(
        UUID manifestId,
        UUID bagId,
        String flightNo,
        int parcelCount,
        int weightGrams,
        String parcels,
        UUID supersedesId,
        Instant generatedAt) {

    public static ManifestResponse from(BagManifest m) {
        return new ManifestResponse(m.getId(), m.getBagId(), m.getFlightNo(), m.getParcelCount(),
                m.getWeightGrams(), m.getParcels(), m.getSupersedesId(), m.getGeneratedAt());
    }
}
