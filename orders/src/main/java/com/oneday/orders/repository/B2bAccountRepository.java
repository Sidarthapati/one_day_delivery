package com.oneday.orders.repository;

import com.oneday.orders.domain.B2bAccount;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface B2bAccountRepository extends JpaRepository<B2bAccount, UUID> {

    List<B2bAccount> findByCityId(String cityId);

    List<B2bAccount> findByIsActive(boolean isActive);

    Optional<B2bAccount> findByBillingEmail(String billingEmail);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM B2bAccount a WHERE a.id = :id")
    Optional<B2bAccount> findByIdForUpdate(@Param("id") UUID id);
}
