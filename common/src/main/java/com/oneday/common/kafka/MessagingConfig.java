package com.oneday.common.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.support.converter.DefaultClassMapper;
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
        // In-house monolith: every producer is our own code, so trust all packages for __TypeId__
        // deserialization. NOTE: DefaultClassMapper does NOT do prefix/wildcard matching — a literal
        // "com.oneday.*" matches no package (it only honours the exact "*" = trust-all token or exact
        // package names), so it silently rejected every com.oneday event and broke ALL @RabbitListener
        // consumers against a real broker. Use the trust-all token. Tighten to exact package names if
        // an external/untrusted producer ever writes to a stream we consume.
        DefaultClassMapper classMapper = new DefaultClassMapper();
        classMapper.setTrustedPackages("*");
        converter.setClassMapper(classMapper);
        return converter;
    }

    /**
     * Replaces Boot's auto-configured RabbitAdmin with one that does NOT abort on a single bad
     * declaration. RabbitAdmin declares every {@code *MessagingTopology} bean in one pass; if one
     * queue conflicts with pre-existing broker state (a channel-level {@code PRECONDITION_FAILED}),
     * the default admin lets the channel die and silently SKIPS every queue after it in that pass —
     * which is how a single stale queue left {@code m5.shipments}/{@code orders.flight}/… undeclared
     * and their consumers unbound. {@code ignoreDeclarationExceptions} makes each declaration
     * independent, so the whole topology always comes up.
     */
    @Bean
    public RabbitAdmin rabbitAdmin(ConnectionFactory connectionFactory) {
        RabbitAdmin admin = new RabbitAdmin(connectionFactory);
        admin.setIgnoreDeclarationExceptions(true);
        return admin;
    }

    /** Convenience for tests/aliases that still reference a List of trusted packages. */
    static List<String> trustedPackages() {
        return List.of("*");
    }
}
