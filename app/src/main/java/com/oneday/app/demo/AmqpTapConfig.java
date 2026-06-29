package com.oneday.app.demo;

import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.amqp.RabbitTemplateConfigurer;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Demo-only ({@code @Profile("!prod")}) wiring that taps the real RabbitMQ traffic into {@link AmqpTap}
 * for the live Execution feed. It re-declares the two beans Spring Boot would otherwise auto-configure,
 * each built through Boot's own {@code *Configurer} so all the standard defaults (connection factory,
 * JSON converter, listener retry/DLQ, concurrency) are preserved — we only add an observer.
 *
 * <ul>
 *   <li><b>Publish</b>: a {@link RabbitTemplate} subclass overriding the {@code send(...)} funnel that
 *       every {@code convertAndSend} routes through, so each real publish is recorded with its true
 *       exchange + routing key + event type (the {@code __TypeId__} header).</li>
 *   <li><b>Consume</b>: an {@code afterReceivePostProcessor} on the listener container factory, which
 *       fires for every message any {@code @RabbitListener} actually receives.</li>
 * </ul>
 *
 * <p>Both are pure observers — they return the message unchanged and never alter the bus. In prod these
 * beans are absent and Boot's untouched auto-configuration is used.</p>
 */
@Configuration
@Profile("!prod")
class AmqpTapConfig {

    /** Producer side: tap the central send funnel so every publish is captured with exchange + type. */
    @Bean
    RabbitTemplate rabbitTemplate(RabbitTemplateConfigurer configurer,
                                  ConnectionFactory connectionFactory,
                                  AmqpTap tap) {
        RabbitTemplate template = new RabbitTemplate() {
            @Override
            public void send(String exchange, String routingKey, Message message, CorrelationData correlationData) {
                try {
                    tap.publish(exchange, routingKey, typeOf(message));
                } catch (RuntimeException ignore) {
                    // observation must never break a publish
                }
                super.send(exchange, routingKey, message, correlationData);
            }
        };
        configurer.configure(template, connectionFactory);
        return template;
    }

    /** Consumer side: record every real delivery to any @RabbitListener. */
    @Bean
    SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer,
            ConnectionFactory connectionFactory,
            AmqpTap tap) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        factory.setAfterReceivePostProcessors(message -> {
            try {
                tap.consume(message.getMessageProperties().getConsumerQueue(), typeOf(message));
            } catch (RuntimeException ignore) {
                // observation must never break a delivery
            }
            return message;
        });
        return factory;
    }

    /** Simple name of the event from the {@code __TypeId__} header Jackson sets on every payload. */
    private static String typeOf(Message message) {
        Object typeId = message.getMessageProperties().getHeader("__TypeId__");
        if (typeId == null) return "?";
        String fqcn = typeId.toString();
        int dot = fqcn.lastIndexOf('.');
        return dot >= 0 ? fqcn.substring(dot + 1) : fqcn;
    }
}
