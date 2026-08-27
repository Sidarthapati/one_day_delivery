package com.oneday.dispatch.events;

import com.oneday.common.kafka.events.ReceiverRejectedEvent;
import com.oneday.dispatch.config.DispatchProperties;
import com.oneday.dispatch.domain.DeferReason;
import com.oneday.dispatch.service.DispatchService;
import com.oneday.grid.dto.response.ServiceableAtResponse;
import com.oneday.grid.service.GridService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

/**
 * Consumes M4's {@code RECEIVER_REJECTED} (a receiver rejected today's delivery and picked a next-day
 * shift) and re-parks the last-mile delivery for that day/shift — a courtesy reschedule, NOT a failed
 * attempt (no {@code DROP_FAILED}, so M11's attempt count is untouched).
 *
 * <p>{@link DispatchService#deferDeliveryForRetry} is idempotent on a live delivery task, so this handles
 * only the common "parcel still inbound / not yet dispatched" case: if the parcel is already out for
 * last-mile the reject-defer no-ops and the DA's own door-failure carry-back path handles it instead.</p>
 */
@Component
public class ReceiverRejectedConsumer {

    private static final Logger log = LoggerFactory.getLogger(ReceiverRejectedConsumer.class);

    private final DispatchService dispatchService;
    private final GridService gridService;
    private final DispatchProperties props;

    public ReceiverRejectedConsumer(DispatchService dispatchService, GridService gridService,
                                    DispatchProperties props) {
        this.dispatchService = dispatchService;
        this.gridService = gridService;
        this.props = props;
    }

    @RabbitListener(queues = DispatchMessagingTopology.DELIVERY_CONFIRMATIONS_QUEUE)
    public void onReceiverRejected(ReceiverRejectedEvent event) {
        UUID shipmentId = event.shipmentId();
        Double lat = event.destLat();
        Double lon = event.destLon();
        if (lat == null || lon == null) {
            log.warn("RECEIVER_REJECTED for shipment {} without dest coordinates — cannot re-park", shipmentId);
            return;
        }
        ServiceableAtResponse loc = gridService.serviceableAt(lat, lon);
        if (loc == null || loc.cityId() == null) {
            log.error("RECEIVER_REJECTED drop point ({},{}) outside every grid — cannot re-park shipment {}",
                    lat, lon, shipmentId);
            return;
        }
        UUID tileId = event.destTileId() != null ? event.destTileId() : loc.hexId();
        LocalDate tomorrow = LocalDate.now(ZoneId.of(props.getShift().getZone())).plusDays(1);
        dispatchService.deferDeliveryForRetry(shipmentId, loc.cityId(), tileId, lat, lon,
                event.orderId(), event.orderRef(), tomorrow, event.targetShift(), DeferReason.RECEIVER_REJECTED);
        log.info("Re-parked delivery of shipment {} for {} shift {} (receiver rejected)",
                shipmentId, tomorrow, event.targetShift());
    }
}
