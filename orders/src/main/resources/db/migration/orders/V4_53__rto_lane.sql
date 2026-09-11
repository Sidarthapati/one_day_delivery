-- R4 durable recovery: persist the return lane chosen at cancel time alongside the RTO intent, so the
-- reconcile backstop can recover a stranded intent on the correct lane. A pre-hub cancel resolves
-- SAME_CITY_FROM_ORIGIN at the origin hub; a cancel once hub-scanned/in-flight resolves REVERSE_FROM_DEST
-- at the destination hub. Both share the AT_ORIGIN_HUB state briefly, so the state alone can't tell them
-- apart — the stored lane disambiguates. Nullable (only set on a deferred POST_CUSTODY_CANCEL intent).
ALTER TABLE shipments
    ADD COLUMN rto_lane VARCHAR(24);
