package com.oneday.shuttle.repository;

import com.oneday.shuttle.domain.ShuttleLiveStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ShuttleLiveStatusRepository extends JpaRepository<ShuttleLiveStatus, UUID> {
}
