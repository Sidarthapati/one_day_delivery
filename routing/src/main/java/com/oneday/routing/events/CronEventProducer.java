package com.oneday.routing.events;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oneday.common.kafka.EventPublisher;
import com.oneday.common.kafka.EventStreams;
import com.oneday.common.kafka.events.cron.DaCronScheduledEvent;
import com.oneday.common.kafka.events.cron.RouteChangedEvent;
import com.oneday.common.kafka.events.cron.RoutePlanPublishedEvent;
import com.oneday.common.kafka.events.cron.ShuttleScheduledEvent;
import com.oneday.routing.domain.DaCronSchedule;
import com.oneday.routing.domain.RoutePlan;
import com.oneday.routing.service.model.ShuttleTimetable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalTime;
import java.util.List;

/**
 * Publishes M6's plan-time cron events on {@code oneday.cron.events} (§17.1) via the shared
 * {@link EventPublisher}. The typed {@code common.kafka.events.cron.*} payloads are the contract;
 * this class is the only place that maps M6 domain rows onto them. Run-time events
 * (VAN_ARRIVED, HANDOFF_*, …) arrive in later PRs.
 */
@Component
public class CronEventProducer {

    private static final Logger log = LoggerFactory.getLogger(CronEventProducer.class);
    private static final TypeReference<List<String>> TIME_LIST = new TypeReference<>() {};

    private final EventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    CronEventProducer(EventPublisher eventPublisher, ObjectMapper objectMapper) {
        this.eventPublisher = eventPublisher;
        this.objectMapper = objectMapper;
    }

    /** DA_CRON_SCHEDULED → M5: the DA's vertex + the day's meeting times. */
    public void emitDaCronScheduled(DaCronSchedule cron, double meetingLat, double meetingLon) {
        eventPublisher.publish(EventStreams.CRON_EVENTS, new DaCronScheduledEvent(
                cron.getCityId(),
                cron.getValidDate(),
                cron.getDaId(),
                cron.getHexVertexId(),
                meetingLat,
                meetingLon,
                parseMeetingTimes(cron.getMeetingTimes()),
                cron.getVanId(),
                cron.getRoutePlanId()));
    }

    /** ROUTE_PLAN_PUBLISHED → M10, van app: a city's plan is approved & active. */
    public void emitRoutePlanPublished(RoutePlan plan) {
        eventPublisher.publish(EventStreams.CRON_EVENTS, new RoutePlanPublishedEvent(
                plan.getCityId(),
                plan.getValidForDate(),
                plan.getId(),
                plan.getVansUsed() != null ? plan.getVansUsed() : 0,
                plan.getRecommendedVanCount() != null ? plan.getRecommendedVanCount() : 0,
                plan.getProvisioningFlag() != null ? plan.getProvisioningFlag().name() : null));
    }

    /** ROUTE_CHANGED → M5, M10, station mgr: an intraday override took effect. */
    public void emitRouteChanged(RoutePlan revision, java.util.UUID actorId, String reason) {
        eventPublisher.publish(EventStreams.CRON_EVENTS, new RouteChangedEvent(
                revision.getCityId(),
                revision.getValidForDate(),
                revision.getId(),
                actorId,
                reason));
    }

    /** SHUTTLE_SCHEDULED → M9, M10: the periodic hub↔airport timetable. */
    public void emitShuttleScheduled(ShuttleTimetable timetable, java.util.UUID routePlanId) {
        eventPublisher.publish(EventStreams.CRON_EVENTS, new ShuttleScheduledEvent(
                timetable.cityId(),
                timetable.validDate(),
                routePlanId,
                timetable.departureTimes(),
                timetable.hubToAirportMinutes()));
    }

    // meeting_times is stored as a JSON array of "HH:mm" strings; the event carries LocalTime.
    private List<LocalTime> parseMeetingTimes(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, TIME_LIST).stream().map(LocalTime::parse).toList();
        } catch (Exception e) {
            log.warn("Could not parse meeting_times json '{}': {}", json, e.getMessage());
            return List.of();
        }
    }
}
