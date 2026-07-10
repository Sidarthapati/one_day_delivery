package com.oneday.hub.dto;

import jakarta.validation.constraints.NotBlank;

/** Add a parcel to an open bag (§14.2 POST /hub/{hubId}/bags/{bagId}/add). */
public record AddParcelRequest(@NotBlank String shipmentRef) {
}
