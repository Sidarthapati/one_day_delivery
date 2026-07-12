package com.oneday.routing.dto;

import com.oneday.common.domain.MeetingMode;

/**
 * Partial fleet update ({@code PUT /routing/fleet/{cityId}}). Every field is nullable so the ops
 * console can change just the van count (the demo's primary control) — or flip {@code meetingMode}
 * — without resending the rest. Null fields keep their stored value.
 */
public record FleetConfigUpdateRequest(
        Integer vansAvailable,
        Integer capacityPackets,
        Integer cycleTimeMinMinutes,
        Integer cycleTimeMaxMinutes,
        Integer shuttleCadenceMinutes,
        Integer maxDaToVertexMinutes,
        Integer dwellMinutes,
        MeetingMode meetingMode,
        Integer hubReturnIntervalMinutes) {}
