-- The pickup task stays IN_PROGRESS between OTP-verify (PICKED_UP) and handoff (COMPLETED). This flag
-- lets the DA app tell "en route" from "picked up, awaiting handoff" so it can resume on the right step.
ALTER TABLE dispatch_queue ADD COLUMN picked_up BOOLEAN NOT NULL DEFAULT false;
