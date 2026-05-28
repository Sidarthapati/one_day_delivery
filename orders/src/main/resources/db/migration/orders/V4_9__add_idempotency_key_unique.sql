-- Enforces uniqueness of idempotency_key values on shipments.
-- Prevents duplicate shipment creation under concurrent requests with the same key.
-- NULL values are permitted (B2B batch imports may omit idempotency keys);
-- PostgreSQL treats each NULL as distinct, so multiple NULLs are allowed.
ALTER TABLE shipments
  ADD CONSTRAINT uq_shipments_idempotency_key UNIQUE (idempotency_key);
