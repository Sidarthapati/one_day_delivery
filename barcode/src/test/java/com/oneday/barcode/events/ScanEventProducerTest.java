package com.oneday.barcode.events;

import com.oneday.common.kafka.EventPublisher;
import com.oneday.common.kafka.EventStreams;
import com.oneday.common.kafka.enums.ScanEventType;
import com.oneday.common.kafka.events.ScanEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class ScanEventProducerTest {

    @Mock EventPublisher publisher;
    @InjectMocks ScanEventProducer producer;

    @Test
    void broadcasts_whenScanTypeIsAScanEventType() {
        UUID shipment = UUID.randomUUID();
        producer.onScanRecorded(new ScanRecorded(shipment, "1DD-DEL-260711-000042", "LABEL_GENERATED", Instant.now()));

        ArgumentCaptor<ScanEvent> sent = ArgumentCaptor.forClass(ScanEvent.class);
        verify(publisher).publish(eq(EventStreams.SCAN_EVENTS), sent.capture());
        assertThat(sent.getValue().eventType()).isEqualTo(ScanEventType.LABEL_GENERATED);
        assertThat(sent.getValue().parcelId()).isEqualTo("1DD-DEL-260711-000042");
    }

    @Test
    void skips_whenScanTypeIsAVanCustodyScan() {
        producer.onScanRecorded(new ScanRecorded(UUID.randomUUID(), null, "VAN_LOAD", Instant.now()));
        verifyNoInteractions(publisher); // van scans are ledger-only (D-004)
    }
}
