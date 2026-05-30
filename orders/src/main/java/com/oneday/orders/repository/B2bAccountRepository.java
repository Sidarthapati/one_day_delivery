package com.oneday.orders.repository;

import com.oneday.orders.domain.B2bAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface B2bAccountRepository extends JpaRepository<B2bAccount, UUID> {

    List<B2bAccount> findByCityId(String cityId);

    List<B2bAccount> findByIsActive(boolean isActive);

    Optional<B2bAccount> findByBillingEmail(String billingEmail);
}
