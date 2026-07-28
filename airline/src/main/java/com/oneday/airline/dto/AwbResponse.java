package com.oneday.airline.dto;

import com.oneday.airline.domain.Awb;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record AwbResponse(
        UUID id,
        String awbNo,
        String flightNo,
        LocalDate flightDate,
        String originHub,
        String destHub,
        UUID bagId,
        int totalWeightGrams,
        int parcelCount,
        long costPaise,
        String providerRef,
        String status,
        Instant handedOverAt,
        Instant loadedAt,
        /** Null only if the backing flight_instance was somehow never created — shouldn't happen for a
         *  row that reached BOOKED, since booking always finds-or-creates the instance first. */
        Instant cutoff,
        /** Set once a reassignment (§7) supersedes this row — the replacement AWB's id. */
        UUID supersededBy) {

    public static AwbResponse from(Awb a, Instant cutoff) {
        return new AwbResponse(a.getId(), a.getAwbNo(), a.getFlightNo(), a.getFlightDate(), a.getOriginHub(),
                a.getDestHub(), a.getBagId(), a.getTotalWeightGrams(), a.getParcelCount(), a.getCostPaise(),
                a.getProviderRef(), a.getStatus().name(), a.getHandedOverAt(), a.getLoadedAt(),
                cutoff, a.getSupersededBy());
    }
}
