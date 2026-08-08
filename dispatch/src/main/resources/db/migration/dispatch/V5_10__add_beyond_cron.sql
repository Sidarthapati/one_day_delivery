-- Cron-aware reorder: marks a QUEUED task the DA can't reach before its cron/van-meeting cutoff.
-- Such a task is kept on the same DA but demoted to the tail ("after van meeting") and excluded from
-- pre-cron feasibility; it auto-promotes once reachable again. Mutable (unlike insertion-time cron_safe).
ALTER TABLE dispatch_queue ADD COLUMN beyond_cron BOOLEAN NOT NULL DEFAULT false;
