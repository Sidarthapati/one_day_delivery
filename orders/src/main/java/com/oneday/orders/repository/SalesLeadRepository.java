package com.oneday.orders.repository;

import com.oneday.orders.domain.SalesLead;
import com.oneday.orders.domain.SalesLeadStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SalesLeadRepository extends JpaRepository<SalesLead, UUID> {

    List<SalesLead> findAllByOrderByCreatedAtDesc();

    List<SalesLead> findByStatusOrderByCreatedAtDesc(SalesLeadStatus status);
}
