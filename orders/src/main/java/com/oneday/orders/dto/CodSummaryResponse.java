package com.oneday.orders.dto;

import java.util.UUID;

/**
 * COD position for one vendor account: money in transit (awaiting collection), collected and
 * available to pay out, held in a pending remittance, and already paid out.
 */
public record CodSummaryResponse(
        UUID b2bAccountId,
        long awaitingCollectionPaise,   // booked, not yet delivered
        long availableToRemitPaise,     // collected, not yet batched → payable now
        long inRemittancePaise,         // collected, in a PENDING remittance
        long remittedPaise,             // paid out to date
        int awaitingCollectionCount,
        int availableToRemitCount
) {}
