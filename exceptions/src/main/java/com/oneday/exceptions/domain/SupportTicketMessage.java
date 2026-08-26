package com.oneday.exceptions.domain;

import com.oneday.common.domain.MutableBaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

/**
 * One message in a {@link SupportTicket}'s conversation thread. Either side can post: the customer/
 * merchant who raised the ticket, or an ops agent working it. {@code fromAgent} distinguishes the two
 * so the UI can lay the thread out left/right; the author's M1 id and role are kept for the audit trail.
 */
@Entity
@Table(name = "support_ticket_message")
@Getter
@Setter
@NoArgsConstructor
public class SupportTicketMessage extends MutableBaseEntity {

    @Column(name = "ticket_id", nullable = false, updatable = false)
    private UUID ticketId;

    @Column(name = "author_user_id", nullable = false, updatable = false)
    private UUID authorUserId;

    @Column(name = "author_role", length = 40, updatable = false)
    private String authorRole;

    /** True when an ops agent wrote it; false when the ticket's raiser did. */
    @Column(name = "from_agent", nullable = false, updatable = false)
    private boolean fromAgent;

    @Column(name = "body", nullable = false)
    private String body;
}
