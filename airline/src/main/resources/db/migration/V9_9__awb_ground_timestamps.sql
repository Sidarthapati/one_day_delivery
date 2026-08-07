-- M9 §9 — two things the airport ground team confirms back to us: that a batch has been physically
-- handed over at the dock, and that it's been fully loaded onto the aircraft. Both are simply
-- timestamps recorded for later reporting; nullable until confirmed.
ALTER TABLE awb
    ADD COLUMN handed_over_at TIMESTAMPTZ,
    ADD COLUMN loaded_at TIMESTAMPTZ;
