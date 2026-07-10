package com.oneday.routing.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oneday.routing.domain.DaCronSchedule;
import com.oneday.routing.domain.RouteOverrideAudit;
import com.oneday.routing.domain.RoutePlan;
import com.oneday.routing.domain.RoutePlanSource;
import com.oneday.routing.domain.RoutePlanStatus;
import com.oneday.routing.domain.RoutePlanStop;
import com.oneday.routing.domain.RoutingSolverType;
import com.oneday.routing.domain.StopNodeKind;
import com.oneday.routing.dto.OverrideRequest;
import com.oneday.routing.dto.StopReassignment;
import com.oneday.routing.events.CronEventProducer;
import com.oneday.routing.repository.DaCronScheduleRepository;
import com.oneday.routing.repository.RouteOverrideAuditRepository;
import com.oneday.routing.repository.RoutePlanRepository;
import com.oneday.routing.repository.RoutePlanStopRepository;
import com.oneday.routing.service.GridDataAdapter;
import com.oneday.routing.service.RoutePlanningService;
import com.oneday.routing.service.ShuttleScheduleService;
import com.oneday.routing.service.VanManifestService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RoutePlanLifecycleServiceImplTest {

    @Mock RoutePlanRepository routePlanRepository;
    @Mock RoutePlanStopRepository routePlanStopRepository;
    @Mock DaCronScheduleRepository daCronScheduleRepository;
    @Mock RouteOverrideAuditRepository auditRepository;
    @Mock RoutePlanningService routePlanningService;
    @Mock ShuttleScheduleService shuttleScheduleService;
    @Mock CronEventProducer cronEventProducer;
    @Mock GridDataAdapter gridDataAdapter;
    @Mock VanManifestService vanManifestService;

    final Clock clock = Clock.fixed(Instant.parse("2026-06-09T03:30:00Z"), ZoneId.of("Asia/Kolkata"));
    RoutePlanLifecycleServiceImpl service;

    final UUID cityId = UUID.randomUUID();
    final LocalDate date = LocalDate.of(2026, 6, 10);

    @BeforeEach
    void setUp() {
        service = new RoutePlanLifecycleServiceImpl(routePlanRepository, routePlanStopRepository,
                daCronScheduleRepository, auditRepository, routePlanningService, shuttleScheduleService,
                cronEventProducer, gridDataAdapter, vanManifestService, new ObjectMapper(), clock);
    }

    private RoutePlan plan(UUID id, RoutePlanStatus status, RoutePlanSource source, int revision) {
        return RoutePlan.builder().id(id).cityId(cityId).validForDate(date).status(status)
                .source(source).solverType(RoutingSolverType.OR_TOOLS).revision(revision)
                .vansUsed(2).nLoops(4).build();
    }

    private DaCronSchedule cron(UUID planId, UUID vertexId) {
        return DaCronSchedule.builder().routePlanId(planId).daId(UUID.randomUUID()).hexVertexId(vertexId)
                .vanId(UUID.randomUUID()).meetingTimes("[\"07:30\",\"09:30\"]").cityId(cityId).validDate(date).build();
    }

    @Test
    void approve_flipsToApprovedAndEmitsCronAndPublishEvents() {
        UUID planId = UUID.randomUUID();
        RoutePlan proposed = plan(planId, RoutePlanStatus.PROPOSED, RoutePlanSource.NIGHTLY, 1);
        when(routePlanRepository.findById(planId)).thenReturn(Optional.of(proposed));
        when(routePlanRepository.findByCityIdAndValidForDate(cityId, date)).thenReturn(List.of(proposed));
        DaCronSchedule cron = cron(planId, UUID.randomUUID());
        when(daCronScheduleRepository.findByRoutePlanId(planId)).thenReturn(List.of(cron));
        when(gridDataAdapter.getDaTerritories(cityId, date)).thenReturn(List.of());

        UUID actor = UUID.randomUUID();
        RoutePlan result = service.approve(planId, actor);

        assertThat(result.getStatus()).isEqualTo(RoutePlanStatus.APPROVED);
        assertThat(result.getApprovedBy()).isEqualTo(actor);
        assertThat(result.getApprovedAt()).isNotNull();
        verify(cronEventProducer).emitDaCronScheduled(eq(cron), anyDouble(), anyDouble());
        verify(cronEventProducer).emitRoutePlanPublished(result);
        verify(shuttleScheduleService).publish(cityId, date, planId);
        verify(auditRepository).save(any(RouteOverrideAudit.class));
    }

    @Test
    void approve_nonProposed_conflict() {
        UUID planId = UUID.randomUUID();
        when(routePlanRepository.findById(planId))
                .thenReturn(Optional.of(plan(planId, RoutePlanStatus.APPROVED, RoutePlanSource.NIGHTLY, 1)));

        assertThatThrownBy(() -> service.approve(planId, UUID.randomUUID()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("409");
    }

    @Test
    void override_clonesNewRevisionWritesAuditAndEmitsRouteChanged() {
        UUID planId = UUID.randomUUID();
        RoutePlan source = plan(planId, RoutePlanStatus.APPROVED, RoutePlanSource.NIGHTLY, 1);
        when(routePlanRepository.findById(planId)).thenReturn(Optional.of(source));

        UUID oldVertex = UUID.randomUUID();
        UUID newVertex = UUID.randomUUID();
        RoutePlanStop stop = RoutePlanStop.builder().id(UUID.randomUUID()).routePlanId(planId)
                .vanId(UUID.randomUUID()).loopIndex(0).stopSeq(1).nodeKind(StopNodeKind.MEETING_VERTEX)
                .hexVertexId(oldVertex).deliverQty(3).collectQty(1).loadAfter(3).build();
        when(routePlanStopRepository.findByRoutePlanId(planId)).thenReturn(List.of(stop));
        when(daCronScheduleRepository.findByRoutePlanId(planId)).thenReturn(List.of(cron(planId, oldVertex)));
        when(gridDataAdapter.getDaTerritories(cityId, date)).thenReturn(List.of());

        UUID actor = UUID.randomUUID();
        OverrideRequest req = new OverrideRequest(actor, "vertex blocked",
                List.of(new StopReassignment(stop.getId(), newVertex)));

        RoutePlan revision = service.override(planId, req);

        assertThat(revision.getSource()).isEqualTo(RoutePlanSource.MANUAL_OVERRIDE);
        assertThat(revision.getRevision()).isEqualTo(2);
        assertThat(revision.getSupersedesPlanId()).isEqualTo(planId);
        assertThat(revision.getStatus()).isEqualTo(RoutePlanStatus.APPROVED);
        assertThat(source.getStatus()).isEqualTo(RoutePlanStatus.SUPERSEDED);

        // cloned stop carries the reassigned vertex
        ArgumentCaptor<List<RoutePlanStop>> stopsCaptor = ArgumentCaptor.forClass(List.class);
        verify(routePlanStopRepository).saveAll(stopsCaptor.capture());
        assertThat(stopsCaptor.getValue()).singleElement()
                .satisfies(s -> assertThat(s.getHexVertexId()).isEqualTo(newVertex));

        // cloned cron whose vertex matched the reassigned stop follows the new vertex
        ArgumentCaptor<List<DaCronSchedule>> cronCaptor = ArgumentCaptor.forClass(List.class);
        verify(daCronScheduleRepository).saveAll(cronCaptor.capture());
        assertThat(cronCaptor.getValue()).singleElement()
                .satisfies(c -> assertThat(c.getHexVertexId()).isEqualTo(newVertex));

        verify(auditRepository).save(any(RouteOverrideAudit.class));
        verify(cronEventProducer).emitRouteChanged(revision, actor, "vertex blocked");
    }

    @Test
    void override_nonApproved_conflict() {
        UUID planId = UUID.randomUUID();
        when(routePlanRepository.findById(planId))
                .thenReturn(Optional.of(plan(planId, RoutePlanStatus.PROPOSED, RoutePlanSource.NIGHTLY, 1)));

        assertThatThrownBy(() -> service.override(planId, new OverrideRequest(UUID.randomUUID(), "x", List.of())))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("409");
    }

    @Test
    void activePlan_prefersApprovedHighestRevision() {
        RoutePlan proposed = plan(UUID.randomUUID(), RoutePlanStatus.PROPOSED, RoutePlanSource.NIGHTLY, 3);
        RoutePlan approvedR1 = plan(UUID.randomUUID(), RoutePlanStatus.APPROVED, RoutePlanSource.NIGHTLY, 1);
        RoutePlan approvedR2 = plan(UUID.randomUUID(), RoutePlanStatus.APPROVED, RoutePlanSource.MANUAL_OVERRIDE, 2);
        RoutePlan superseded = plan(UUID.randomUUID(), RoutePlanStatus.SUPERSEDED, RoutePlanSource.NIGHTLY, 4);
        when(routePlanRepository.findByCityIdAndValidForDate(cityId, date))
                .thenReturn(List.of(proposed, approvedR1, approvedR2, superseded));

        assertThat(service.activePlan(cityId, date)).contains(approvedR2);
    }
}
