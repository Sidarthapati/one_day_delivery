package com.oneday.orders.events;

import com.oneday.common.domain.enums.ReturnReason;
import com.oneday.common.domain.enums.ShipmentState;
import com.oneday.orders.domain.Shipment;
import com.oneday.orders.repository.ShipmentRepository;
import com.oneday.orders.service.ReturnService;
import com.oneday.orders.service.ReturnService.ReturnLane;
import com.oneday.orders.service.TransitionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;


/**
 * Resolves a deferred mid-transit RTO intent (feature iii) when the parcel reaches a hub. A return
 * requested while the parcel was still in flight (or in hand pre-hub) records an intent on the
 * shipment and keeps flowing forward; this listener fires the actual return the moment the parcel
 * lands at a hub:
 * <ul>
 *   <li>into {@code AT_ORIGIN_HUB} → {@link ReturnLane#SAME_CITY_FROM_ORIGIN} (never flew; deliver back
 *       within the origin city, no flight);</li>
 *   <li>into {@code AT_DEST_HUB} → {@link ReturnLane#REVERSE_FROM_DEST} (it flew; fly the return back).</li>
 * </ul>
 *
 * <p>Listens to the in-process {@link ShipmentTransitioned} AFTER_COMMIT so the hub-arrival transition
 * is durable first, in a fresh transaction (the return's pessimistic-lock read needs one) — mirrors
 * {@link ReturnCompletionListener}.</p>
 */
@Component
public class RtoIntentResolver {

    private static final Logger log = LoggerFactory.getLogger(RtoIntentResolver.class);
    private static final String SOURCE = "rto-intent-resolver";

    private final ShipmentRepository shipmentRepository;
    private final ReturnService returnService;

    RtoIntentResolver(ShipmentRepository shipmentRepository, ReturnService returnService) {
        this.shipmentRepository = shipmentRepository;
        this.returnService = returnService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onShipmentTransitioned(ShipmentTransitioned e) {
        // The lane this hub arrival can resolve: origin hub → same-city, dest hub → reverse.
        ReturnLane hubLane = switch (e.toState()) {
            case AT_ORIGIN_HUB -> ReturnLane.SAME_CITY_FROM_ORIGIN;
            case AT_DEST_HUB   -> ReturnLane.REVERSE_FROM_DEST;
            default -> null;
        };
        if (hubLane == null) {
            return; // not a hub arrival — nothing to resolve here
        }

        Shipment s = shipmentRepository.findById(e.shipmentId()).orElse(null);
        if (s == null
                || s.getReturnOfShipmentId() != null          // a return child never RTOs itself
                || s.getRtoRequestedAt() == null              // no pending intent
                || s.getRtoResolvedAt() != null) {            // already resolved
            return;
        }

        // Resolve on the intent's own lane. Only fire when this hub IS that lane's resolution hub — a
        // REVERSE intent must not resolve at the origin hub (it still has to fly). The stored lane is the
        // source of truth (set at cancel time); fall back to the hub-derived lane for any intent with none.
        ReturnLane lane = laneOf(s.getRtoLane(), hubLane);
        if (lane != hubLane) {
            return;
        }

        // initiateReturn locks the original, transitions it to RTO_INITIATED and stamps rto_resolved_at
        // on that locked instance (POST_CUSTODY_CANCEL). We must NOT stamp/save `s` here: open-in-view is
        // off so `s` is a different persistence-context copy, and re-saving it would clobber the
        // RTO_INITIATED transition back to the hub state. `s` is left untouched (read-only guard check).
        ReturnService.ReturnResult result = returnService.initiateReturn(
                e.shipmentId(), ReturnReason.POST_CUSTODY_CANCEL, lane,
                TransitionContext.fromSystem(SOURCE)
                        .withNotes("Mid-transit RTO resolved at " + e.toState()));

        log.info("Mid-transit RTO intent on {} resolved at {} ({}) → return child {}",
                s.getShipmentRef(), e.toState(), lane, result.childShipmentRef());
    }

    /** The stored lane if present and parseable, else the hub-derived fallback (legacy/unset intents). */
    static ReturnLane laneOf(String stored, ReturnLane fallback) {
        if (stored == null) {
            return fallback;
        }
        try {
            return ReturnLane.valueOf(stored);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }
}
