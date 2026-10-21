package com.oneday.orders.service;

import com.oneday.common.domain.enums.ShipmentState;
import org.springframework.stereotype.Component;

/**
 * Mid-transit RTO (feature iii): opens {@code RTO_INITIATED} from the <em>hub</em> states, so a return
 * requested anywhere in transit can fire when the parcel next reaches a hub — the origin hub for a
 * pre-flight recall (same-city return), or the destination hub once the parcel has flown (reverse-lane
 * return). The base registry only allows {@code DELIVERY_FAILED → RTO_INITIATED} (the doorstep case).
 *
 * <p>Intermediate/in-flight states get <b>no</b> new edge: they carry an RTO intent flag and keep
 * flowing forward untouched (the movers key off state) until they hit one of these hub states, where
 * {@code RtoIntentResolver} fires the return. This is the intended
 * {@link TransitionRegistryConfigurer} extension point — no change to {@link TransitionRegistry}.</p>
 */
@Component
public class MidTransitRtoTransitions implements TransitionRegistryConfigurer {

    @Override
    public void configure(TransitionRegistry registry) {
        // Origin hub — a pre-hub (same-city) return resolves the moment the parcel is dock-received into
        // AT_ORIGIN_HUB. Only this origin state gets an edge: a cancel at ORIGIN_HUB_PROCESSING/IN_TAKEOFF_BAG
        // is committed to fly (R4), so it defers and RTOs reverse-lane from the dest hub — nothing ever
        // transitions to RTO_INITIATED from those states.
        registry.register(ShipmentState.AT_ORIGIN_HUB,         ShipmentState.RTO_INITIATED);

        // Destination hub — parcel has flown: reverse-lane return, fired on arrival or at the dock.
        registry.register(ShipmentState.AT_DEST_HUB,           ShipmentState.RTO_INITIATED);
        registry.register(ShipmentState.DEST_HUB_PROCESSING,   ShipmentState.RTO_INITIATED);
    }
}
