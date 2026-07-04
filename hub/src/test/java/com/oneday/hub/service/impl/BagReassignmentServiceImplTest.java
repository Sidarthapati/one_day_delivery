package com.oneday.hub.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oneday.common.kafka.enums.FlightReassignReason;
import com.oneday.hub.domain.*;
import com.oneday.hub.events.HubEventProducer;
import com.oneday.hub.repository.*;
import com.oneday.hub.service.BagReassignmentService;
import com.oneday.hub.service.FlightBagService;
import com.oneday.hub.service.exception.NothingToReassignException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BagReassignmentServiceImplTest {

    @Mock FlightBagService flightBagService;
    @Mock FlightBagRepository flightBagRepository;
    @Mock FlightBagItemRepository flightBagItemRepository;
    @Mock BagManifestRepository bagManifestRepository;
    @Mock StandRepository standRepository;
    @Mock HubEventProducer eventProducer;

    private final Clock clock = Clock.fixed(Instant.parse("2026-07-03T06:00:00Z"), ZoneOffset.UTC);
    private final UUID hubId = UUID.randomUUID();

    private BagReassignmentServiceImpl service() {
        return new BagReassignmentServiceImpl(flightBagService, flightBagRepository, flightBagItemRepository,
                bagManifestRepository, standRepository, eventProducer, new ObjectMapper(), clock);
    }

    private FlightBag bag(UUID id, String flightNo, int count, FlightBagStatus status) {
        return FlightBag.builder().id(id).cityId(hubId).hubId(hubId).flightNo(flightNo)
                .flightDate(LocalDate.of(2026, 7, 3)).originHub("BENGALURU").destHub("MUMBAI")
                .currentStandId(UUID.randomUUID()).status(status).parcelCount(count).weightGrams(count * 1000).build();
    }

    private FlightBagItem item(UUID bagId, UUID parcelId, String ref) {
        return FlightBagItem.builder().id(UUID.randomUUID()).bagId(bagId).parcelId(parcelId)
                .shipmentRef(ref).weightGrams(1000).status(FlightBagItemStatus.IN_BAG).build();
    }

    private void stubStandNo(UUID standId, String no) {
        when(standRepository.findById(standId)).thenReturn(Optional.of(
                Stand.builder().id(standId).cityId(hubId).hubId(hubId).standNo(no)
                        .capacity(200).status(StandStatus.OPEN).build()));
    }

    private void stubManifestSave() {
        when(bagManifestRepository.save(any())).thenAnswer(i -> {
            BagManifest m = i.getArgument(0);
            m.setId(UUID.randomUUID());
            return m;
        });
    }

    @Test
    void wholeFlightMove_cancellation_movesAllAndCancelsEmptiedSource() {
        UUID sourceId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        FlightBag source = bag(sourceId, "ODMUMBAI18", 2, FlightBagStatus.SEALED);
        FlightBag target = bag(targetId, "ODMUMBAI22", 0, FlightBagStatus.OPEN);
        UUID p1 = UUID.randomUUID(), p2 = UUID.randomUUID();
        FlightBagItem i1 = item(sourceId, p1, "BLR-1"), i2 = item(sourceId, p2, "BLR-2");

        // Cancellation: the parcels sit in an already-SEALED bag; whole-flight resolution finds it.
        when(flightBagRepository.findFirstByFlightNoAndDestHubAndStatusInOrderByCreatedAtDesc(
                eq("ODMUMBAI18"), eq("MUMBAI"), any())).thenReturn(Optional.of(source));
        when(flightBagItemRepository.findByBagIdAndStatus(sourceId, FlightBagItemStatus.IN_BAG))
                .thenReturn(List.of(i1, i2));
        when(flightBagRepository.findById(sourceId)).thenReturn(Optional.of(source));
        when(flightBagRepository.findById(targetId)).thenReturn(Optional.of(target));
        when(flightBagService.openBag(any())).thenReturn(target);
        when(flightBagItemRepository.findByBagIdAndStatus(targetId, FlightBagItemStatus.IN_BAG))
                .thenReturn(List.of(item(targetId, p1, "BLR-1"), item(targetId, p2, "BLR-2")));
        when(flightBagRepository.save(any())).thenAnswer(x -> x.getArgument(0));
        stubStandNo(target.getCurrentStandId(), "A-5");
        stubManifestSave();

        var cmd = new BagReassignmentService.FlightReassignmentCommand(
                "ODMUMBAI22", LocalDate.of(2026, 7, 3), "MUMBAI",
                Instant.parse("2026-07-03T17:00:00Z"), "ODMUMBAI18", null, FlightReassignReason.CANCELLATION);

        var result = service().reassign(cmd);

        assertThat(result.movedCount()).isEqualTo(2);
        assertThat(result.standNo()).isEqualTo("A-5");
        assertThat(source.getStatus()).isEqualTo(FlightBagStatus.CANCELLED);   // emptied → stand freed
        assertThat(source.getParcelCount()).isZero();
        verify(flightBagService, times(2)).addParcel(eq(targetId), any());
        verify(eventProducer).emitBagRescheduled(eq(target), eq("ODMUMBAI18"), eq("CANCELLATION"), eq(2), eq("A-5"), any());
    }

    @Test
    void subsetMove_optimisation_leavesSourceOpen() {
        UUID sourceId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        FlightBag source = bag(sourceId, "ODMUMBAI18", 3, FlightBagStatus.OPEN);
        FlightBag target = bag(targetId, "ODMUMBAI22", 0, FlightBagStatus.OPEN);
        UUID p1 = UUID.randomUUID();
        FlightBagItem i1 = item(sourceId, p1, "BLR-1");

        when(flightBagItemRepository.findFirstByParcelIdAndStatus(p1, FlightBagItemStatus.IN_BAG))
                .thenReturn(Optional.of(i1));
        when(flightBagRepository.findById(sourceId)).thenReturn(Optional.of(source));
        when(flightBagRepository.findById(targetId)).thenReturn(Optional.of(target));
        when(flightBagService.openBag(any())).thenReturn(target);
        when(flightBagItemRepository.findByBagIdAndStatus(targetId, FlightBagItemStatus.IN_BAG))
                .thenReturn(List.of(item(targetId, p1, "BLR-1")));
        when(flightBagRepository.save(any())).thenAnswer(x -> x.getArgument(0));
        stubStandNo(target.getCurrentStandId(), "A-7");
        stubManifestSave();

        var cmd = new BagReassignmentService.FlightReassignmentCommand(
                "ODMUMBAI22", LocalDate.of(2026, 7, 3), "MUMBAI",
                Instant.parse("2026-07-03T17:00:00Z"), null, List.of(p1), FlightReassignReason.OPTIMISATION);

        var result = service().reassign(cmd);

        assertThat(result.movedCount()).isEqualTo(1);
        assertThat(source.getStatus()).isEqualTo(FlightBagStatus.OPEN);   // still has 2 → not cancelled
        assertThat(source.getParcelCount()).isEqualTo(2);
        verify(flightBagService).addParcel(eq(targetId), eq("BLR-1"));
        // fromFlightNo derived from the source bag when the command omits it.
        verify(eventProducer).emitBagRescheduled(eq(target), eq("ODMUMBAI18"), eq("OPTIMISATION"), eq(1), eq("A-7"), any());
    }

    @Test
    void nothingResolved_throws() {
        when(flightBagItemRepository.findFirstByParcelIdAndStatus(any(), any())).thenReturn(Optional.empty());
        var cmd = new BagReassignmentService.FlightReassignmentCommand(
                "ODMUMBAI22", LocalDate.of(2026, 7, 3), "MUMBAI", null, null,
                List.of(UUID.randomUUID()), FlightReassignReason.OPTIMISATION);

        assertThatThrownBy(() -> service().reassign(cmd)).isInstanceOf(NothingToReassignException.class);
        verify(flightBagService, never()).openBag(any());
    }
}
