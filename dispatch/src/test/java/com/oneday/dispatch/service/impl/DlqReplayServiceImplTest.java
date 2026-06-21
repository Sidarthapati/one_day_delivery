package com.oneday.dispatch.service.impl;

import com.oneday.dispatch.config.DispatchProperties;
import com.oneday.dispatch.service.DlqReplayService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class DlqReplayServiceImplTest {

    private RabbitTemplate rabbitTemplate;
    private DlqReplayService service;

    private static final String EXCHANGE = "oneday.shipments.events";
    private static final String DLQ = "oneday.shipments.events.dlq";

    @BeforeEach
    void setUp() {
        rabbitTemplate = mock(RabbitTemplate.class);
        service = new DlqReplayServiceImpl(rabbitTemplate, new DispatchProperties());
    }

    @Test
    void republishesEachParkedMessageToSourceExchange() {
        Message parked = message("CREATED");
        when(rabbitTemplate.receive(DLQ)).thenReturn(parked, (Message) null);

        int replayed = service.replay(EXCHANGE);

        assertThat(replayed).isEqualTo(1);
        verify(rabbitTemplate).send(eq(EXCHANGE), eq("CREATED"), eq(parked));
    }

    @Test
    void emptyDlqReplaysNothing() {
        when(rabbitTemplate.receive(DLQ)).thenReturn(null);

        int replayed = service.replay(EXCHANGE);

        assertThat(replayed).isZero();
        verify(rabbitTemplate).receive(DLQ);
        verifyNoMoreInteractions(rabbitTemplate);
    }

    private Message message(String routingKey) {
        MessageProperties props = new MessageProperties();
        props.setReceivedRoutingKey(routingKey);
        return new Message("{}".getBytes(), props);
    }
}
