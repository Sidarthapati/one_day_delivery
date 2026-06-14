package com.oneday.pricing.repository;

import com.oneday.pricing.domain.CityPairRate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CityPairRateRepository extends JpaRepository<CityPairRate, UUID> {

    Optional<CityPairRate> findByRateCardIdAndOriginCityAndDestCity(
            UUID rateCardId, String originCity, String destCity);

    List<CityPairRate> findByRateCardIdOrderByOriginCityAscDestCityAsc(UUID rateCardId);
}
