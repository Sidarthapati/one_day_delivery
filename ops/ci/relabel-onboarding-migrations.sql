-- One-time relabel for the Route 1 migration renumber (onboarding migrations V10/V11 → V1_11_1/V1_11_2).
--
-- Run this on each ALREADY-MIGRATED database (staging, and any dev DB the renamed code will boot
-- against) *BEFORE* deploying the renamed migration files. It renames the two Flyway history rows so
-- they match the renamed files. Fresh DBs need nothing — they build correctly from scratch.
--
-- Safe: the file *contents* didn't change, so checksums are unchanged — only version + script.
-- Idempotent: the WHERE clauses no-op if already relabeled. Reversible (swap the values back).
-- Verify after: the app should boot with Flyway reporting the schema current (no pending, no missing).

UPDATE flyway_schema_history
   SET version = '1.11.1', script = 'V1_11_1__create_onboarding_requests.sql'
 WHERE version = '10' AND script = 'V10__create_onboarding_requests.sql';

UPDATE flyway_schema_history
   SET version = '1.11.2', script = 'V1_11_2__add_phone_to_onboarding_requests.sql'
 WHERE version = '11' AND script = 'V11__add_phone_to_onboarding_requests.sql';

-- Sanity check (expect the two 1.11.x rows, no leftover 10/11):
-- SELECT version, script FROM flyway_schema_history WHERE version IN ('10','11','1.11.1','1.11.2');
