package com.oneday.routing.service.model;

import com.oneday.routing.service.port.ScanLedgerPort;

import java.time.Instant;
import java.util.UUID;

// One physical scan at a custody transfer point (§11.1). Both van and driver identity travel on it
// (Q14). counterpartyDaId is the DA at a meeting stop (deliver/collect); null at the hub (load/unload).
public record VanCustodyCommand(
        UUID parcelId,
        ScanLedgerPort.VanScanType type,
        UUID vanId,
        UUID driverId,
        UUID counterpartyDaId,
        Instant scannedAt) {
}
