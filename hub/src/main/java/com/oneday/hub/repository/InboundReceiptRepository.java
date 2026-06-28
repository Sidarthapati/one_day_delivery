package com.oneday.hub.repository;

import com.oneday.hub.domain.InboundReceipt;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface InboundReceiptRepository extends JpaRepository<InboundReceipt, UUID> {

    List<InboundReceipt> findByParcelId(UUID parcelId);
}
