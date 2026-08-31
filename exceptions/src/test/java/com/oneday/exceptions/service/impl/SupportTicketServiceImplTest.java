package com.oneday.exceptions.service.impl;

import com.oneday.exceptions.domain.SupportTicket;
import com.oneday.exceptions.domain.TicketCategory;
import com.oneday.exceptions.domain.TicketChannel;
import com.oneday.exceptions.domain.TicketStatus;
import com.oneday.exceptions.dto.SupportTicketRequest;
import com.oneday.exceptions.dto.SupportTicketResponse;
import com.oneday.exceptions.domain.SupportTicketMessage;
import com.oneday.exceptions.repository.SupportTicketMessageRepository;
import com.oneday.exceptions.repository.SupportTicketRepository;
import com.oneday.orders.dto.ShipmentInfo;
import com.oneday.orders.service.ShipmentLookupService;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Intake validation and ticket lifecycle are the load-bearing bits: a callback must carry a phone, a
 * ticket must carry a body, a named shipment must exist, a customer can't read another's ticket, and a
 * closed ticket can't be re-actioned. These pin those.
 */
class SupportTicketServiceImplTest {

    private final SupportTicketRepository repo = mock(SupportTicketRepository.class);
    private final SupportTicketMessageRepository messages = mock(SupportTicketMessageRepository.class);
    private final ShipmentLookupService shipmentLookup = mock(ShipmentLookupService.class);
    private final com.oneday.exceptions.integration.JiraPort jira =
            mock(com.oneday.exceptions.integration.JiraPort.class);
    private final SupportTicketServiceImpl service =
            new SupportTicketServiceImpl(repo, messages, shipmentLookup, jira);

    private static final UUID USER = UUID.randomUUID();

    @Test
    void callbackWithoutPhoneIsRejected() {
        var req = new SupportTicketRequest(TicketChannel.CALLBACK, null, null, "call me", null, null);
        assertThatThrownBy(() -> service.create(USER, "B2B_USER", req))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("contactPhone");
        verify(repo, never()).save(any());
    }

    @Test
    void ticketWithoutBodyIsRejected() {
        var req = new SupportTicketRequest(TicketChannel.TICKET, null, null, "subject only", "  ", null);
        assertThatThrownBy(() -> service.create(USER, "B2B_USER", req))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("body");
        verify(repo, never()).save(any());
    }

    @Test
    void unknownShipmentRefIsRejected() {
        when(shipmentLookup.findByRef("1DD-NOPE")).thenReturn(Optional.empty());
        var req = new SupportTicketRequest(TicketChannel.TICKET, null, "1DD-NOPE", null, "where is it?", null);
        assertThatThrownBy(() -> service.create(USER, "B2B_USER", req))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Unknown shipment");
        verify(repo, never()).save(any());
    }

    @Test
    void validTicketIsPersistedOpenWithRaiser() {
        when(repo.save(any(SupportTicket.class))).thenAnswer(i -> i.getArgument(0));
        var req = new SupportTicketRequest(
                TicketChannel.TICKET, TicketCategory.DAMAGE, null, "Damaged box", "Arrived crushed", null);

        SupportTicketResponse resp = service.create(USER, "B2B_USER", req);

        assertThat(resp.status()).isEqualTo(TicketStatus.OPEN);
        assertThat(resp.body()).isEqualTo("Arrived crushed");
        assertThat(resp.category()).isEqualTo(TicketCategory.DAMAGE);
        var saved = org.mockito.ArgumentCaptor.forClass(SupportTicket.class);
        verify(repo).save(saved.capture());
        assertThat(saved.getValue().getRaisedByUserId()).isEqualTo(USER);
        assertThat(saved.getValue().getRaisedByRole()).isEqualTo("B2B_USER");
        assertThat(saved.getValue().getCategory()).isEqualTo(TicketCategory.DAMAGE);
    }

    @Test
    void myDetailIsScopedToTheCaller() {
        UUID id = UUID.randomUUID();
        when(repo.findByIdAndRaisedByUserId(id, USER)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.myDetail(USER, id)).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void resolvingStampsResolvedAtAndAgent() {
        UUID id = UUID.randomUUID();
        SupportTicket t = new SupportTicket();
        t.setStatus(TicketStatus.OPEN);
        when(repo.findById(id)).thenReturn(Optional.of(t));
        when(repo.save(any(SupportTicket.class))).thenAnswer(i -> i.getArgument(0));

        SupportTicketResponse resp = service.act(id, "agent-1", TicketStatus.RESOLVED, "called back, sorted");

        assertThat(resp.status()).isEqualTo(TicketStatus.RESOLVED);
        assertThat(resp.assignedTo()).isEqualTo("agent-1");
        assertThat(resp.resolvedAt()).isNotNull();
        assertThat(resp.resolutionNote()).isEqualTo("called back, sorted");
    }

    @Test
    void movingATicketBackToOpenIsRejected() {
        UUID id = UUID.randomUUID();
        SupportTicket t = new SupportTicket();
        t.setStatus(TicketStatus.IN_PROGRESS);
        when(repo.findById(id)).thenReturn(Optional.of(t));
        assertThatThrownBy(() -> service.act(id, "agent-1", TicketStatus.OPEN, null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("OPEN is not a valid action");
    }

    @Test
    void actingOnAClosedTicketConflicts() {
        UUID id = UUID.randomUUID();
        SupportTicket t = new SupportTicket();
        t.setStatus(TicketStatus.RESOLVED);
        when(repo.findById(id)).thenReturn(Optional.of(t));
        assertThatThrownBy(() -> service.act(id, "agent-1", TicketStatus.IN_PROGRESS, null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("already");
    }

    // ── Conversation thread ────────────────────────────────────────────────

    @Test
    void customerReplyIsScopedToTheirOwnTicket() {
        UUID id = UUID.randomUUID();
        when(repo.findByIdAndRaisedByUserId(id, USER)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.postMineMessage(USER, id, "any update?"))
                .isInstanceOf(ResponseStatusException.class);
        verify(messages, never()).save(any());
    }

    @Test
    void customerReplyReopensAResolvedTicket() {
        UUID id = UUID.randomUUID();
        SupportTicket t = new SupportTicket();
        t.setRaisedByRole("B2B_USER");
        t.setStatus(TicketStatus.RESOLVED);
        when(repo.findByIdAndRaisedByUserId(id, USER)).thenReturn(Optional.of(t));
        when(messages.findByTicketIdOrderByCreatedAtAsc(any())).thenReturn(java.util.List.of());

        SupportTicketResponse resp = service.postMineMessage(USER, id, "still not delivered");

        assertThat(resp.status()).isEqualTo(TicketStatus.OPEN); // reopened
        assertThat(t.getResolvedAt()).isNull();
        org.mockito.ArgumentCaptor<SupportTicketMessage> msg = org.mockito.ArgumentCaptor.forClass(SupportTicketMessage.class);
        verify(messages).save(msg.capture());
        assertThat(msg.getValue().isFromAgent()).isFalse();
        assertThat(msg.getValue().getBody()).isEqualTo("still not delivered");
    }

    @Test
    void agentReplyClaimsAnOpenTicket() {
        UUID id = UUID.randomUUID();
        SupportTicket t = new SupportTicket();
        t.setStatus(TicketStatus.OPEN);
        when(repo.findByIdForUpdate(id)).thenReturn(Optional.of(t));
        when(messages.findByTicketIdOrderByCreatedAtAsc(any())).thenReturn(java.util.List.of());

        UUID agent = UUID.randomUUID();
        SupportTicketResponse resp = service.postAgentMessage(agent, "CALL_CENTER_AGENT", id, "on it now");

        assertThat(resp.status()).isEqualTo(TicketStatus.IN_PROGRESS); // claimed
        assertThat(resp.assignedTo()).isEqualTo(agent.toString());
        org.mockito.ArgumentCaptor<SupportTicketMessage> msg = org.mockito.ArgumentCaptor.forClass(SupportTicketMessage.class);
        verify(messages).save(msg.capture());
        assertThat(msg.getValue().isFromAgent()).isTrue();
    }

    @Test
    void blankMessageBodyIsRejected() {
        UUID id = UUID.randomUUID();
        SupportTicket t = new SupportTicket();
        t.setStatus(TicketStatus.IN_PROGRESS);
        when(repo.findByIdAndRaisedByUserId(id, USER)).thenReturn(Optional.of(t));
        assertThatThrownBy(() -> service.postMineMessage(USER, id, "   "))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("body is required");
        verify(messages, never()).save(any());
    }
}
