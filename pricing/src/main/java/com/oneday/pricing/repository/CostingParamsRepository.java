package com.oneday.pricing.repository;

import com.oneday.pricing.domain.CostingParams;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface CostingParamsRepository extends JpaRepository<CostingParams, UUID> {

    Optional<CostingParams> findFirstByCityAndStatus(String city, String status);
}
