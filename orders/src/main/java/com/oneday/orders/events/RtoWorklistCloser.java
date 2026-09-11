package com.oneday.orders.events;

import com.oneday.common.domain.enums.ShipmentState;
import com.oneday.orders.service.RtoWorklistService;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Auto-closes a hub RTO worklist item once its return child has been sorted — i.e. the child leaves
 * its birth hub ({@code AT_ORIGIN_HUB → …}). Runs synchronously in the transition's transaction, so
 * the close commits with the sort. Only return children have a worklist item keyed by their ref
 * ({@code <ref>_R}); an ordinary shipment leaving {@code AT_ORIGIN_HUB} matches nothing (no-op).
 */
@Component
public class RtoWorklistCloser {

    private final RtoWorklistService worklist;

    RtoWorklistCloser(RtoWorklistService worklist) {
        this.worklist = worklist;
    }

    @EventListener
    public void onShipmentTransitioned(ShipmentTransitioned e) {
        if (e.fromState() == ShipmentState.AT_ORIGIN_HUB) {
            worklist.closeForChild(e.shipmentRef(), "sorted");
        }
    }
}
