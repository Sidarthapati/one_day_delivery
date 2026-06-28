package com.oneday.hub.events;

import com.oneday.common.kafka.EventPublisher;
import com.oneday.common.kafka.EventStreams;
import com.oneday.common.kafka.events.hub.*;
import com.oneday.hub.domain.ArrivalMode;
import com.oneday.hub.domain.BagManifest;
import com.oneday.hub.domain.DiscrepancyType;
import com.oneday.hub.domain.FlightBag;
import com.oneday.hub.domain.SortDirection;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Publishes M7's hub events on {@code oneday.hub.events} (§14.1) via the shared {@link EventPublisher}.
 * The typed {@code common.kafka.events.hub.*} payloads are the contract; this is the only place that
 * maps M7 domain rows onto them (mirrors M6's {@code CronEventProducer}).
 */
@Component
public class HubEventProducer {

    private final EventPublisher eventPublisher;

    HubEventProducer(EventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    /** STAND_ASSIGNED → operator console / M8: a scanned parcel resolved to a stand (§7.1). */
    public void emitStandAssigned(UUID shipmentId, UUID cityId, UUID hubId, String standNo,
                                  String sortKey, SortDirection direction) {
        publish(new StandAssignedEvent(shipmentId, cityId, hubId, standNo, sortKey, direction.name()));
    }

    /** BAG_CREATED: a flight bag was opened lazily for a (flight, date, dest_hub) on a stand (§7.2). */
    public void emitBagCreated(FlightBag bag, String standNo) {
        publish(new BagCreatedEvent(bag.getId(), bag.getCityId(), bag.getHubId(),
                bag.getFlightNo(), bag.getFlightDate(), bag.getDestHub(), standNo));
    }

    /** BAG_SEALED → M9, M10: contents frozen, manifest generated (§7.3). */
    public void emitBagSealed(FlightBag bag, String standNo) {
        publish(new BagSealedEvent(bag.getId(), bag.getFlightNo(), bag.getFlightDate(),
                standNo, bag.getParcelCount(), bag.getWeightGrams()));
    }

    /** MANIFEST_GENERATED → M9 handover: the append-only manifest is ready (§7.3). */
    public void emitManifestGenerated(FlightBag bag, BagManifest manifest) {
        publish(new ManifestGeneratedEvent(bag.getId(), manifest.getId(), bag.getFlightNo()));
    }

    /** HUB_DISCREPANCY → M11, M10: dock reconciliation mismatch (§6, C13). */
    public void emitHubDiscrepancy(UUID shipmentId, UUID cityId, UUID hubId,
                                   ArrivalMode arrivalMode, DiscrepancyType discrepancyType) {
        publish(new HubDiscrepancyEvent(shipmentId, cityId, hubId,
                arrivalMode.name(), discrepancyType.name()));
    }

    private void publish(HubEventPayload event) {
        eventPublisher.publish(EventStreams.HUB_EVENTS, event);
    }
}
