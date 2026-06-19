package com.oneday.routing.service;

import com.oneday.routing.service.model.StopReconciliation;

import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

// Per-stop, per-DA reconciliation (§13.1/§13.3, M6-D-018). After the dwell window closes the driver
// app reports what was actually scanned each way; this compares it to the manifest, writes an
// append-only handoff_reconciliation row, marks any missing/rejected items EXCEPTION, and emits
// HANDOFF_COMPLETED (clean) or HANDOFF_DISCREPANCY (missing/extra/rejected). Partial handoff is legal.
public interface HandoffService {

    StopReconciliation reconcileStop(UUID vanId, int loopIndex, LocalDate date, int stopSeq, UUID daId,
                                     Set<UUID> deliverScanned, Set<UUID> collectScanned, Set<UUID> rejected);
}
