package com.oneday.auth.repository;

import com.oneday.auth.domain.OnboardingRequest;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface OnboardingRequestRepository extends JpaRepository<OnboardingRequest, UUID> {
    boolean existsByEmail(String email);
    List<OnboardingRequest> findAllByOrderByCreatedAtDesc();
}
