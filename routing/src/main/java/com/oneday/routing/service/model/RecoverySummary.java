package com.oneday.routing.service.model;

import java.util.UUID;

// Outcome of dispatching a recovery van for a broken one (§13.5): how many in-flight manifest items
// were moved to the recovery van's custody, and how many carried deliveries were re-bound.
public record RecoverySummary(UUID brokenVanId, UUID recoveryVanId, int itemsReassigned, int deliveriesRebound) {
}
