# M6 — Van Routing & Scheduling · Godspeed Requirements

**Plan sources:** §8 (two aggregation points, point-to-point), Annexure G (SLA & wave architecture), G.1 (waves), E.2 (network model).
**Module status:** M6 cron contract implemented (`DaCronScheduledEvent`, `da_cron_schedule`, `/routing/cron/da/{daId}`). These requirements bind the van plan to the **wave/flight calendar** and the **two-aggregation** model.

## The journey M6 must enable
"The journey runs through only two aggregation points — origin city to flight, flight to destination city — against the multi-hub journey of a courier" (§8). M6 plans the consolidation van milk-runs that **gather pickups from DA tiles into the origin hub** and **distribute from the destination hub to delivery tiles**, timed to the flight waves.

## Features / requirements

- **R1 — Two-aggregation, point-to-point (E.2).** No multi-hub routing. The origin-side plan ends at the **one** origin hub; the destination-side plan starts at the **one** destination hub. M6 never routes parcel through a third sort.
- **R2 — Wave-aligned cron meetings (G.1).** The consolidation van's vertex meeting times must align to the FM pickup waves (8 AM, 11 AM, 2 PM, 6 PM, 12 AM) so a DA's `scheduled_meeting_time` (consumed by M5) corresponds to a real flight injection — especially **FM5 night wave** onto the discounted 8 PM–2 AM cargo window (F.2).
- **R3 — Cron schedule carries a list of meeting times** (already: M6-D-008, `meetingTimes List`). Multiple van passes per day = multiple waves.
- **R4 — Destination distribution plan (LM waves).** Mirror plan for the destination hub → delivery-tile milk-run aligned to LM1–5 (8 AM, 10 AM, 1 PM, 4 PM, 7 PM), with LM5 reserved for SLA closure/reattempt.
- **R5 — Nightly stability** (platform invariant): routes replan once nightly over the grid graph; intraday change needs Station Manager approval. Unchanged.
- **R6 — Full nodes only.** Van consolidation runs exist for the **6 full nodes**; **light nodes** (delivery-focused, 3rd-party last mile) need no own consolidation van plan (E.3) — at most a hub→3rd-party handoff schedule.
- **R7 — Grid co-design.** Van vertices must sit on M3 grid vertices (existing invariant); the 10-city / node-tier split (M3 R2) means M6 only plans for full-node grids.

## Acceptance signals
- Each full-node DA's cron schedule lists meeting times that map 1:1 to FM waves and onward to a real flight cutoff.
- No route plan exists for a light node's own DAs.
- The night wave (FM5) produces a cron meeting feeding the 8 PM–2 AM flight window.

## Open questions / deltas
- Q-R1: Does the wave/flight calendar live in M6, M9, or a shared network-config? M6 needs flight cutoffs to set meeting times; M9 owns the schedule.
- Q-R2: Light-node hub→3rd-party-last-mile handoff — is that an M6 schedule, an M7 manifest, or out of M6 entirely?
