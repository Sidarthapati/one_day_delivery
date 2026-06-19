package com.oneday.routing.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Body for {@code POST /routing/vans/{vanId}/load-scan} (§15 step 2) and, structurally identical,
 * {@code .../return-scan} (step 7). The driver scans each parcel onto (VAN_LOAD) or off (VAN_UNLOAD)
 * the van; the controller records one custody scan per id. driverId rides every scan (Q14).
 */
public record LoadScanRequest(
        Integer loopIndex,
        LocalDate date,
        List<UUID> parcelIds,
        UUID driverId) {

    public LocalDate dateOrToday(LocalDate today) {
        return date != null ? date : today;
    }

    public List<UUID> parcelIdsOrEmpty() {
        return parcelIds != null ? parcelIds : List.of();
    }
}
