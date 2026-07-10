# M5 — DA Dispatch · Godspeed Requirements

**Plan sources:** §8 (execution, hours around the flight), Annexure G.1 (pickup/delivery waves), Annexure J (first/last-mile cost curves), E.3 (full vs light nodes).
**Module status:** M5 **in active implementation** (this branch). Cron-meeting feasibility is the hard constraint already designed. These requirements bind M5 to the plan's **wave architecture** and **node tiering**.

## Why M5 is where the 24h promise is won
"A 24-hour promise is won or lost on the ground, in the hours around the flight" (§8). M5 sequences DA pickups so every parcel reaches the consolidation van's **cron meeting** in time for its flight wave, and on the destination side sequences deliveries within the day's last-mile waves.

## Features / requirements

- **R1 — Five pickup waves (FM1–5), hard cutoffs (G.1).** Pickups are organised into waves at **8 AM, 11 AM, 2 PM, 6 PM, 12 AM**; **FM5 (12–1 AM)** is the night-flight injection on the discounted cargo window. A booking's eligible flight is set by which wave its pickup can make → drives the cron feasibility deadline M5 already enforces.
- **R2 — Five delivery waves (LM1–5).** Deliveries at **8 AM, 10 AM, 1 PM, 4 PM, 7 PM**; **LM5 (7–10 PM)** is **SLA closure + reattempts** (ties to M11). M5's delivery queue must respect these wave windows.
- **R3 — Cron-meeting feasibility = hard constraint** (unchanged from M5 design): no pickup assigned unless the DA can still reach the consolidation van vertex before the wave/flight cutoff. This *is* the in-codebase `CronFeasibilityService`.
- **R4 — Node-tier-aware dispatch (E.3).** Full nodes (6 cities) use own-DA pickup *and* delivery queues. **Light nodes (4 cities) are delivery-focused with third-party / on-demand last mile** — M5 either (a) hands the delivery task to a 3rd-party adapter, or (b) is bypassed for light-node deliveries. Decide and encode.
- **R5 — Wave-to-flight binding is upstream of feasibility.** The `scheduled_meeting_time` M5 consumes from M6 must derive from the wave→flight schedule, so M5's deadline math is anchored to a real departure (M9), not a nominal time.
- **R6 — COD-to-merchant at the door (§5).** On `drop-completed`, if COD, M5 surfaces the **merchant QR** to the DA app and records direct settlement (flat ₹7), emitting `COD_COLLECTED`. No carrier cash float.
- **R7 — ~70% DA utilisation cost floor** (CLAUDE.md invariant, consistent with J's first/last-mile curves) — don't optimise purely for speed; the cheapest-insertion heuristic already encodes detour cost.
- **R8 — Reattempt handling.** Failed deliveries route to LM5 reattempt and/or M11; M5 must support re-queuing a delivery task within the day's remaining waves.

## Acceptance signals
- A pickup booked at 1:45 PM is eligible for the 2 PM (FM3) wave only if the DA can reach the cron vertex before its flight cutoff; otherwise deferred to FM4.
- A light-node (e.g., Pune) delivery does **not** create an own-DA cron-grid task.
- A COD delivery completion records merchant-QR settlement + emits `COD_COLLECTED`, with no carrier-held cash.

## Open questions / deltas
- **Δ Light-node last mile:** is there a `ThirdPartyLastMilePort`, or are light-node deliveries entirely outside M5? (Blocks R4.)
- Q-D1: Where do FM/LM wave definitions live — `DispatchProperties`, M6 plan, or a shared network-config module? (They're also needed by M4 promised-date and M9.)
- Q-D2: Night wave FM5 (12 AM) crosses the operating-date boundary — confirm `operating_date` handling for after-midnight pickups.
