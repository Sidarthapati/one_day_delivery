package com.oneday.hub.dto;

import jakarta.validation.constraints.NotBlank;

/** Dock scan-in of one parcel (§14.2 POST /hub/{hubId}/receive). Arrival mode is derived, not sent. */
public record ReceiveRequest(
        @NotBlank String shipmentRef) {
}
