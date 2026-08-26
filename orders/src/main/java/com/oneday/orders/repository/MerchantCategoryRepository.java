package com.oneday.orders.repository;

import com.oneday.orders.domain.MerchantCategory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MerchantCategoryRepository extends JpaRepository<MerchantCategory, UUID> {

    List<MerchantCategory> findByB2bAccountIdOrderByName(UUID b2bAccountId);

    /** Account-scoped fetch so one merchant can never read/edit/tag-with another's category by id. */
    Optional<MerchantCategory> findByIdAndB2bAccountId(UUID id, UUID b2bAccountId);

    boolean existsByB2bAccountIdAndNameIgnoreCase(UUID b2bAccountId, String name);
}
