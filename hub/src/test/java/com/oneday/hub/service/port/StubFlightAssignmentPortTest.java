package com.oneday.hub.service.port;

import com.oneday.hub.config.ClockConfig;
import com.oneday.hub.config.HubProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalTime;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/** The bag must leave the hub {@code hubDepartureLeadMinutes} before the flight departs (§9 config). */
class StubFlightAssignmentPortTest {

    @Test
    void bagCutoffIsFiveHoursBeforeDeparture() {
        HubProperties props = new HubProperties();   // default hubDepartureLeadMinutes = 300 (5h)
        StubFlightAssignmentPort port = new StubFlightAssignmentPort(props);

        // Ready at IST midnight → catches the 06:00 departure; cutoff = 06:00 − 5h = 01:00 IST.
        ZonedDateTime readyIst = ZonedDateTime.of(2026, 7, 3, 0, 0, 0, 0, ClockConfig.IST);
        var assignment = port.assignFlight("MUMBAI", readyIst.toInstant());

        ZonedDateTime departure = ZonedDateTime.of(2026, 7, 3, 6, 0, 0, 0, ClockConfig.IST);
        ZonedDateTime cutoff = assignment.bagCutoff().atZone(ClockConfig.IST);

        assertThat(assignment.flightNo()).isEqualTo("ODMUMBAI06");
        assertThat(cutoff.toLocalTime()).isEqualTo(LocalTime.of(1, 0));
        assertThat(Duration.between(cutoff, departure)).isEqualTo(Duration.ofHours(5));
    }
}
