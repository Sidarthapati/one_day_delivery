-- M5 dispatch: store the FULL list of a DA's cron meeting times for the day (M6-D-008). M6's
-- DaCronScheduledEvent carries meetingTimes as a list; scheduled_meeting_time keeps the primary
-- (earliest) meeting for the existing feasibility/monitor path, while meeting_times holds them all
-- (JSON array of ISO LocalTime strings, e.g. ["06:00","10:00"]) for next-meeting selection later.
ALTER TABLE da_cron_assignment
    ADD COLUMN meeting_times JSONB NOT NULL DEFAULT '[]'::jsonb;
