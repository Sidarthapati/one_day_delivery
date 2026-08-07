package com.oneday.airline.events;

import com.oneday.airline.service.AwbBookingService;
import com.oneday.common.kafka.events.hub.BagSealedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitHandler;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Consumes M7's hub events from {@code oneday.hub.events} (§14.1). BAG_SEALED is the sole booking
 * trigger (§6) — but M7 also publishes {@code BagSealedEvent} for inbound <em>delivery</em> bags
 * (route/territory bags, no flight involved), distinguished only by a null {@code flightNo}
 * ({@code HubEventProducer.emitDeliveryBagSealed}); those are ignored here.
 */
@Component
@RabbitListener(queues = AirlineMessagingTopology.HUB_QUEUE)
public class HubEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(HubEventConsumer.class);

    private final AwbBookingService awbBookingService;

    public HubEventConsumer(AwbBookingService awbBookingService) {
        this.awbBookingService = awbBookingService;
    }

    @RabbitHandler
    public void onBagSealed(BagSealedEvent event) {
        if (event.flightNo() == null) {
            log.trace("Ignoring BAG_SEALED for an inbound delivery bag (no flight): bag {}", event.bagId());
            return;
        }
        awbBookingService.book(new AwbBookingService.BookBagCommand(
                event.bagId(), event.flightNo(), event.flightDate(), event.parcelCount(), event.weightGrams()));
        log.debug("Booked bag {} onto flight {} ({})", event.bagId(), event.flightNo(), event.flightDate());
    }

    @RabbitHandler(isDefault = true)
    public void onOther(Object event) {
        // STAND_ASSIGNED/BAG_CREATED/MANIFEST_GENERATED/etc. — no M9 reaction needed this milestone.
        log.trace("Ignoring non-booking hub event {}", event.getClass().getSimpleName());
    }
}
