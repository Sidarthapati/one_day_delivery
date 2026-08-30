package com.oneday.orders.repository;

import com.oneday.orders.domain.MerchantCategory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MerchantCategoryRepository extends JpaRepository<MerchantCategory, UUID> {

    /** All of a merchant's categories incl. archived — for resolving names on already-tagged shipments
     *  (analytics). Never use for the pick list, which must show live categories only. */
    List<MerchantCategory> findByB2bAccountIdOrderByName(UUID b2bAccountId);

    /** The merchant's live (non-archived) categories — the pick list / console list. */
    List<MerchantCategory> findByB2bAccountIdAndArchivedAtIsNullOrderByName(UUID b2bAccountId);

    /** Account-scoped live fetch so one merchant can never read/edit/tag-with another's (or an archived)
     *  category by id. Rename/delete/booking-tag all go through this. */
    Optional<MerchantCategory> findByIdAndB2bAccountIdAndArchivedAtIsNull(UUID id, UUID b2bAccountId);

    /** Uniqueness is enforced only among live categories (an archived name can be reused). */
    boolean existsByB2bAccountIdAndNameIgnoreCaseAndArchivedAtIsNull(UUID b2bAccountId, String name);
}
