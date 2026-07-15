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
        Instant loadedAt) {

    public static AwbResponse from(Awb a) {
        return new AwbResponse(a.getId(), a.getAwbNo(), a.getFlightNo(), a.getFlightDate(), a.getOriginHub(),
                a.getDestHub(), a.getBagId(), a.getTotalWeightGrams(), a.getParcelCount(), a.getCostPaise(),
                a.getProviderRef(), a.getStatus().name(), a.getHandedOverAt(), a.getLoadedAt());
    }
}
