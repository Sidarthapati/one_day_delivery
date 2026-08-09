-- DA "Mark arrived" timestamp: set when the DA taps arrived at a pickup/delivery stop.
-- Nullable; the arrived→picked_up/completed gap measures dwell time at the customer.
ALTER TABLE dispatch_queue ADD COLUMN arrived_at timestamptz;
