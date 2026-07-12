package com.oneday.routing.events;

import com.oneday.routing.domain.InboundKind;
import com.oneday.routing.domain.InboundParcel;
import com.oneday.common.kafka.events.hub.HubEventPayload;
import com.oneday.common.kafka.events.hub.ParcelSortedForDeliveryEvent;
import com.oneday.routing.repository.InboundParcelRepository;
import com.oneday.routing.service.VanManifestService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

// M7 sorted-for-delivery → record (audit) + bind the parcel immediately to a van/loop (§12.1).
@Component
public class HubFeedConsumer {

    private static final Logger log = LoggerFactory.getLogger(HubFeedConsumer.class);

    private final InboundParcelRepository repository;
    private final VanManifestService manifestService;

    public HubFeedConsumer(InboundParcelRepository repository, VanManifestService manifestService) {
        this.repository = repository;
        this.manifestService = manifestService;
    }

    // The queue is bound `#` to oneday.hub.events, so it receives EVERY hub event shape. Take the
    // sealed supertype and act only on ParcelSortedForDelivery — the other shapes (BagCreated,
    // StandAssigned, …) are M7-internal and ignored here. Binding the concrete type instead made all
    // the non-matching shapes fail deserialization and dead-letter (house pattern: one #-bound queue,
    // supertype param, dispatch in code — see orders.HubEventsConsumer).
    @RabbitListener(queues = RoutingMessagingTopology.HUB_FEED_QUEUE)
    public void onHubEvent(HubEventPayload payload) {
        if (!(payload instanceof ParcelSortedForDeliveryEvent event)) {
            return;
        }
        if (!repository.existsByKindAndParcelId(InboundKind.DELIVER, event.parcelId())) {
            repository.save(InboundParcel.builder()
                    .kind(InboundKind.DELIVER)
                    .parcelId(event.parcelId())
                    .cityId(event.cityId())
                    .destinationHexId(event.destinationHexId())
                    .readyAt(event.sortedAt())
                    .slaDeadline(event.slaDeadline())
                    .validDate(event.validDate())
                    .build());
        }
        manifestService.bindDelivery(event.cityId(), event.validDate(), event.parcelId(),
                event.destinationHexId(), event.slaDeadline());
        log.debug("Bound DELIVER parcel {} city {} date {}", event.parcelId(), event.cityId(), event.validDate());
    }
}
