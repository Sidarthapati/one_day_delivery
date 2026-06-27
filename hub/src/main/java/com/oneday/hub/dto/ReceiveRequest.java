package com.oneday.hub.dto;

import com.oneday.hub.domain.ArrivalMode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** Dock scan-in of one parcel (§14.2 POST /hub/{hubId}/receive). */
public record ReceiveRequest(
        @NotBlank String shipmentRef,
        @NotNull ArrivalMode mode) {
}
