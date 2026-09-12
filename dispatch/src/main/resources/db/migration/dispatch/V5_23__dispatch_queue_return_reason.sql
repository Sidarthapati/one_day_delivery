-- SC1: distinguish a shift-close carry-back from an ordinary delivery-failure carry-back.
-- Null for every existing RETURN_TO_HUB row (they are delivery-failure carry-backs handled by the
-- deferred-retry engine). A SHIFT_CLOSE value re-enters the parcel into the dest-hub sort next shift.
ALTER TABLE dispatch_queue
    ADD COLUMN return_reason VARCHAR(20);
