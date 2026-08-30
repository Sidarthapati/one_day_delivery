-- Return framework: a return child (<ref>_R) whose own delivery attempts are exhausted ends at
-- HELD_AT_HUB → ops disposition (no return-of-a-return). New terminal state.
-- PG 12+ allows ADD VALUE inside Flyway's transaction (the value is only added here, not used).
ALTER TYPE shipment_state ADD VALUE IF NOT EXISTS 'HELD_AT_HUB';
