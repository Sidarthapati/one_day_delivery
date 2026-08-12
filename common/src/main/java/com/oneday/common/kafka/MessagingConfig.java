package com.oneday.common.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.support.converter.DefaultJackson2JavaTypeMapper;
import org.springframework.amqp.support.converter.Jackson2JavaTypeMapper;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Shared RabbitMQ messaging config. Defining a single {@link MessageConverter} bean makes Spring
 * Boot's auto-configured {@code RabbitTemplate} (producer) and listener container factory
 * (consumer) both serialize/deserialize event payloads as JSON — the drop-in replacement for the
 * Kafka {@code JsonSerializer}/{@code JsonDeserializer} pair.
 *
 * <p>Retry + dead-lettering are configured declaratively: listener retry via
 * {@code spring.rabbitmq.listener.simple.retry.*} (app config), and a {@code <stream>.dlx} /
 * {@code <stream>.dlq} per consumer queue via the {@code *MessagingTopology} configs
 * ({@link RabbitStreamSupport}). See {@code docs/EVENT-BUS-ARCHITECTURE.md} §3, §6.</p>
 */
@Configuration
public class MessagingConfig {

    @Bean
    public MessageConverter jsonMessageConverter(ObjectMapper objectMapper) {
        Jackson2JsonMessageConverter converter = new Jackson2JsonMessageConverter(objectMapper);
        // Type resolution = INFERRED: deserialize each message to the TYPE THE LISTENER METHOD DECLARES
        // (e.g. HubEvent), not the concrete class named in the producer's __TypeId__ header. Several M7
        // events (StandAssignedEvent, BagCreatedEvent, …) publish their own concrete __TypeId__ but the
        // orders.hub consumer binds the umbrella HubEvent — a header-driven ClassMapper deserialized to
        // the concrete class and then failed "Cannot convert from [StandAssignedEvent] to [HubEvent]",
        // dead-lettering every hub event and freezing shipments at AT_ORIGIN_HUB. Inference sidesteps the
        // mismatch (the shared ObjectMapper ignores unknown fields, so the umbrella type absorbs any
        // event's JSON). Still trust all packages for the header fallback (receiveAndConvert without a
        // target type) — in-house monolith, every producer is ours.
        DefaultJackson2JavaTypeMapper typeMapper = new DefaultJackson2JavaTypeMapper();
        typeMapper.setTrustedPackages("*");
        typeMapper.setTypePrecedence(Jackson2JavaTypeMapper.TypePrecedence.INFERRED);
        converter.setJavaTypeMapper(typeMapper);
        return converter;
    }

    /** Convenience for tests/aliases that still reference a List of trusted packages. */
    static List<String> trustedPackages() {
        return List.of("*");
    }
}
