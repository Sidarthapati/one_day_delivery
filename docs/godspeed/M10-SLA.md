# M10 — SLA Monitoring & Escalation · Godspeed Requirements

**Plan sources:** §8 (24h promise / 16h target, engineered buffer), Annexure G (SLA leg budgets), Annexure D & L (99% SLA, churn, control tower), B.1 (engineered buffered SLA).
**Module status:** Not started (consumes M4/M8 events). The SLA engine is what makes "guaranteed" a real word.

## The promise M10 must guard
"Godspeed promises 24 hours to the market and runs to 16 hours internally. The 8-hour gap … is engineered buffer" (§8). Reliability *is* the product (B.1). M10 computes per-leg SLA state, fires the buffer logic, and escalates before the customer ever sees a breach. **Retention is an SLA function** (Annexure D): hold 99% and churn stays ~2.5%; miss it and no account team rescues it.

## Features / requirements

- **R1 — Per-leg SLA budgets (Annexure G).** Track each parcel against the leg time budget:

  | Leg | Budget |
  |---|---|
  | Origin airport (tender, screen, stage, load) | 3.0 h |
  | Air travel | 2.5 h |
  | Destination airport (unload, break, sort, DO, release) | 2.0 h |
  | First + last mile (road legs) | ~8.5 h |
  | **Internal ready-to-delivered target** | **16 h** |
  | **Public promise** | **24 h** |

- **R2 — GREEN / AMBER / RED per leg.** State derived from M8 scan timestamps vs the leg budget. AMBER = eating into the 8h buffer; RED = projected to breach the **16h internal** target (not the 24h public — so ops acts before the customer is at risk).
- **R3 — Buffer-aware projection.** M10 must project end-to-end completion from current scan state; the 8h buffer "absorbs the missed flight, the late pickup, the difficult delivery" (§8) — so a single late leg need not be RED if downstream budget covers it.
- **R4 — Control tower view (Annexure L).** A live ops dashboard of every in-flight parcel's SLA state, by lane/wave/city, with flight-bank discipline surfaced (M9). RED parcels routed to Supervisor / Station Manager for action.
- **R5 — Escalation to roles (M1).** RED → Supervisor/Station Manager escalation; repeated lane RED → Admin/control tower. Append-only escalation log.
- **R6 — 99% SLA target as a measured metric (D).** M10 produces the SLA pass-rate that gates expansion (Annexure L: "gated on SLA performance, not the calendar") and drives churn modelling.
- **R7 — Leg triggers from events.** Pickup-completed → leg-1 start; van-handoff → leg-1 close; flight-load/arrive (M9) → air leg; delivered → SLA close. M10 is a Kafka/RabbitMQ consumer of M4/M5/M7/M9/M8.

## Acceptance signals
- A parcel that misses its first flight but can make the next wave within the 16h budget stays AMBER, not RED.
- A parcel projected to breach 16h goes RED and escalates *before* the 24h public promise is at risk.
- Control tower shows live SLA state per lane/wave; lane pass-rate ≥ 99% is reportable.

## Open questions / deltas
- Q-S1: Buffer allocation policy — is the 8h buffer global or per-leg? Determines AMBER/RED thresholds.
- Q-S2: SLA clock start — order placed, pickup ready, or first scan? (Affects promised-date in M4.)
- Q-S3: Light-node deliveries on 3rd-party last mile — how is their leg SLA observed without own-scan coverage?
