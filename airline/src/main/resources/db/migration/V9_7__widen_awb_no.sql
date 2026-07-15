-- V9_4 sized awb_no at VARCHAR(30), but the generated format ("AWB-" + flight_no[≤20] + "-" +
-- flight_date[10] + "-" + an 8-char bag-id suffix) can reach ~44 chars — discovered live when a
-- real booking on a longer flight number overflowed the column. Widen with headroom.
ALTER TABLE awb ALTER COLUMN awb_no TYPE VARCHAR(64);
