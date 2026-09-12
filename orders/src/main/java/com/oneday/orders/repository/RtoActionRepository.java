package com.oneday.orders.repository;

import com.oneday.orders.domain.RtoAction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RtoActionRepository extends JpaRepository<RtoAction, UUID> {

    /** Open worklist for a hub's city, newest first. */
    List<RtoAction> findByReturnHubCityAndStatusOrderByCreatedAtDesc(String returnHubCity, String status);

    /** All open items (ADMIN, all cities). */
    List<RtoAction> findByStatusOrderByCreatedAtDesc(String status);

    /** The item for a given return child (used to auto-close when the child is sorted). */
    Optional<RtoAction> findByChildRef(String childRef);
}
