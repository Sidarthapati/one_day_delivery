package com.oneday.exceptions.repository;

import com.oneday.exceptions.domain.SupportTicket;
import com.oneday.exceptions.domain.TicketCategory;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SupportTicketRepository extends JpaRepository<SupportTicket, UUID> {

    /** A customer's own tickets, newest first. */
    List<SupportTicket> findByRaisedByUserIdOrderByCreatedAtDesc(UUID raisedByUserId);

    /** Reporter-scoped fetch so one customer can never read another's ticket by id. */
    Optional<SupportTicket> findByIdAndRaisedByUserId(UUID id, UUID raisedByUserId);

    /**
     * Pessimistic-write fetch (SELECT FOR UPDATE) — serialises the OPEN→IN_PROGRESS claim so two agents
     * replying at once can't both claim the same ticket. Must be called inside a {@code @Transactional}
     * method. Mirrors {@code orders.B2bAccountRepository.findByIdForUpdate}.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM SupportTicket t WHERE t.id = :id")
    Optional<SupportTicket> findByIdForUpdate(@Param("id") UUID id);

    /** The ops queue: live (unresolved) tickets, freshest first. */
    Page<SupportTicket> findByResolvedAtIsNullOrderByCreatedAtDesc(Pageable pageable);

    /** The ops queue filtered to one category (live tickets, freshest first). */
    Page<SupportTicket> findByResolvedAtIsNullAndCategoryOrderByCreatedAtDesc(TicketCategory category, Pageable pageable);
}
