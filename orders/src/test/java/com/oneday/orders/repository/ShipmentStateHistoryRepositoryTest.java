package com.oneday.orders.repository;

import com.oneday.common.domain.enums.ShipmentState;
import com.oneday.orders.domain.Shipment;
import com.oneday.orders.domain.ShipmentStateHistory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ShipmentStateHistoryRepository")
class ShipmentStateHistoryRepositoryTest extends AbstractRepositoryTest {

    @Autowired
    private ShipmentStateHistoryRepository repo;

    @Autowired
    private ShipmentRepository shipmentRepository;

    @Test
    void saveAndFindById_roundTrip() {
        Shipment shipment = shipmentRepository.save(TestFixtures.shipment("TST-SSH-RND", "DEL"));
        ShipmentStateHistory saved = repo.save(
                TestFixtures.stateHistory(shipment.getId(), ShipmentState.PICKED_UP, Instant.now()));

        assertThat(repo.findById(saved.getId())).isPresent();
        assertThat(saved.getShipmentId()).isEqualTo(shipment.getId());
        assertThat(saved.getToState()).isEqualTo(ShipmentState.PICKED_UP);
    }

    @Test
    void findByShipmentIdOrderByOccurredAtAsc_returnsChronologicalOrder() {
        Shipment shipment = shipmentRepository.save(TestFixtures.shipment("TST-SSH-ORD", "DEL"));
        UUID shipmentId = shipment.getId();

        Instant t0 = Instant.now().minus(2, ChronoUnit.MINUTES);
        Instant t1 = Instant.now().minus(1, ChronoUnit.MINUTES);
        Instant t2 = Instant.now();

        // Save out-of-order intentionally
        repo.save(TestFixtures.stateHistory(shipmentId, ShipmentState.HANDED_TO_PICKUP_VAN, t2));
        repo.save(TestFixtures.stateHistory(shipmentId, ShipmentState.PICKUP_ASSIGNED, t0));
        repo.save(TestFixtures.stateHistory(shipmentId, ShipmentState.PICKED_UP, t1));

        List<ShipmentStateHistory> result = repo.findByShipmentIdOrderByOccurredAtAsc(shipmentId);

        assertThat(result).hasSize(3);
        assertThat(result).extracting(ShipmentStateHistory::getToState).containsExactly(
                ShipmentState.PICKUP_ASSIGNED,
                ShipmentState.PICKED_UP,
                ShipmentState.HANDED_TO_PICKUP_VAN
        );
    }

    @Test
    void findByShipmentIdOrderByOccurredAtAsc_doesNotReturnOtherShipments() {
        Shipment shipA = shipmentRepository.save(TestFixtures.shipment("TST-SSH-A", "DEL"));
        Shipment shipB = shipmentRepository.save(TestFixtures.shipment("TST-SSH-B", "DEL"));

        repo.save(TestFixtures.stateHistory(shipA.getId(), ShipmentState.BOOKED, Instant.now()));
        repo.save(TestFixtures.stateHistory(shipB.getId(), ShipmentState.PICKED_UP, Instant.now()));

        assertThat(repo.findByShipmentIdOrderByOccurredAtAsc(shipA.getId())).hasSize(1);
        assertThat(repo.findByShipmentIdOrderByOccurredAtAsc(shipB.getId())).hasSize(1);
    }
}
