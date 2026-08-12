-- M9 Task 2 — a one-shot guard so the post-take-off vendor check (FlightStatusPollJob.pollInFlight)
-- corrects each DEPARTED flight's arrival at most once. False until that check has run.
ALTER TABLE flight_instance
    ADD COLUMN inflight_checked BOOLEAN NOT NULL DEFAULT FALSE;
