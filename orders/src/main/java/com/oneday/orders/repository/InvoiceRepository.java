package com.oneday.orders.repository;

import com.oneday.orders.domain.Invoice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InvoiceRepository extends JpaRepository<Invoice, UUID> {

    Optional<Invoice> findByShipmentId(UUID shipmentId);

    Optional<Invoice> findByShipmentRef(String shipmentRef);

    List<Invoice> findByBuyerUserIdOrderByIssuedAtDesc(UUID buyerUserId);

    /** Next value of the invoice serial sequence (defined in V4_24). */
    @Query(value = "SELECT nextval('invoice_seq')", nativeQuery = true)
    long nextInvoiceSequence();
}
