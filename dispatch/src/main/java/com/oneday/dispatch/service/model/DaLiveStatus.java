package com.oneday.dispatch.service.model;

import com.oneday.dispatch.domain.DaStatusEnum;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * In-memory live state for one DA — latest GPS, last heartbeat, and current status. Updated on every
 * GPS ping and flushed to {@code da_status} periodically (Phase 2).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class DaLiveStatus {
    private UUID daId;
    private UUID cityId;
    private Double lat;
    private Double lon;
    private Instant lastHeartbeat;
    private DaStatusEnum status;
    // Which shift this DA is on (SHIFT_1/SHIFT_2), so shift-end can tear down only the ending shift.
    private String shiftType;
}
