package com.oneday.auth.repository;

import com.oneday.auth.domain.ApiKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ApiKeyRepository extends JpaRepository<ApiKey, UUID> {

    @Query("SELECT k FROM ApiKey k JOIN FETCH k.user u JOIN FETCH u.role WHERE k.keyHash = :keyHash AND k.active = true")
    Optional<ApiKey> findActiveByKeyHashWithUser(@Param("keyHash") String keyHash);

    long countByUserIdAndActiveTrue(UUID userId);

    List<ApiKey> findAllByUserId(UUID userId);
}
