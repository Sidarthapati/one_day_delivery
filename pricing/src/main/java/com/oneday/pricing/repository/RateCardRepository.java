package com.oneday.pricing.repository;

import com.oneday.common.domain.enums.CustomerType;
import com.oneday.pricing.domain.RateCard;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RateCardRepository extends JpaRepository<RateCard, UUID> {

    /** The single ACTIVE published card for a customer type (B2C / C2C). */
    Optional<RateCard> findFirstByCustomerTypeAndStatus(CustomerType customerType, String status);

    List<RateCard> findByCustomerTypeOrderByEffectiveFromDesc(CustomerType customerType);
}
