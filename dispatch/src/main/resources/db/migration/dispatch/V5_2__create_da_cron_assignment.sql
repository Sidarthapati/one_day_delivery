-- M5 dispatch: per-DA cron meeting for the operating day — the van rendezvous M5 must protect
-- (cron-meeting hard constraint). Sourced from M6's DaCronScheduledEvent / da_cron_schedule.
-- NOTE (M6-D-008): M6 emits a LIST of meeting times per DA/day. v1 stores the next/primary meeting
-- here; storing the full list (e.g. a meeting_times JSONB column) is a follow-up when ShiftLoadJob
-- lands (Phase 2). One row per DA per operating date.
CREATE TABLE da_cron_assignment (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    da_id                  UUID NOT NULL,
    city_id                UUID NOT NULL,
    operating_date         DATE NOT NULL,
    cron_vertex_id         UUID NOT NULL,             -- M3 hex vertex where the van meets the DA
    meeting_lat            DOUBLE PRECISION NOT NULL,
    meeting_lon            DOUBLE PRECISION NOT NULL,
    scheduled_meeting_time TIMESTAMPTZ NOT NULL,
    van_id                 UUID,
    status                 VARCHAR(20) NOT NULL DEFAULT 'SCHEDULED', -- SCHEDULED|COMPLETED|MISSED|CANCELLED
    handoff_completed_at   TIMESTAMPTZ,
    parcel_count_handed    INT,
    UNIQUE (da_id, operating_date)
);

-- Supports the shift-load query: all cron assignments for a city on a date.
CREATE INDEX idx_da_cron_assignment_city_date ON da_cron_assignment (operating_date, city_id);
