package com.oneday.orders.repository;

import com.oneday.common.domain.enums.CustomerType;
import com.oneday.common.domain.enums.ShipmentState;
import com.oneday.orders.domain.Shipment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

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
    void findByState_paginatedVariant_returnsCorrectPage() {
        // Save 3 BOOKED + 1 PICKED_UP
        repo.save(TestFixtures.shipment("TST-DEL-PG-001", "DEL"));
        repo.save(TestFixtures.shipment("TST-DEL-PG-002", "DEL"));
        repo.save(TestFixtures.shipment("TST-DEL-PG-003", "DEL"));
        Shipment pickedUp = TestFixtures.shipment("TST-DEL-PG-004", "DEL");
        pickedUp.setState(ShipmentState.PICKED_UP);
        repo.save(pickedUp);

        Page<Shipment> page = repo.findByState(
                ShipmentState.BOOKED,
                PageRequest.of(0, 2, Sort.by("shipmentRef")));

        assertThat(page.getTotalElements()).isGreaterThanOrEqualTo(3);
        assertThat(page.getSize()).isEqualTo(2);
        assertThat(page.getContent())
                .extracting(Shipment::getShipmentRef)
                .doesNotContain("TST-DEL-PG-004");
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
    void findByStateAndCityId_paginatedVariant_returnsCorrectSubset() {
        repo.save(TestFixtures.shipment("TST-DEL-SCP-001", "DEL"));
        repo.save(TestFixtures.shipment("TST-DEL-SCP-002", "DEL"));
        Shipment bom = TestFixtures.shipment("TST-BOM-SCP-001", "BOM");
        bom.setOriginCity("BOM");
        repo.save(bom);

        Page<Shipment> page = repo.findByStateAndCityId(
                ShipmentState.BOOKED, "DEL",
                PageRequest.of(0, 10, Sort.by("shipmentRef")));

        assertThat(page.getContent())
                .extracting(Shipment::getShipmentRef)
                .contains("TST-DEL-SCP-001", "TST-DEL-SCP-002")
                .doesNotContain("TST-BOM-SCP-001");
    }

    @Test
    void existsByIdempotencyKey_trueWhenPresent() {
        Shipment s = TestFixtures.shipment("TST-DEL-IK-001", "DEL");
        s.setIdempotencyKey("test-idem-key-001");
        repo.save(s);

        assertThat(repo.existsByIdempotencyKey("test-idem-key-001")).isTrue();
        assertThat(repo.existsByIdempotencyKey("no-such-key")).isFalse();
    }

    @Test
    void findByCustomerType_filtersByPgEnumColumn() {
        // Verifies that the read transformer on customer_type enables WHERE-clause filtering.
        // Without read = "customer_type::text", this would throw "operator does not exist".
        Shipment b2c = TestFixtures.shipment("TST-DEL-CT-B2C", "DEL"); // default is B2C
        Shipment b2b = TestFixtures.shipment("TST-DEL-CT-B2B", "DEL");
        b2b.setCustomerType(CustomerType.B2B);

        repo.save(b2c);
        repo.save(b2b);

        assertThat(repo.findByCustomerType(CustomerType.B2C))
                .extracting(Shipment::getShipmentRef)
                .contains("TST-DEL-CT-B2C")
                .doesNotContain("TST-DEL-CT-B2B");

        assertThat(repo.findByCustomerType(CustomerType.B2B))
                .extracting(Shipment::getShipmentRef)
                .contains("TST-DEL-CT-B2B")
                .doesNotContain("TST-DEL-CT-B2C");
    }

    @Test
    void findByAssignedFlightId_returnsShipmentsForFlight() {
        // Fix 6: Verifies M9 can query all shipments assigned to a specific flight.
        UUID flightId = UUID.randomUUID();
        UUID otherFlightId = UUID.randomUUID();

        Shipment s1 = TestFixtures.shipment("TST-DEL-FL-001", "DEL");
        s1.setAssignedFlightId(flightId);
        Shipment s2 = TestFixtures.shipment("TST-DEL-FL-002", "DEL");
        s2.setAssignedFlightId(flightId);
        Shipment s3 = TestFixtures.shipment("TST-DEL-FL-003", "DEL");
        s3.setAssignedFlightId(otherFlightId);
        Shipment s4 = TestFixtures.shipment("TST-DEL-FL-004", "DEL"); // no flight assigned

        repo.save(s1);
        repo.save(s2);
        repo.save(s3);
        repo.save(s4);

        List<Shipment> results = repo.findByAssignedFlightId(flightId);

        assertThat(results)
                .extracting(Shipment::getShipmentRef)
                .contains("TST-DEL-FL-001", "TST-DEL-FL-002")
                .doesNotContain("TST-DEL-FL-003", "TST-DEL-FL-004");
    }
}
