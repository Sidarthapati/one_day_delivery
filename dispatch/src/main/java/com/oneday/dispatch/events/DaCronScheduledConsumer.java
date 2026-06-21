package com.oneday.dispatch.events;

import com.oneday.common.kafka.events.cron.CronEventPayload;
import com.oneday.common.kafka.events.cron.DaCronScheduledEvent;
import com.oneday.dispatch.config.DispatchProperties;
import com.oneday.dispatch.domain.CronAssignmentStatus;
import com.oneday.dispatch.domain.DaCronAssignment;
import com.oneday.dispatch.repository.DaCronAssignmentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Comparator;

/**
 * Consumes M6's cron stream ({@code oneday.cron.events}, queue {@code m5.cron}) and keeps M5's
 * {@code da_cron_assignment} in step with the nightly plan. The queue carries every cron payload type;
 * M5 acts only on {@link DaCronScheduledEvent} (the DA↔van rendezvous) and ignores the rest.
 *
 * <p>The full day's {@code meetingTimes} (M6-D-008) are stored as ISO strings; {@code scheduled_meeting_time}
 * keeps the earliest as the primary meeting for the current feasibility/monitor path.</p>
 */
@Component
public class DaCronScheduledConsumer {

    private static final Logger log = LoggerFactory.getLogger(DaCronScheduledConsumer.class);

    private final DaCronAssignmentRepository cronRepository;
    private final DispatchProperties props;

    public DaCronScheduledConsumer(DaCronAssignmentRepository cronRepository, DispatchProperties props) {
        this.cronRepository = cronRepository;
        this.props = props;
    }

    @RabbitListener(queues = DispatchMessagingTopology.CRON_QUEUE)
    @Transactional
    public void onCronEvent(CronEventPayload event) {
        if (event instanceof DaCronScheduledEvent scheduled) {
            upsert(scheduled);
        }
        // Other cron payloads (shuttle, route, van run-time) are not M5's concern — ignore.
    }

    private void upsert(DaCronScheduledEvent e) {
        if (e.meetingTimes() == null || e.meetingTimes().isEmpty()) {
            log.error("DA_CRON_SCHEDULED for da {} on {} has no meeting times — ignoring",
                    e.daId(), e.validDate());
            return;
        }
        LocalTime earliest = e.meetingTimes().stream().min(Comparator.naturalOrder()).orElseThrow();

        DaCronAssignment row = cronRepository.findByDaIdAndOperatingDate(e.daId(), e.validDate())
                .orElseGet(DaCronAssignment::new);
        row.setDaId(e.daId());
        row.setCityId(e.cityId());
        row.setOperatingDate(e.validDate());
        row.setCronVertexId(e.cronVertexId());
        row.setMeetingLat(e.meetingLat());
        row.setMeetingLon(e.meetingLon());
        row.setVanId(e.vanId());
        row.setMeetingTimes(e.meetingTimes().stream().sorted().map(LocalTime::toString).toList());
        row.setScheduledMeetingTime(toInstant(e.validDate(), earliest));
        row.setStatus(CronAssignmentStatus.SCHEDULED);   // a (re)plan resets the meeting to scheduled
        cronRepository.save(row);

        log.debug("Upserted cron for da {} on {}: {} meeting(s), primary {}",
                e.daId(), e.validDate(), e.meetingTimes().size(), earliest);
    }

    private java.time.Instant toInstant(LocalDate date, LocalTime time) {
        return LocalDateTime.of(date, time).atZone(ZoneId.of(props.getShift().getZone())).toInstant();
    }
}
