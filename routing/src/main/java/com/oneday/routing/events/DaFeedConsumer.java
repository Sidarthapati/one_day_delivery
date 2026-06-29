package com.oneday.routing.events;

import com.oneday.common.kafka.enums.DaEventType;
import com.oneday.common.kafka.events.DaLifecycleEvent;
import com.oneday.routing.domain.InboundKind;
import com.oneday.routing.domain.InboundParcel;
import com.oneday.routing.repository.InboundParcelRepository;
import com.oneday.routing.service.VanManifestService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.UUID;

// M5 DA-pickup → record (audit) + bind the parcel immediately to its latest feasible loop (§12.2).
// DA_EVENTS carries the whole DA lifecycle on one #-bound queue; this consumer takes the rich
// DaLifecycleEvent and acts ONLY on PICKUP_COMPLETED (the collect-bind trigger), ignoring the rest
// (DA_ABSENT, QUEUE_REORDERED, VAN_HANDOFF_COMPLETED, …). v1: parcelId == shipmentId (no M8 barcode).
@Component
public class DaFeedConsumer {

    private static final Logger log = LoggerFactory.getLogger(DaFeedConsumer.class);

    private final InboundParcelRepository repository;
    private final VanManifestService manifestService;

    public DaFeedConsumer(InboundParcelRepository repository, VanManifestService manifestService) {
        this.repository = repository;
        this.manifestService = manifestService;
    }

    @RabbitListener(queues = RoutingMessagingTopology.DA_FEED_QUEUE)
    public void onDaEvent(DaLifecycleEvent event) {
        if (event.eventType() != DaEventType.PICKUP_COMPLETED) {
            return;   // not a collect-bind trigger
        }
        UUID parcelId = event.parcelId() != null ? event.parcelId() : event.shipmentId();
        if (parcelId == null || event.cityId() == null || event.validDate() == null) {
            log.warn("PICKUP_COMPLETED missing parcelId/cityId/validDate (parcel={} city={} date={}) — skipping bind",
                    parcelId, event.cityId(), event.validDate());
            return;
        }
        if (!repository.existsByKindAndParcelId(InboundKind.COLLECT, parcelId)) {
            repository.save(InboundParcel.builder()
                    .kind(InboundKind.COLLECT)
                    .parcelId(parcelId)
                    .cityId(event.cityId())
                    .daId(event.daId())
                    .pickedUpAt(event.occurredAt())
                    .validDate(event.validDate())
                    .build());
        }
        manifestService.bindCollect(event.cityId(), event.validDate(), parcelId, event.daId());
        log.debug("Bound COLLECT parcel {} da {} date {}", parcelId, event.daId(), event.validDate());
    }
}
