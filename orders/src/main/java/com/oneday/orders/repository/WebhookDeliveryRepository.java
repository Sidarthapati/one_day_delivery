package com.oneday.orders.repository;

import com.oneday.orders.domain.WebhookDelivery;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface WebhookDeliveryRepository extends JpaRepository<WebhookDelivery, UUID> {

    List<WebhookDelivery> findByB2bAccountIdOrderByCreatedAtDesc(UUID b2bAccountId, Pageable pageable);
}
