package com.oneday.hub.dto;

import com.oneday.hub.service.ParcelLocatorService;

import java.util.UUID;

/** "Where does this box go?" — a scanned parcel's current bag + stand (§14.2). */
public record ParcelLocationResponse(
        UUID parcelId,
        String direction,
        UUID bagId,
        UUID standId,
        String standNo,
        String flightNo,
        String bagKind,
        String status) {

    public static ParcelLocationResponse from(ParcelLocatorService.ParcelLocation l) {
        return new ParcelLocationResponse(l.parcelId(), l.direction(), l.bagId(), l.standId(),
                l.standNo(), l.flightNo(), l.bagKind(), l.status());
    }
}
