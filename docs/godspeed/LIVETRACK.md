# LiveTrack · Godspeed Requirements (cross-cutting / new surface)

**Plan sources:** §5 & B.1 (LiveTrack offering), §8 ("the parcel is visible the entire way — barcode to air waybill to live flight to rider … the one piece of technology Godspeed builds deeply"), B.2 (WISMO = ~40% of support tickets).
**Module status:** **Not an existing M1–M11 module.** The plan names LiveTrack as *the* genuine technical differentiator ("no competitor has it"). It is a read/aggregation surface over M8 scans, M9 flight status, M5 rider GPS, and M4 order state.

## Why LiveTrack is called out
Every incumbent shows a generic "in transit." Godspeed shows **where the parcel actually is, end to end** (B.1). This is both a conversion/loyalty lever and the primary WISMO-deflection mechanism (B.2: proactive tracking cuts "where is my order" tickets 50–80%). It is the one piece the plan says is built deeply.

## The visibility chain (§8)
```
barcode (M8)  →  air waybill / AWB (M9 + M7)  →  live flight (M9)  →  rider GPS (M5)  →  delivered (M8 scan)
```
LiveTrack stitches these four data sources into one customer- and merchant-facing live view.

## Features / requirements

- **R1 — Flight-level tracking, not generic status.** Surface the actual segment the parcel is in, including **live flight position/status** (M9 R-flight-status). This is the differentiator — a generic "in transit" is explicitly rejected (B.1).
- **R2 — End-to-end, single timeline.** One view spanning pickup → cron handoff → origin hub → AWB tender → flight → destination hub → out-for-delivery → delivered, driven by the M8 append-only scan ledger (M8 R6).
- **R3 — Rider leg with live GPS.** On first/last mile, show the DA's live position (M5 `DaLiveStatus` GPS heartbeat) as "rider en route", with the doorstep OTP/QR step.
- **R4 — Customer + merchant + buyer access.** The buyer (via a tracking link, no login), the merchant (dashboard, Merchant Platform R6), and ops (control tower, M10) all read the same truth at different granularity.
- **R5 — Proactive notifications.** Push status changes (picked up, flew, out for delivery, delivered) to deflect WISMO contacts before they happen (B.2). This is the support-cost lever.
- **R6 — Definite-date reinforcement.** Show the promised delivery date (M4 R2) and live confidence against it (M10 SLA state: on-track / at-risk), turning the checkout promise into visible reliability (B.1).
- **R7 — AWB-traceability dependency.** LiveTrack only works if AWB control is preserved — so it **forbids freight-forwarder consolidation** that hides the AWB (M9 R3/R4). This is a hard architectural constraint, not a nice-to-have.
- **R8 — Read-only aggregation, no new source of truth.** LiveTrack persists nothing authoritative; it composes M8/M9/M5/M4. The ledger stays the truth (M8 R2).

## Acceptance signals
- A buyer with a tracking link sees the live flight leg (not "in transit") and the rider's live position on last mile.
- Every state transition fires a proactive notification.
- Disabling AWB-level traceability (e.g., forwarder consolidation) visibly breaks the flight leg — proving the dependency is enforced.
- WISMO contact rate per merchant is measurably reduced post-LiveTrack (tracked with M11 R6).

## Open questions / deltas
- Q-LT1: Live flight position source — airline API, GHA feed, or third-party flight tracker (FlightRadar-style)? (Ties M9 Q-L3.)
- Q-LT2: Is LiveTrack a **new module (M13)** or part of the Merchant Platform / a thin BFF over existing modules? Recommend a dedicated read-service given its strategic weight.
- Q-LT3: Buyer tracking-link auth (tokenised, no login) and privacy of rider GPS exposure.
