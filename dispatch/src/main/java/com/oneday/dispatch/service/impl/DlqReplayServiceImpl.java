package com.oneday.dispatch.service.impl;

import com.oneday.dispatch.config.DispatchProperties;
import com.oneday.dispatch.service.DlqReplayService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

/**
 * Drains a stream's {@code .dlq} and republishes each message to the source exchange, preserving the
 * original routing key (RabbitMQ keeps it on a dead-lettered message). Bounded by
 * {@code dispatch.dlq.replay-batch-limit}; stops early when the DLQ is empty.
 */
@Service
class DlqReplayServiceImpl implements DlqReplayService {

    private static final Logger log = LoggerFactory.getLogger(DlqReplayServiceImpl.class);

    private final RabbitTemplate rabbitTemplate;
    private final DispatchProperties props;

    DlqReplayServiceImpl(RabbitTemplate rabbitTemplate, DispatchProperties props) {
        this.rabbitTemplate = rabbitTemplate;
        this.props = props;
    }

    @Override
    public int replay(String exchange) {
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
