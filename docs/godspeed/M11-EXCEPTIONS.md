# M11 — Exception Handling, Call Center & RTO · Godspeed Requirements

**Plan sources:** §5 & B.1/B.2 (RTO revenue-positive, −30–33%; WISMO support cost), Annexure G.1 (LM5 reattempt wave), Annexure H (RTO billed at standard rates), Annexure L (risk register).
**Module status:** Not started (consumes M4/M10). The plan makes failure handling a **margin** topic, not just an ops one.

## The plan's stance on failure
RTO is "pure loss" for a D2C brand, and prompt verified delivery cuts it **30–33%** (B.2) — that reduction "drops straight to margin" (§5). When a return does happen, it's carried at **standard rates, revenue-positive** (H). And "where is my order" queries — ~40% of D2C support tickets — are killed by LiveTrack, not by a call center (B.2). So M11's job is to **minimise** exceptions, **monetise** the unavoidable ones, and **capture** the rest cleanly.

## Features / requirements

- **R1 — RTO workflow, revenue-positive.** An undelivered/refused parcel becomes a return shipment on the reverse lane, **billed at standard return rates** (M2 R4 / H). RTO is a normal booking, not a write-off.
- **R2 — Reattempt before RTO (G.1).** Failed deliveries route to the **LM5 wave (7–10 PM) for reattempt** and SLA closure before any RTO decision. Define max attempts (PRD F1, open) per lane/category.
- **R3 — Failure capture & call-center queue.** Capture pickup-failed / delivery-failed / address-issue / refused events (from M5/M8), route to Call Center Agent (M1 role) for resolution input (reschedule, correct address, cancel).
- **R4 — Reschedule loops back to dispatch.** `PICKUP_RESCHEDULED` / `DELIVERY_RESCHEDULED` events re-enter M5 with new coords/time (already in M5 design's ExceptionsEventConsumer).
- **R5 — Prepaid nudge / verified delivery to cut RTO (B.2).** Support prepaid conversion + OTP/QR verified delivery as the mechanism behind the 30–33% RTO reduction. M11 tracks the RTO rate as a first-class KPI.
- **R6 — WISMO deflection is LiveTrack, not call center (B.2).** M11 should *measure* WISMO contact volume but the primary mitigation is LiveTrack visibility (cross-cutting). Proactive tracking cuts these 50–80%.
- **R7 — DA penalty / accountability workflow (PRD F2/F3, open).** Capture DA-attributable failures for the penalty workflow; append-only.
- **R8 — Cron-missed & flight-missed exceptions.** When a DA misses the cron (M5 `CRON_MISSED`) or a bag misses its flight (M9), open the exception, alert via M10, and drive manual resolution (next van/flight).

## Acceptance signals
- A failed delivery is auto-queued for LM5 reattempt before any RTO is created.
- An RTO produces a billable return shipment, not a zero-revenue event.
- RTO rate and WISMO contact rate are reportable KPIs per merchant/lane.

## Open questions / deltas
- Q-F1: Max reattempt count before RTO (PRD open) — per lane or per category?
- Q-F2/F3: DA penalty workflow definition (PRD open).
- Q-F4: COD-refused handling — does the merchant-QR model change the refusal/return economics vs carrier-COD?
