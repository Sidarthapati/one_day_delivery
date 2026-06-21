package com.oneday.dispatch.service.impl;

import com.oneday.common.kafka.EventStreams;
import com.oneday.dispatch.config.DispatchProperties;
import com.oneday.dispatch.service.DlqReplayService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.Set;

/**
 * Drains a stream's {@code .dlq} and republishes each message to the source exchange, preserving the
 * original routing key (RabbitMQ keeps it on a dead-lettered message). Bounded by
 * {@code dispatch.dlq.replay-batch-limit}; stops early when the DLQ is empty.
 *
 * <p>Only M5's own consumer streams may be re-driven (allow-list) — this both prevents publishing to
 * an arbitrary, caller-supplied exchange and keeps the value logged here from being attacker-tainted.</p>
 */
@Service
class DlqReplayServiceImpl implements DlqReplayService {

    private static final Logger log = LoggerFactory.getLogger(DlqReplayServiceImpl.class);

    /** Streams M5 consumes (and therefore has a DLQ for). */
    private static final Set<String> REPLAYABLE = Set.of(
            EventStreams.SHIPMENTS_EVENTS, EventStreams.CRON_EVENTS);

    private final RabbitTemplate rabbitTemplate;
    private final DispatchProperties props;

    DlqReplayServiceImpl(RabbitTemplate rabbitTemplate, DispatchProperties props) {
        this.rabbitTemplate = rabbitTemplate;
        this.props = props;
    }

    @Override
    public int replay(String exchange) {
        if (!REPLAYABLE.contains(exchange)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Stream is not a replayable M5 dead-letter queue");
        }
        String dlq = exchange + ".dlq";
        int limit = props.getDlq().getReplayBatchLimit();
        int replayed = 0;
        for (int i = 0; i < limit; i++) {
            Message message = rabbitTemplate.receive(dlq);
            if (message == null) {
                break;   // DLQ drained
            }
            String routingKey = message.getMessageProperties().getReceivedRoutingKey();
            rabbitTemplate.send(exchange, routingKey, message);
            replayed++;
        }
        log.info("Re-drove {} message(s) from {} back to {}", replayed, dlq, exchange);
        return replayed;
    }
}
