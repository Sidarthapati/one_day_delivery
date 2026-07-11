package com.oneday.dispatch.events;

import com.oneday.common.domain.MeetingMode;
import com.oneday.common.kafka.events.hub.HubEventPayload;
import com.oneday.common.kafka.events.hub.ParcelSortedForDeliveryEvent;
import com.oneday.common.port.CityMeetingModePort;
import com.oneday.dispatch.service.AssignmentResult;
import com.oneday.dispatch.service.DispatchService;
import com.oneday.grid.dto.response.DaTerritoryResponse;
import com.oneday.grid.dto.response.GridVertexResponse;
import com.oneday.grid.dto.response.TerritoryHexResponse;
import com.oneday.grid.service.GridService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Delivery-from-hub for HUB_RETURN cities. In a city with no van (the M6 gate off), M7 sorting a
 * destination parcel for last-mile ({@code PARCEL_SORTED_FOR_DELIVERY}) is the trigger to queue the
 * delivery on the territory DA — who collects it on their next hub visit. In VAN_MEETING cities M6's
 * {@code HubFeedConsumer} owns this event (binds it to a van loop), so this consumer no-ops there.
 *
 * <p>Reuses the existing {@link DispatchService#assignDelivery} engine: the destination hex resolves
 * to the territory DA via M3, and the DA's hub-return cron gates the queue exactly like a pickup. The
 * assignment is idempotent (an already-active DELIVERY task is skipped), so it is safe even if M4's
 * own delivery path also fires for the shipment.</p>
 */
@Component
public class HubDeliveryFeedConsumer {

    private static final Logger log = LoggerFactory.getLogger(HubDeliveryFeedConsumer.class);

    private final DispatchService dispatchService;
    private final GridService gridService;
    private final CityMeetingModePort meetingModePort;

    public HubDeliveryFeedConsumer(DispatchService dispatchService,
                                   GridService gridService,
                                   CityMeetingModePort meetingModePort) {
        this.dispatchService = dispatchService;
        this.gridService = gridService;
        this.meetingModePort = meetingModePort;
    }

    @RabbitListener(queues = DispatchMessagingTopology.HUB_QUEUE)
    public void onHubEvent(HubEventPayload event) {
        if (!(event instanceof ParcelSortedForDeliveryEvent e)) {
            return;   // other hub events are not M5's concern
        }
        if (e.cityId() == null || e.destinationHexId() == null || e.parcelId() == null) {
            log.warn("PARCEL_SORTED_FOR_DELIVERY missing parcelId/cityId/hex (parcel={} city={} hex={}) — skipping",
                    e.parcelId(), e.cityId(), e.destinationHexId());
            return;
        }
        if (meetingModePort.modeFor(e.cityId()) != MeetingMode.HUB_RETURN) {
            return;   // VAN_MEETING → M6 binds this parcel to a van; nothing for M5 to do
        }

        double[] centroid = hexCentroid(e.cityId(), e.validDate(), e.destinationHexId());
        if (centroid == null) {
            log.error("HUB_RETURN delivery parcel {} — destination hex {} not in any active territory for {}; cannot assign",
                    e.parcelId(), e.destinationHexId(), e.validDate());
            return;
        }
        AssignmentResult result = dispatchService.assignDelivery(
                e.parcelId(), e.cityId(), centroid[0], centroid[1], e.destinationHexId());
        log.debug("HUB_RETURN delivery assignment for parcel {}: {}", e.parcelId(), result.outcome());
    }

    /** Representative coordinate for a hex: the mean of its H3 corner vertices (null if not found). */
    private double[] hexCentroid(UUID cityId, java.time.LocalDate date, UUID hexId) {
        List<DaTerritoryResponse> territories = gridService.getDaTerritories(cityId, date);
        for (DaTerritoryResponse t : territories) {
            for (TerritoryHexResponse hex : t.hexes()) {
                if (hex.hexId().equals(hexId)) {
                    return centroidOf(hex.vertices());
                }
            }
        }
        return null;
    }

    private double[] centroidOf(List<GridVertexResponse> vertices) {
        if (vertices == null || vertices.isEmpty()) return null;
        double lat = 0, lon = 0;
        for (GridVertexResponse v : vertices) {
            lat += v.lat();
            lon += v.lon();
        }
        return new double[]{lat / vertices.size(), lon / vertices.size()};
    }
}
