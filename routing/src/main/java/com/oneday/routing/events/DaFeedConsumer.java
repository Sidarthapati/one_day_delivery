package com.oneday.routing.events;

import com.oneday.routing.domain.InboundKind;
import com.oneday.routing.domain.InboundParcel;
import com.oneday.routing.events.payload.DaParcelPickedUpEvent;
import com.oneday.routing.repository.InboundParcelRepository;
import com.oneday.routing.service.VanManifestService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

// M5 DA-pickup → record (audit) + bind the parcel immediately to its latest feasible loop (§12.2).
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
    public void onDaPickup(DaParcelPickedUpEvent event) {
        if (!repository.existsByKindAndParcelId(InboundKind.COLLECT, event.parcelId())) {
            repository.save(InboundParcel.builder()
                    .kind(InboundKind.COLLECT)
                    .parcelId(event.parcelId())
                    .cityId(event.cityId())
                    .daId(event.daId())
                    .pickedUpAt(event.pickedUpAt())
                    .validDate(event.validDate())
                    .build());
        }
        manifestService.bindCollect(event.cityId(), event.validDate(), event.parcelId(), event.daId());
        log.debug("Bound COLLECT parcel {} da {} date {}", event.parcelId(), event.daId(), event.validDate());
    }
}
