package com.oneday.exceptions.repository;

import com.oneday.exceptions.domain.SupportTicket;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SupportTicketRepository extends JpaRepository<SupportTicket, UUID> {

    /** A customer's own tickets, newest first. */
    List<SupportTicket> findByRaisedByUserIdOrderByCreatedAtDesc(UUID raisedByUserId);

    /** Reporter-scoped fetch so one customer can never read another's ticket by id. */
    Optional<SupportTicket> findByIdAndRaisedByUserId(UUID id, UUID raisedByUserId);

    /** The ops queue: live (unresolved) tickets, freshest first. */
    Page<SupportTicket> findByResolvedAtIsNullOrderByCreatedAtDesc(Pageable pageable);
}
