-- M12 shuttle: an agent can ask the hub to seal an OPEN bag early so it can be batched onto a trip.
-- The hub console badges bags where this is set; the shuttle sets it via FlightBagService.requestSeal.
ALTER TABLE flight_bag ADD COLUMN seal_requested_at timestamptz;
