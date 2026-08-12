package com.oneday.shuttle.service;

import com.oneday.airline.service.ShuttleInboundQueryService;
import com.oneday.airline.service.ShuttleInboundQueryService.InboundAwb;
import com.oneday.hub.domain.FlightBag;
import com.oneday.hub.domain.FlightBagStatus;
import com.oneday.hub.service.FlightBagService;
import com.oneday.shuttle.config.ShuttleProperties;
import com.oneday.shuttle.domain.ShuttleDirection;
import com.oneday.shuttle.dto.ShuttleQueueResponse;
import com.oneday.shuttle.repository.ShuttleLegRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ShuttleQueueServiceTest {

    @Mock FlightBagService flightBagService;
    @Mock ShuttleInboundQueryService inboundQuery;
    @Mock ShuttleLegRepository legRepository;

    private static final Instant NOW = Instant.parse("2026-08-13T10:00:00Z");
    private static final LocalDate DATE = LocalDate.of(2026, 8, 13);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private ShuttleQueueService service() {
        return new ShuttleQueueService(flightBagService, inboundQuery, legRepository, new ShuttleProperties(), clock);
    }

    @Test
    void outbound_showsOpenAndSealed_excludesDispatched_flagsOverdue_sortedByLeaveBy() {
        // SEALED, cutoff +120m → leaveBy = +120 − 75 = +45m (future, not overdue).
        FlightBag sealed = bag(FlightBagStatus.SEALED, NOW.plus(120, ChronoUnit.MINUTES));
        // OPEN, cutoff +60m → leaveBy = −15m (past → OVERDUE).
        FlightBag open = bag(FlightBagStatus.OPEN, NOW.plus(60, ChronoUnit.MINUTES));
        FlightBag dispatched = bag(FlightBagStatus.DISPATCHED, NOW.plus(200, ChronoUnit.MINUTES));
        when(flightBagService.bagsForOriginHub("HYD", DATE)).thenReturn(List.of(sealed, open, dispatched));
        when(inboundQuery.landedInboundAwbs("HYD", DATE)).thenReturn(List.of());

        List<ShuttleQueueResponse.OutboundBag> out = service().queue("HYD", DATE).outbound();

        assertThat(out).hasSize(2);   // DISPATCHED excluded
        assertThat(out.get(0).status()).isEqualTo("OVERDUE");   // earliest leaveBy first
        assertThat(out.get(1).status()).isEqualTo("SEALED");
    }

    @Test
    void inbound_dropsAwbsAlreadyCollected() {
        UUID collected = UUID.randomUUID();
        UUID pending = UUID.randomUUID();
        when(flightBagService.bagsForOriginHub(any(), any())).thenReturn(List.of());
        when(inboundQuery.landedInboundAwbs("HYD", DATE)).thenReturn(List.of(
                new InboundAwb(collected, "6E6025", DATE, "098-1", 3, NOW),
                new InboundAwb(pending, "AI806", DATE, "098-2", 2, NOW)));
        when(legRepository.existsByAwbIdAndDirection(collected, ShuttleDirection.INBOUND)).thenReturn(true);
        when(legRepository.existsByAwbIdAndDirection(eq(pending), any())).thenReturn(false);

        List<ShuttleQueueResponse.InboundFlight> in = service().queue("HYD", DATE).inbound();

        assertThat(in).singleElement().satisfies(f -> assertThat(f.awbId()).isEqualTo(pending));
    }

    private static FlightBag bag(FlightBagStatus status, Instant cutoff) {
        return FlightBag.builder()
                .id(UUID.randomUUID()).flightNo("6E6025").flightDate(DATE).destHub("DEL")
                .status(status).parcelCount(2).weightGrams(2000).bagCutoff(cutoff).build();
    }
}
