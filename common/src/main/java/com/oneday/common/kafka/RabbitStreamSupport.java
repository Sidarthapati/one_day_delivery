package com.oneday.common.kafka;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;

import java.util.ArrayList;
import java.util.List;

/**
 * Helpers for declaring RabbitMQ topology consistently across modules. A module's
 * {@code *MessagingTopology} config returns a {@link Declarables} built here; Spring's
 * {@code RabbitAdmin} declares everything on boot (idempotent — declaring the same durable
 * exchange from two modules is fine). See {@code docs/EVENT-BUS-ARCHITECTURE.md} §6.
 *
 * <p>Conventions: exchanges are durable <b>topic</b> exchanges named by an {@link EventStreams}
 * constant; one queue per (consuming module, exchange) named {@code <module>.<stream>}; each
 * consumer queue dead-letters to {@code <exchange>.dlx} → {@code <exchange>.dlq}.</p>
 */
public final class RabbitStreamSupport {

    private RabbitStreamSupport() {}

    /** Declare a producing module's exchange (use when nothing consumes it yet, so publishes aren't dropped). */
    public static Declarables exchange(String exchangeName) {
        return new Declarables(topicExchange(exchangeName));
    }

    /**
     * Declare a consumer binding: the source exchange + this module's durable queue (with a
     * dead-letter route) + the binding, plus the matching DLX exchange + DLQ queue + DLQ binding.
     *
     * @param queueName    this module's queue, e.g. {@code "orders.da"}
     * @param exchangeName the source stream/exchange, e.g. {@link EventStreams#DA_EVENTS}
     */
    public static Declarables consumer(String queueName, String exchangeName) {
        String dlxName = exchangeName + ".dlx";
        String dlqName = exchangeName + ".dlq";

        TopicExchange exchange = topicExchange(exchangeName);
        TopicExchange dlx = topicExchange(dlxName);

        Queue queue = QueueBuilder.durable(queueName)
                .deadLetterExchange(dlxName)
                .build();
        Queue dlq = QueueBuilder.durable(dlqName).build();

        Binding binding = BindingBuilder.bind(queue).to(exchange).with("#");
        Binding dlqBinding = BindingBuilder.bind(dlq).to(dlx).with("#");

        List<Declarable> declarables = new ArrayList<>(List.of(exchange, dlx, queue, dlq, binding, dlqBinding));
        return new Declarables(declarables);
    }

    private static TopicExchange topicExchange(String name) {
        return ExchangeBuilder.topicExchange(name).durable(true).build();
    }
}
