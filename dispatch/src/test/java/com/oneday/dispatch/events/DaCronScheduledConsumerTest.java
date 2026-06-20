package com.oneday.dispatch.events;

import com.oneday.common.kafka.events.cron.DaCronScheduledEvent;
import com.oneday.dispatch.config.DispatchProperties;
import com.oneday.dispatch.domain.CronAssignmentStatus;
import com.oneday.dispatch.domain.DaCronAssignment;
import com.oneday.dispatch.repository.DaCronAssignmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real-Postgres test of the cron-schedule upsert: it must persist the full meeting-times list (sorted),
 * set the earliest as the primary {@code scheduled_meeting_time}, and be idempotent per (da, date).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class DaCronScheduledConsumerTest {

    @Autowired
    DaCronAssignmentRepository cronRepo;

    private final UUID da = UUID.randomUUID();
    private final UUID city = UUID.randomUUID();
    private final LocalDate date = LocalDate.now();
    private final ZoneId zone = ZoneId.of("Asia/Kolkata");

    private DaCronScheduledConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new DaCronScheduledConsumer(cronRepo, new DispatchProperties());
    }

    @Test
    void upsertStoresSortedMeetingTimesAndEarliestPrimary() {
        consumer.onCronEvent(event(List.of(LocalTime.of(10, 0), LocalTime.of(6, 0)), UUID.randomUUID()));

        DaCronAssignment row = cronRepo.findByDaIdAndOperatingDate(da, date).orElseThrow();
        assertThat(row.getMeetingTimes()).containsExactly("06:00", "10:00");
        assertThat(row.getScheduledMeetingTime())
                .isEqualTo(LocalDateTime.of(date, LocalTime.of(6, 0)).atZone(zone).toInstant());
        assertThat(row.getStatus()).isEqualTo(CronAssignmentStatus.SCHEDULED);
        assertThat(row.getCityId()).isEqualTo(city);
    }

    @Test
    void secondEventUpsertsTheSameRow() {
        UUID firstVan = UUID.randomUUID();
        UUID secondVan = UUID.randomUUID();
        consumer.onCronEvent(event(List.of(LocalTime.of(6, 0)), firstVan));
        consumer.onCronEvent(event(List.of(LocalTime.of(8, 0)), secondVan));

        List<DaCronAssignment> all = cronRepo.findByOperatingDateAndCityId(date, city);
        assertThat(all).hasSize(1);
        assertThat(all.get(0).getVanId()).isEqualTo(secondVan);
        assertThat(all.get(0).getMeetingTimes()).containsExactly("08:00");
    }

    @Test
    void emptyMeetingTimesIsIgnored() {
        consumer.onCronEvent(event(List.of(), UUID.randomUUID()));
        Optional<DaCronAssignment> row = cronRepo.findByDaIdAndOperatingDate(da, date);
        assertThat(row).isEmpty();
    }

    private DaCronScheduledEvent event(List<LocalTime> meetingTimes, UUID vanId) {
        return new DaCronScheduledEvent(city, date, da, UUID.randomUUID(),
                12.96, 77.60, meetingTimes, vanId, UUID.randomUUID());
    }
}
