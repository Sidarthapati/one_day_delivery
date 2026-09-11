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
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Reconcile backstop for stranded mid-transit RTO intents. The happy path is {@link RtoIntentResolver}
 * firing on the parcel's hub-arrival event; but that listener runs AFTER_COMMIT in its own transaction,
 * so a transient failure there (a pricing/serviceability port hiccup, a DB blip) leaves the intent
 * recorded-but-unresolved with no second trigger — the hub-arrival event won't fire again. This job
 * periodically re-attempts any intent whose parcel is already sitting at a resolvable hub state, so a
 * return is never permanently stuck. Idempotent: {@link ReturnService#initiateReturn} returns the
 * existing child if one was already minted, and stamps {@code rto_resolved_at} on success, so a resolved
 * intent drops out of the next sweep.
 */
@Component
public class RtoIntentReconcileJob {

    private static final Logger log = LoggerFactory.getLogger(RtoIntentReconcileJob.class);
    private static final String SOURCE = "rto-intent-reconcile";
    private static final int BATCH = 100;

    // Dest hub only (R4). A same-city (pre-hub) intent resolves on the AFTER_COMMIT transition INTO
    // AT_ORIGIN_HUB and is guarded from flying by HubReceivingService; sweeping AT_ORIGIN_HUB here would
    // wrongly same-city a *committed* intent that was cancelled while already at the origin hub (which
    // must fly and return reverse-lane). So the backstop only re-resolves reverse-lane intents that have
    // reached the destination hub.
    private static final Set<ShipmentState> HUB_STATES = EnumSet.of(ShipmentState.AT_DEST_HUB);

    private final ShipmentRepository shipmentRepository;
    private final ReturnService returnService;

    RtoIntentReconcileJob(ShipmentRepository shipmentRepository, ReturnService returnService) {
        this.shipmentRepository = shipmentRepository;
        this.returnService = returnService;
    }

    /**
     * Every {@code orders.rto.reconcile-interval-ms} (default 5 min), resolve any stranded intents.
     * Each is handled independently — one failure is logged and doesn't abort the rest, so it will be
     * retried on the next sweep.
     */
    @Scheduled(fixedDelayString = "${orders.rto.reconcile-interval-ms:300000}",
            initialDelayString = "${orders.rto.reconcile-initial-delay-ms:120000}")
    public void reconcile() {
        List<Shipment> stranded =
                shipmentRepository.findStrandedRtoIntents(HUB_STATES, PageRequest.of(0, BATCH));
        if (stranded.isEmpty()) {
            return;
        }
        log.warn("RTO reconcile: {} stranded intent(s) at the dest hub — re-resolving", stranded.size());
        for (Shipment s : stranded) {
            // Only AT_DEST_HUB is swept (see HUB_STATES) → always the reverse lane.
            ReturnLane lane = ReturnLane.REVERSE_FROM_DEST;
            try {
                ReturnService.ReturnResult r = returnService.initiateReturn(
                        s.getId(), ReturnReason.POST_CUSTODY_CANCEL, lane,
                        TransitionContext.fromSystem(SOURCE)
                                .withNotes("Mid-transit RTO reconciled at " + s.getState()));
                log.info("RTO reconcile: {} resolved at {} ({}) → return child {}",
                        s.getShipmentRef(), s.getState(), lane, r.childShipmentRef());
            } catch (RuntimeException e) {
                // Leave the intent pending; the next sweep retries it.
                log.warn("RTO reconcile: {} at {} failed to resolve, will retry next sweep: {}",
                        s.getShipmentRef(), s.getState(), e.toString());
            }
        }
    }
}
