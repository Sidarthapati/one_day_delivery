package com.oneday.routing.repository;

import com.oneday.routing.domain.VanLiveStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface VanLiveStatusRepository extends JpaRepository<VanLiveStatus, UUID> {

    List<VanLiveStatus> findByCityId(UUID cityId);
}
