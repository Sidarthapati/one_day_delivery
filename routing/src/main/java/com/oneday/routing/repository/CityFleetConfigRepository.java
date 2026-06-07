package com.oneday.routing.repository;

import com.oneday.routing.domain.CityFleetConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface CityFleetConfigRepository extends JpaRepository<CityFleetConfig, UUID> {

    Optional<CityFleetConfig> findByCityId(UUID cityId);
}
