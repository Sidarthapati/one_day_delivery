package com.oneday.airline.api;

import com.oneday.airline.batch.FlightStatusPollJob;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * Dev/e2e-only hook to run the clock-based flight progress poll on demand (active outside prod only).
 *
 * <p>{@link FlightStatusPollJob#pollProgress()} normally fires on the {@code airline.status-poll-delay-ms}
 * schedule (default 5 min) and flips SCHEDULED&rarr;DEPARTED&rarr;LANDED once a flight's departure/arrival
 * {@code Instant} has passed. The full-day E2E harness time-travels a flight by setting its
 * {@code flight_instance.departure/arrival} into the past, then calls this endpoint to advance it
 * immediately instead of waiting out the poll cycle. Clock-only, no vendor calls. Never in prod.
 */
@RestController
@RequestMapping("/internal/dev/airline")
@Profile("!prod")
class DevFlightPollController {

    private final FlightStatusPollJob pollJob;

    DevFlightPollController(FlightStatusPollJob pollJob) {
        this.pollJob = pollJob;
    }

    @PostMapping("/force-poll")
    public ForcePollResult forcePoll() {
        pollJob.pollProgress();
        return new ForcePollResult("ok", Instant.now());
    }

    record ForcePollResult(String status, Instant polledAt) {}
}
