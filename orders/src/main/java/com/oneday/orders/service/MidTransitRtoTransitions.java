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
        // Origin hub — parcel never left the origin city (or was pulled from an OPEN bag): same-city return.
        registry.register(ShipmentState.AT_ORIGIN_HUB,         ShipmentState.RTO_INITIATED);
        registry.register(ShipmentState.ORIGIN_HUB_PROCESSING, ShipmentState.RTO_INITIATED);
        registry.register(ShipmentState.IN_TAKEOFF_BAG,        ShipmentState.RTO_INITIATED);

        // Destination hub — parcel has flown (or was committed to a sealed bag): reverse-lane return.
        registry.register(ShipmentState.AT_DEST_HUB,           ShipmentState.RTO_INITIATED);
        registry.register(ShipmentState.DEST_HUB_PROCESSING,   ShipmentState.RTO_INITIATED);
    }
}
