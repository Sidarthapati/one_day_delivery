package com.oneday.hub.dto;

import com.oneday.hub.domain.ArrivalMode;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/** Bulk dock scan from a scan gun (§14.2 POST /hub/{hubId}/receive/batch, ≤100). */
public record BatchReceiveRequest(
        @NotEmpty @Size(max = 100) List<String> shipmentRefs,
        @NotNull ArrivalMode mode) {
}
