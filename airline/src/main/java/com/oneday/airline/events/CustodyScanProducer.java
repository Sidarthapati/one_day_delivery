package com.oneday.airline.events;

import com.oneday.common.kafka.EventPublisher;
import com.oneday.common.kafka.EventStreams;
import com.oneday.common.kafka.enums.ScanEventType;
import com.oneday.common.kafka.events.ScanEvent;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Originates the four intercity <b>airport custody scans</b> on {@code oneday.scan.events} — the same
 * exchange M8 uses — so M4's {@code ScanEventsConsumer} advances a parcel through the air legs.
 *
 * <p>These points (hub→airport dispatch, GHA acceptance, dest shuttle-in, dest hub-in) are physically
 * handled by the non-tech freight consolidator (Bhagwati), so no scan gun fires them; the airline
 * console triggers them per-AWB instead (closing gap G1). Ledger-only recording in M8 is a later nicety;
 * what matters for the flow is the parcel state, which these ScanEvents drive.</p>
 */
@Component
public class CustodyScanProducer {

    private final EventPublisher eventPublisher;

    CustodyScanProducer(EventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    /** Publish one custody scan for a parcel (parcelId == shipmentId in v1). */
    public void publish(UUID shipmentId, ScanEventType type) {
        eventPublisher.publish(EventStreams.SCAN_EVENTS, new ScanEvent(shipmentId, type));
    }
}
