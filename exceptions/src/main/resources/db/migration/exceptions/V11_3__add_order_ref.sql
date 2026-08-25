-- Order back-reference on a case: which parent Order (Order → N shipments) the failed parcel belongs
-- to. Lets the problem-solve console group a parcel with its order siblings and RTO a whole failed
-- order in one action. Populated at open time from M4's ShipmentInfo; null for legacy/pre-order cases.
ALTER TABLE exception_case ADD COLUMN order_id  UUID;
ALTER TABLE exception_case ADD COLUMN order_ref VARCHAR(30);

-- Live-siblings lookup + batch-RTO-by-order both query the live cases of one order, so index the
-- order_ref over just the unresolved set (mirrors idx_exception_case_open's partial-index shape).
CREATE INDEX idx_exception_case_open_order ON exception_case (order_ref) WHERE resolved_at IS NULL;
