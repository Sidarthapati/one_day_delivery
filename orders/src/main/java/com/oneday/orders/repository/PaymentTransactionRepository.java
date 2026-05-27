package com.oneday.orders.repository;

import com.oneday.orders.domain.PaymentTransaction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentTransactionRepository extends JpaRepository<PaymentTransaction, UUID> {

    List<PaymentTransaction> findByShipmentId(UUID shipmentId);

    Optional<PaymentTransaction> findByRazorpayOrderId(String razorpayOrderId);
}
