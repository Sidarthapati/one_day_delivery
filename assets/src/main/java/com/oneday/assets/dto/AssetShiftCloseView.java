package com.oneday.assets.dto;

import com.oneday.assets.domain.AssetShiftClose;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Read model of an A1 asset-registry shift close. */
public record AssetShiftCloseView(
        UUID id,
        UUID cityId,
        String shift,
        LocalDate closeDate,
        UUID closedByUserId,
        Instant closedAt,
        int totalAssets,
        int atStationCount,
        int outWithDaCount,
        int inMaintenanceCount,
        int vansTotal,
        int vansOutstanding,
        int discrepancyCount,
        List<AssetShiftClose.OutstandingItem> outstanding) {

    public static AssetShiftCloseView from(AssetShiftClose c) {
        return new AssetShiftCloseView(
                c.getId(), c.getCityId(), c.getShift(), c.getCloseDate(), c.getClosedByUserId(),
                c.getClosedAt(), c.getTotalAssets(), c.getAtStationCount(), c.getOutWithDaCount(),
                c.getInMaintenanceCount(), c.getVansTotal(), c.getVansOutstanding(),
                c.getDiscrepancyCount(), c.getOutstanding());
    }
}
