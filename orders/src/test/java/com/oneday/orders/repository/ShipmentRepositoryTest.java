package com.oneday.orders.repository;

import com.oneday.common.domain.enums.ShipmentState;
import com.oneday.orders.domain.Shipment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ShipmentRepository")
class ShipmentRepositoryTest extends AbstractRepositoryTest {

    @Autowired
    private ShipmentRepository repo;

    @Test
    void saveAndFindById_roundTrip() {
        Shipment saved = repo.save(TestFixtures.shipment("TST-DEL-001", "DEL"));

        Optional<Shipment> found = repo.findById(saved.getId());

        assertThat(found).isPresent();
        assertThat(found.get().getShipmentRef()).isEqualTo("TST-DEL-001");
        assertThat(found.get().getCityId()).isEqualTo("DEL");
        assertThat(found.get().getState()).isEqualTo(ShipmentState.BOOKED);
    }

    @Test
    void findByShipmentRef_returnsMatchingShipment() {
        repo.save(TestFixtures.shipment("TST-DEL-REF-A", "DEL"));
        repo.save(TestFixtures.shipment("TST-DEL-REF-B", "DEL"));

        Optional<Shipment> found = repo.findByShipmentRef("TST-DEL-REF-A");

        assertThat(found).isPresent();
        assertThat(found.get().getShipmentRef()).isEqualTo("TST-DEL-REF-A");
    }

    @Test
    void findByShipmentRef_emptyWhenNotFound() {
        assertThat(repo.findByShipmentRef("NONEXISTENT-REF")).isEmpty();
    }

    @Test
    void findByState_returnsOnlyMatchingState() {
        Shipment booked1 = TestFixtures.shipment("TST-DEL-ST-001", "DEL");
        Shipment booked2 = TestFixtures.shipment("TST-DEL-ST-002", "DEL");
        Shipment pickedUp = TestFixtures.shipment("TST-DEL-ST-003", "DEL");
        pickedUp.setState(ShipmentState.PICKED_UP);

        repo.save(booked1);
        repo.save(booked2);
        repo.save(pickedUp);

        List<Shipment> bookedShipments = repo.findByState(ShipmentState.BOOKED);

        assertThat(bookedShipments)
                .extracting(Shipment::getShipmentRef)
                .contains("TST-DEL-ST-001", "TST-DEL-ST-002")
                .doesNotContain("TST-DEL-ST-003");
    }

    @Test
    void findByStateAndCityId_filtersOnBothColumns() {
        Shipment delhiBooked = TestFixtures.shipment("TST-DEL-SC-001", "DEL");
        Shipment mumbaiBooked = TestFixtures.shipment("TST-BOM-SC-001", "BOM");
        mumbaiBooked.setOriginCity("BOM");

        repo.save(delhiBooked);
        repo.save(mumbaiBooked);

        List<Shipment> results = repo.findByStateAndCityId(ShipmentState.BOOKED, "DEL");

        assertThat(results)
                .extracting(Shipment::getShipmentRef)
                .contains("TST-DEL-SC-001")
                .doesNotContain("TST-BOM-SC-001");
    }

    @Test
    void existsByIdempotencyKey_trueWhenPresent() {
        Shipment s = TestFixtures.shipment("TST-DEL-IK-001", "DEL");
        s.setIdempotencyKey("test-idem-key-001");
        repo.save(s);

        assertThat(repo.existsByIdempotencyKey("test-idem-key-001")).isTrue();
        assertThat(repo.existsByIdempotencyKey("no-such-key")).isFalse();
    }
}
