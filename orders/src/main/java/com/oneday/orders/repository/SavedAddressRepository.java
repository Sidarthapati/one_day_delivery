package com.oneday.orders.repository;

import com.oneday.orders.domain.SavedAddress;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SavedAddressRepository extends JpaRepository<SavedAddress, UUID> {

    List<SavedAddress> findByUserIdOrderByCreatedAtDesc(UUID userId);

    /** User-scoped fetch so one user can never read/edit another's address by id. */
    Optional<SavedAddress> findByIdAndUserId(UUID id, UUID userId);
}
