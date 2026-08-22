package com.oneday.exceptions.repository;

import com.oneday.exceptions.domain.ExceptionAction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ExceptionActionRepository extends JpaRepository<ExceptionAction, UUID> {

    List<ExceptionAction> findByCaseIdOrderByCreatedAtAsc(UUID caseId);
}
