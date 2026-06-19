package com.oneday.routing.dto;

import com.oneday.routing.domain.VanManifest;
import com.oneday.routing.domain.VanManifestItem;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The driver app's view of one loop ({@code GET /routing/vans/{vanId}/manifest?loop=}): the manifest
 * header plus the parcels to deliver and collect, so the app can drive the load → navigate → scan →
 * confirm flow (§15).
 */
public record VanManifestResponse(
        UUID manifestId,
        UUID vanId,
        int loopIndex,
        LocalDate validDate,
        String status,
        List<VanManifestItemResponse> deliver,
        List<VanManifestItemResponse> collect) {

    public static VanManifestResponse from(VanManifest m, List<VanManifestItem> items) {
        List<VanManifestItemResponse> deliver = items.stream()
                .filter(i -> i.getDirection() != null && i.getDirection().name().equals("DELIVER"))
                .map(VanManifestItemResponse::from).toList();
        List<VanManifestItemResponse> collect = items.stream()
                .filter(i -> i.getDirection() != null && i.getDirection().name().equals("COLLECT"))
                .map(VanManifestItemResponse::from).toList();
        return new VanManifestResponse(
                m.getId(), m.getVanId(), m.getLoopIndex(), m.getValidDate(),
                m.getStatus() != null ? m.getStatus().name() : null, deliver, collect);
    }
}
