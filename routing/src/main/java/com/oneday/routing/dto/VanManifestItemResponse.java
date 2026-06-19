package com.oneday.routing.dto;

import com.oneday.routing.domain.VanManifestItem;

import java.util.UUID;

/** One parcel on the driver's loop manifest — what to load, hand to which DA, or collect. */
public record VanManifestItemResponse(
        UUID parcelId,
        String direction,
        Integer stopSeq,
        UUID meetingVertexId,
        UUID counterpartyDaId,
        String status) {

    public static VanManifestItemResponse from(VanManifestItem i) {
        return new VanManifestItemResponse(
                i.getParcelId(),
                i.getDirection() != null ? i.getDirection().name() : null,
                i.getStopSeq(),
                i.getMeetingVertexId(),
                i.getCounterpartyDaId(),
                i.getStatus() != null ? i.getStatus().name() : null);
    }
}
