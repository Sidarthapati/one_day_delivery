package com.oneday.dispatch.events;

import com.oneday.common.kafka.EventPublisher;
import com.oneday.common.kafka.EventStreams;
import com.oneday.common.kafka.enums.ScanEventType;
import com.oneday.common.kafka.events.ScanEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class HubScanSeamProducerTest {

    @AfterEach
    void tearDown() {
        // Leave no synchronization bound if a test opened one (a failed assertion could skip cleanup).
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void publishesInlineWhenNoTransactionIsActive() {
        EventPublisher publisher = mock(EventPublisher.class);
        HubScanSeamProducer producer = new HubScanSeamProducer(publisher);
        UUID shipment = UUID.randomUUID();

        producer.emitDaCustodyTransfer(shipment);

        ArgumentCaptor<ScanEvent> captor = ArgumentCaptor.forClass(ScanEvent.class);
        verify(publisher).publish(eq(EventStreams.SCAN_EVENTS), captor.capture());
        assertThat(captor.getValue().eventType()).isEqualTo(ScanEventType.DA_CUSTODY_TRANSFER);
        assertThat(captor.getValue().shipmentId()).isEqualTo(shipment);
    }

    @Test
    void defersPublishUntilCommitWhenInsideATransaction() {
        EventPublisher publisher = mock(EventPublisher.class);
        HubScanSeamProducer producer = new HubScanSeamProducer(publisher);

        TransactionSynchronizationManager.initSynchronization();
        try {
            producer.emitDaCustodyTransfer(UUID.randomUUID());
            // Nothing published while the transaction is still open.
            verify(publisher, never()).publish(any(), any());
            // Simulate commit.
            for (TransactionSynchronization s : TransactionSynchronizationManager.getSynchronizations()) {
                s.afterCommit();
            }
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        verify(publisher).publish(eq(EventStreams.SCAN_EVENTS), any(ScanEvent.class));
    }

    @Test
    void neverPublishesWhenTheTransactionRollsBack() {
        EventPublisher publisher = mock(EventPublisher.class);
        HubScanSeamProducer producer = new HubScanSeamProducer(publisher);

        TransactionSynchronizationManager.initSynchronization();
        try {
            producer.emitDaCustodyTransfer(UUID.randomUUID());
            // Rollback: afterCommit is never invoked, synchronizations are simply discarded.
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        verify(publisher, never()).publish(any(), any());
    }
}
