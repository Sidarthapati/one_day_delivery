package com.oneday.hub.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/** Bulk dock scan from a scan gun (§14.2 POST /hub/{hubId}/receive/batch, ≤100). Mode is derived per parcel. */
public record BatchReceiveRequest(
        @NotEmpty @Size(max = 100) List<String> shipmentRefs) {
}
