package com.oneday.grid.repository;

import com.oneday.grid.domain.Grid;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface GridRepository extends JpaRepository<Grid, UUID> {

    Optional<Grid> findByCityId(UUID cityId);
}
