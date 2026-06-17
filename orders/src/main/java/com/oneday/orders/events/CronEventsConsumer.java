package com.oneday.orders.events;

import com.oneday.common.kafka.events.CronEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Consumes M6 routing events from the {@code oneday.cron.events} exchange (queue
 * {@code orders.cron}). Until M6 produces cron events M4 acts on, this logs and ignores.
 *
 * <p>The {@link com.oneday.common.kafka.enums.CronEventType} discriminator was redefined for
 * M6's full van lifecycle (design §17.1). Of those, only {@code HANDOFF_COMPLETED} concerns M4 —
 * but per-shipment custody transitions ({@code HANDED_TO_DROP_VAN}, {@code DROP_COLLECTED}, …)
 * are driven by the <strong>M8 scan ledger</strong> ({@code oneday.scan.events}), not this topic.
 * So this consumer logs and ignores for now; the concrete cron→M4 mapping is settled with the
 * M6 producer PRs and the M4 owner. Kept (dormant) so the wiring + topic subscription survive.</p>
 */
@Component
public class CronEventsConsumer {

    private static final Logger log = LoggerFactory.getLogger(CronEventsConsumer.class);

    @RabbitListener(queues = OrdersMessagingTopology.CRON_QUEUE)
    public void onCronEvent(CronEvent event) {
        // TODO(M6): map the cron events M4 acts on once the M6 producer contract is finalized.
        log.debug("Received cron event type={} shipmentId={} — no M4 transition wired yet",
                event.eventTypeName(), event.shipmentId());
    }
}
