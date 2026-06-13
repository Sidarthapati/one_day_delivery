-- C2C customers register with a phone number, fixed as the sender contact on their bookings.
-- Nullable: pre-existing users (admin, staff onboarded before this) and non-C2C accounts may lack one.
ALTER TABLE users ADD COLUMN phone VARCHAR(15);
