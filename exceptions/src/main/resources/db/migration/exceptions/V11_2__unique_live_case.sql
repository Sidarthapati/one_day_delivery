-- One live (unresolved) case per shipment — now enforced at the DB layer.
-- V11_1 created this as a plain partial index; the check-then-insert in
-- ExceptionCaseServiceImpl.captureDaFailure could race two concurrent failure
-- events into two live rows, splitting attempt_no so the UNDELIVERABLE/RTO cap
-- never trips. UNIQUE makes the second insert fail instead.
DROP INDEX IF EXISTS idx_exception_case_live_shipment;
CREATE UNIQUE INDEX idx_exception_case_live_shipment
    ON exception_case (shipment_id) WHERE resolved_at IS NULL;
