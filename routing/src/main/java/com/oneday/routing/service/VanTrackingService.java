package com.oneday.routing.service;

import com.oneday.routing.dto.TelemetryAck;
import com.oneday.routing.dto.VanTelemetryRequest;

import java.util.UUID;

/**
 * Run-time half of M6 (§14, M6-D-011). Handles one van telemetry event in-process: always overwrites
 * the van's live position; on ARRIVED computes lateness vs the plan and escalates; on DELIVER/COLLECT
 * routes the scan to custody. Raw GPS pings never leave the process (M6-D-012).
 */
public interface VanTrackingService {

    TelemetryAck handle(UUID vanId, VanTelemetryRequest event);
}
