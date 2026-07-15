package com.oneday.airline.repository;

import com.oneday.airline.domain.LaneRateCard;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface LaneRateCardRepository extends JpaRepository<LaneRateCard, UUID> {

    Optional<LaneRateCard> findByOriginHubAndDestHubAndStatus(String originHub, String destHub, String status);
}
