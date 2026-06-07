package com.oneday.routing.repository;

import com.oneday.routing.domain.CityLogisticsNode;
import com.oneday.routing.domain.LogisticsNodeKind;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CityLogisticsNodeRepository extends JpaRepository<CityLogisticsNode, UUID> {

    List<CityLogisticsNode> findByCityId(UUID cityId);

    Optional<CityLogisticsNode> findByCityIdAndKind(UUID cityId, LogisticsNodeKind kind);
}
