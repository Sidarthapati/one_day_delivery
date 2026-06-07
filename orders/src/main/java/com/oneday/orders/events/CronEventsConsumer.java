package com.oneday.orders.events;

import com.oneday.common.kafka.KafkaTopics;
import com.oneday.common.kafka.events.CronEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes M6 routing events from {@code oneday.cron.events}. Dormant by default
 * ({@code autoStartup=false}) until M6 produces on this topic.
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

    @KafkaListener(topics = KafkaTopics.CRON_EVENTS, groupId = "orders-service",
            autoStartup = "${orders.kafka.consumer.auto-startup:false}")
    public void onCronEvent(CronEvent event) {
        // TODO(M6): map the cron events M4 acts on once the M6 producer contract is finalized.
        log.debug("Received cron event type={} shipmentId={} — no M4 transition wired yet",
                event.eventTypeName(), event.shipmentId());
    }
}
