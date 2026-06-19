# M8 — Barcode, Label & Scanning · Godspeed Requirements

**Plan sources:** §8 ("the parcel is visible the entire way — barcode to air waybill to live flight to rider"), B.1 (LiveTrack offering).
**Module status:** Not started (design doc `M8-BARCODE-DESIGN.md` exists). These requirements frame M8 as the **spine of LiveTrack** — the differentiator the plan says Godspeed builds deeply.

## Why M8 is load-bearing
LiveTrack is "the one piece of technology Godspeed builds deeply because no competitor has it" (§8). It is only as good as the scan ledger underneath it: **barcode → AWB → flight → rider** (§8). M8 owns the parcel's immutable identity and every scan event that makes the parcel *visible*.

## Features / requirements

- **R1 — Unique parcel identity at booking.** Generate a parcel ID / barcode the moment a shipment is booked (M4), printable on a label, scannable at every touch point. Format must encode origin city + date + sequence (e.g., `BLR-20260619-000042`, per existing examples).
- **R2 — Append-only scan ledger.** Every scan (pickup, van-handoff, dock-in, sort, bag-add, tender, flight-load, flight-arrive, break, delivery-van-load, delivered) is an immutable event with `(parcel_id, scan_type, actor, location, timestamp)`. Never mutated — platform invariant.
- **R3 — The visibility chain links the spine.** Each parcel scan must be **joinable to its bag, its AWB, and its flight** so LiveTrack can render "barcode → AWB → live flight → rider". M8 stores the parcel↔bag↔AWB linkage (with M7/M9).
- **R4 — Two scan-confirmation mechanisms (OD-8).** Pickup confirmed by **OTP** (M4 pickup-otp), delivery confirmed by **QR / scan** (plan's customer-pays-QR doorstep, §5). M8 records the confirming scan as the custody-transfer event.
- **R5 — Low-weight, document-friendly labels.** Adjacent verticals (KYC, legal, pharma — A.5) are document-light; labels must work on envelopes, not just boxes.
- **R6 — Scan events drive every downstream module.** M5 (custody), M7 (sort state), M9 (flight load/arrive), M10 (per-leg SLA timestamps), M11 (exception capture), LiveTrack (customer view) all consume the ledger. M8 is the single source of physical truth.

## Acceptance signals
- A parcel's full scan history reconstructs its custody chain end-to-end with no gaps.
- Any scan is immutable; a "correction" is a new compensating event, not an edit.
- Given a parcel ID, LiveTrack can resolve its current AWB and flight.

## Open questions / deltas
- Q-B1: Barcode standard (PRD H1) — Code128 / DataMatrix / QR? Blocks label + scanner spec.
- Q-B2: AWB issuer & parcel↔AWB linkage timing (depends on M9 GSA/airline contract).
- Q-B3: Offline scan capture (DA/hub connectivity gaps) and reconciliation order.
