package com.oneday.orders.repository;

import com.oneday.orders.domain.PaymentTransaction;
import com.oneday.orders.domain.Shipment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PaymentTransactionRepository")
class PaymentTransactionRepositoryTest extends AbstractRepositoryTest {

    @Autowired
    private PaymentTransactionRepository repo;

    @Autowired
    private ShipmentRepository shipmentRepository;

    @Test
    void saveAndFindById_roundTrip() {
        Shipment shipment = shipmentRepository.save(TestFixtures.shipment("TST-PT-RND", "DEL"));
        PaymentTransaction saved = repo.save(
                TestFixtures.paymentTransaction(shipment.getId(), "order_tst_001"));

        assertThat(repo.findById(saved.getId())).isPresent();
        assertThat(saved.getRazorpayOrderId()).isEqualTo("order_tst_001");
        assertThat(saved.getTotalPaise()).isEqualTo(11800L);
    }

    @Test
    void findByShipmentId_returnsTransactionsForThatShipment() {
        Shipment shipA = shipmentRepository.save(TestFixtures.shipment("TST-PT-A", "DEL"));
        Shipment shipB = shipmentRepository.save(TestFixtures.shipment("TST-PT-B", "DEL"));

        repo.save(TestFixtures.paymentTransaction(shipA.getId(), "order_tst_A1"));
        repo.save(TestFixtures.paymentTransaction(shipA.getId(), "order_tst_A2"));
        repo.save(TestFixtures.paymentTransaction(shipB.getId(), "order_tst_B1"));

        List<PaymentTransaction> results = repo.findByShipmentId(shipA.getId());

        assertThat(results).hasSize(2);
        assertThat(results).extracting(PaymentTransaction::getRazorpayOrderId)
                .containsExactlyInAnyOrder("order_tst_A1", "order_tst_A2");
    }

    @Test
    void findByRazorpayOrderId_returnsMatchingTransaction() {
        Shipment shipment = shipmentRepository.save(TestFixtures.shipment("TST-PT-RZP", "DEL"));
        repo.save(TestFixtures.paymentTransaction(shipment.getId(), "order_rzp_unique"));

        Optional<PaymentTransaction> found = repo.findByRazorpayOrderId("order_rzp_unique");

        assertThat(found).isPresent();
        assertThat(found.get().getCurrency()).isEqualTo("INR");
    }

    @Test
    void findByRazorpayOrderId_emptyForUnknownId() {
        assertThat(repo.findByRazorpayOrderId("order_does_not_exist")).isEmpty();
    }
}
