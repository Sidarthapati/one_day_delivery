-- A reassignment books a *replacement* AWB for the same bag while the old one is kept, marked
-- SUPERSEDED (§7 — "the old booking is never deleted, just marked as replaced and linked forward to
-- the new one"). V9_4's plain UNIQUE(bag_id) made that impossible. Relax to: at most one BOOKED row
-- per bag at a time (superseded/cancelled rows don't count).
ALTER TABLE awb DROP CONSTRAINT uq_awb_bag;

CREATE UNIQUE INDEX uq_awb_bag_booked ON awb (bag_id) WHERE status = 'BOOKED';
