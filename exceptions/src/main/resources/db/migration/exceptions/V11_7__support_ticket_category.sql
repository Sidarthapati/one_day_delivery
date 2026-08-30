-- xiv — support-ticket categorisation. Nullable: existing tickets stay untagged, intake sets it going forward.
ALTER TABLE support_ticket ADD COLUMN category VARCHAR(20);

-- ponytail: no index. The ops queue already filters the small unresolved set (idx_support_ticket_open);
-- add a (category) or (resolved_at, category) index only if the queue slows at real volume.
