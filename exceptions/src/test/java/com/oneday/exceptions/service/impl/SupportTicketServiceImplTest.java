package com.oneday.exceptions.service.impl;

import com.oneday.exceptions.domain.SupportTicket;
import com.oneday.exceptions.domain.TicketChannel;
import com.oneday.exceptions.domain.TicketStatus;
import com.oneday.exceptions.dto.SupportTicketRequest;
import com.oneday.exceptions.dto.SupportTicketResponse;
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
    private final ShipmentLookupService shipmentLookup = mock(ShipmentLookupService.class);
    private final SupportTicketServiceImpl service = new SupportTicketServiceImpl(repo, shipmentLookup);

    private static final UUID USER = UUID.randomUUID();

    @Test
    void callbackWithoutPhoneIsRejected() {
        var req = new SupportTicketRequest(TicketChannel.CALLBACK, null, "call me", null, null);
        assertThatThrownBy(() -> service.create(USER, "B2B_USER", req))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("contactPhone");
        verify(repo, never()).save(any());
    }

    @Test
    void ticketWithoutBodyIsRejected() {
        var req = new SupportTicketRequest(TicketChannel.TICKET, null, "subject only", "  ", null);
        assertThatThrownBy(() -> service.create(USER, "B2B_USER", req))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("body");
        verify(repo, never()).save(any());
    }

    @Test
    void unknownShipmentRefIsRejected() {
        when(shipmentLookup.findByRef("1DD-NOPE")).thenReturn(Optional.empty());
        var req = new SupportTicketRequest(TicketChannel.TICKET, "1DD-NOPE", null, "where is it?", null);
        assertThatThrownBy(() -> service.create(USER, "B2B_USER", req))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Unknown shipment");
        verify(repo, never()).save(any());
    }

    @Test
    void validTicketIsPersistedOpenWithRaiser() {
        when(repo.save(any(SupportTicket.class))).thenAnswer(i -> i.getArgument(0));
        var req = new SupportTicketRequest(TicketChannel.TICKET, null, "Damaged box", "Arrived crushed", null);

        SupportTicketResponse resp = service.create(USER, "B2B_USER", req);

        assertThat(resp.status()).isEqualTo(TicketStatus.OPEN);
        assertThat(resp.body()).isEqualTo("Arrived crushed");
        var saved = org.mockito.ArgumentCaptor.forClass(SupportTicket.class);
        verify(repo).save(saved.capture());
        assertThat(saved.getValue().getRaisedByUserId()).isEqualTo(USER);
        assertThat(saved.getValue().getRaisedByRole()).isEqualTo("B2B_USER");
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
}
