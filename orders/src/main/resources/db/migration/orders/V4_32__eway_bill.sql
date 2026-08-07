-- Phase G: optional e-way bill number captured at booking (advisory now; NIC API integration
-- deferred to Track A). Required by GST law for interstate movement of goods > ₹50,000.
ALTER TABLE shipments ADD COLUMN eway_bill_number VARCHAR(20);
