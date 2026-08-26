package com.oneday.exceptions.repository;

import com.oneday.exceptions.domain.SupportTicketMessage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SupportTicketMessageRepository extends JpaRepository<SupportTicketMessage, UUID> {

    /** The full thread for one ticket, oldest first (reading order). */
    List<SupportTicketMessage> findByTicketIdOrderByCreatedAtAsc(UUID ticketId);
}
