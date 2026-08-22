package com.oneday.exceptions.service;

import com.oneday.common.domain.enums.ShipmentState;
import com.oneday.exceptions.domain.ExceptionReason;
import com.oneday.exceptions.domain.ExceptionType;
import com.oneday.exceptions.domain.ResolveAction;
import com.oneday.exceptions.dto.ExceptionCaseDetail;
import com.oneday.exceptions.dto.ExceptionQueueResponse;
import com.oneday.exceptions.dto.ExceptionSummaryResponse;

import java.util.UUID;

/**
 * M11's read + problem-solve API. {@code cityScope == null} means admin (all cities); a non-null value
 * restricts to a city (matched on origin or destination).
 */
public interface ExceptionCaseService {

    /**
     * Open (or bump the attempt on) a case from a DA-reported failure — the authoritative failure signal,
     * which carries the reason M4 drops. Idempotent per live case: a repeat failure increments the attempt.
     */
    void captureDaFailure(UUID shipmentId, String shipmentRef, ExceptionType type,
                          ExceptionReason reason, boolean daAttributable);

    /** React to a shipment state change: RTO progression closes/advances the case; a successful terminal
     *  delivery auto-resolves any live case. (Pickup/delivery failures are owned by {@link #captureDaFailure}.) */
    void onShipmentStateChanged(UUID shipmentId, ShipmentState toState);

    ExceptionQueueResponse queue(String cityScope, ExceptionType type, int page, int size);

    ExceptionSummaryResponse summary(String cityScope);

    ExceptionCaseDetail detail(UUID caseId, String cityScope);

    /** Take a problem-solve action — publishes the M4-driving event and records the action. */
    void resolve(UUID caseId, ResolveAction action, String cityScope, String userId, String role, String notes);
}
