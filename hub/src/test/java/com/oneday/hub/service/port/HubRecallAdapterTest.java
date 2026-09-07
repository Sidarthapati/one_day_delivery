package com.oneday.hub.service.port;

import com.oneday.common.port.HubRecallPort.RecallOutcome;
import com.oneday.hub.domain.FlightBag;
import com.oneday.hub.domain.FlightBagItem;
import com.oneday.hub.domain.FlightBagItemStatus;
import com.oneday.hub.domain.FlightBagStatus;
import com.oneday.hub.repository.FlightBagItemRepository;
import com.oneday.hub.repository.FlightBagRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Origin recall: pull from an OPEN bag; leave a SEALED bag to fly. */
class HubRecallAdapterTest {

    private FlightBagItemRepository itemRepo;
    private FlightBagRepository bagRepo;
    private HubRecallAdapter adapter;

    private final UUID shipmentId = UUID.randomUUID();
    private final UUID bagId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        itemRepo = mock(FlightBagItemRepository.class);
        bagRepo = mock(FlightBagRepository.class);
        adapter = new HubRecallAdapter(itemRepo, bagRepo, Clock.fixed(Instant.now(), ZoneOffset.UTC));
    }

    private FlightBagItem item() {
        return FlightBagItem.builder()
                .bagId(bagId).parcelId(shipmentId).shipmentRef("REF")
                .weightGrams(2000).status(FlightBagItemStatus.IN_BAG).build();
    }

    private FlightBag bag(FlightBagStatus status) {
        FlightBag b = new FlightBag();
        b.setStatus(status);
        b.setParcelCount(3);
        b.setWeightGrams(6000);
        b.setFlightNo("6E123");
        return b;
    }

    @Test
    void openBagIsRecalledAndItemRemoved() {
        FlightBagItem it = item();
        when(itemRepo.findFirstByParcelIdAndStatus(shipmentId, FlightBagItemStatus.IN_BAG))
                .thenReturn(Optional.of(it));
        when(bagRepo.findByIdForUpdate(bagId)).thenReturn(Optional.of(bag(FlightBagStatus.OPEN))); // recall locks the bag

        RecallOutcome outcome = adapter.recallAtOrigin(shipmentId);

        assertThat(outcome).isEqualTo(RecallOutcome.PULLED_FROM_BAG);
        assertThat(it.getStatus()).isEqualTo(FlightBagItemStatus.REMOVED);
        assertThat(it.getRemovedAt()).isNotNull();
        verify(itemRepo).save(it);
    }

    @Test
    void sealedBagIsCommitted() {
        when(itemRepo.findFirstByParcelIdAndStatus(shipmentId, FlightBagItemStatus.IN_BAG))
                .thenReturn(Optional.of(item()));
        when(bagRepo.findByIdForUpdate(bagId)).thenReturn(Optional.of(bag(FlightBagStatus.SEALED)));

        assertThat(adapter.recallAtOrigin(shipmentId)).isEqualTo(RecallOutcome.COMMITTED);
        verify(itemRepo, never()).save(any());
    }

    @Test
    void notBaggedIsRecalledWithNothingToPull() {
        when(itemRepo.findFirstByParcelIdAndStatus(shipmentId, FlightBagItemStatus.IN_BAG))
                .thenReturn(Optional.empty());

        assertThat(adapter.recallAtOrigin(shipmentId)).isEqualTo(RecallOutcome.NOT_BAGGED);
        verify(itemRepo, never()).save(any());
    }
}
