package com.oneday.dispatch.events;

import com.oneday.common.kafka.EventPublisher;
import com.oneday.common.kafka.EventStreams;
import com.oneday.common.kafka.enums.DaEventType;
import com.oneday.common.kafka.events.DaLifecycleEvent;
import com.oneday.dispatch.config.DispatchProperties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class DaEventProducerTest {

    @Test
    void suppressesPublishWhenFlagOff() {
        EventPublisher publisher = mock(EventPublisher.class);
        DaEventProducer producer = new DaEventProducer(publisher, new DispatchProperties());   // default false

        producer.emitDaAbsent(UUID.randomUUID(), UUID.randomUUID());

        verify(publisher, never()).publish(any(), any());
    }

    @Test
    void publishesDaAbsentWhenFlagOn() {
        EventPublisher publisher = mock(EventPublisher.class);
        DispatchProperties props = new DispatchProperties();
        props.getEvents().setPublishDaEvents(true);
        DaEventProducer producer = new DaEventProducer(publisher, props);

        UUID da = UUID.randomUUID();
        UUID city = UUID.randomUUID();
        producer.emitDaAbsent(da, city);

        ArgumentCaptor<DaLifecycleEvent> captor = ArgumentCaptor.forClass(DaLifecycleEvent.class);
        verify(publisher).publish(eq(EventStreams.DA_EVENTS), captor.capture());
        DaLifecycleEvent e = captor.getValue();
        assertThat(e.eventType()).isEqualTo(DaEventType.DA_ABSENT);
        assertThat(e.daId()).isEqualTo(da);
        assertThat(e.cityId()).isEqualTo(city);
        assertThat(e.shipmentId()).isNull();
        assertThat(e.partitionKey()).isEqualTo(da.toString());   // DA-scoped → keyed by daId
    }
}
