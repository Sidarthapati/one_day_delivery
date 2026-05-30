package com.oneday.common.kafka;

import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * One error handler for every @KafkaListener in the app. Spring Boot auto-wires the single
 * {@link DefaultErrorHandler} bean into all listener containers.
 *
 * Behaviour: if a consumer throws while processing a record, retry 3 times (1s apart); if it
 * still fails, publish that one record to "<original-topic>.dlq" and move on — so a single
 * poison message can never block its partition forever. Inspect/replay the .dlq topic later.
 */
@Configuration
public class KafkaErrorConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaErrorConfig.class);

    private static final long RETRY_INTERVAL_MS = 1_000L;
    private static final long MAX_RETRIES = 3L;

    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, Object> kafkaTemplate) {
        // Route failed records to "<topic>.dlq". Partition -1 ⇒ broker picks by key
        // (so the DLQ topic can have a different partition count than the source).
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                kafkaTemplate,
                (record, ex) -> new TopicPartition(record.topic() + ".dlq", -1));

        DefaultErrorHandler handler =
                new DefaultErrorHandler(recoverer, new FixedBackOff(RETRY_INTERVAL_MS, MAX_RETRIES));

        handler.setRetryListeners((record, ex, attempt) ->
                log.warn("Kafka consume retry {} — topic={} key={}: {}",
                        attempt, record.topic(), record.key(), ex.getMessage()));

        return handler;
    }
}
