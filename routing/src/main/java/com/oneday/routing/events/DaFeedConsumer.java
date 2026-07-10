package com.oneday.routing.events;

import com.oneday.common.kafka.enums.DaEventType;
import com.oneday.common.kafka.events.DaLifecycleEvent;
import com.oneday.routing.config.ClockConfig;
import com.oneday.routing.domain.InboundKind;
import com.oneday.routing.domain.InboundParcel;
import com.oneday.routing.repository.InboundParcelRepository;
import com.oneday.routing.service.VanManifestService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.UUID;

// M5 DA-pickup → record (audit) + bind the parcel immediately to its latest feasible loop (§12.2).
// Consumes M5's shared common.…DaLifecycleEvent (the single rich type on oneday.da.events) and acts
// only on PICKUP_COMPLETED, reading the carried parcelId + validDate (parcelId ≡ shipmentId in v1).
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
    public void onDaPickup(DaLifecycleEvent event) {
        // PICKUP_COMPLETED = parcel is in DA custody, ready for the collect van. Other DA lifecycle
        // types (ABSENT, QUEUE_REORDERED, VAN_HANDOFF_COMPLETED, …) are not collect-bind triggers.
        if (event.eventType() != DaEventType.PICKUP_COMPLETED) {
            return;
        }
        // parcelId == shipmentId in v1 (no M8 barcode yet); both are carried explicitly by M5 now.
        UUID parcelId = event.parcelId() != null ? event.parcelId() : event.shipmentId();
        if (parcelId == null || event.cityId() == null || event.daId() == null) {
            log.warn("Skipping PICKUP_COMPLETED with missing ids: parcel={} city={} da={}",
                    parcelId, event.cityId(), event.daId());
            return;
        }

        // M5 stamps validDate (operating date); fall back to the pickup instant's IST day.
        LocalDate validDate = event.validDate() != null
                ? event.validDate()
                : event.occurredAt().atZone(ClockConfig.IST).toLocalDate();

        if (!repository.existsByKindAndParcelId(InboundKind.COLLECT, parcelId)) {
            repository.save(InboundParcel.builder()
                    .kind(InboundKind.COLLECT)
                    .parcelId(parcelId)
                    .cityId(event.cityId())
                    .daId(event.daId())
                    .pickedUpAt(event.occurredAt())
                    .validDate(validDate)
                    .build());
        }
        manifestService.bindCollect(event.cityId(), validDate, parcelId, event.daId());
        log.debug("Bound COLLECT parcel {} da {} date {}", parcelId, event.daId(), validDate);
    }
}
