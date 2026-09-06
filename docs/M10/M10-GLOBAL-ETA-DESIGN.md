# Global ETA — a real, per-parcel, data-driven ETA engine · Design (v0.1 — DRAFT, design only)

> Status: **design only — no code**. Discussion-3 item **(x) Global ETA**, Sid's half (the engine);
> Agniva owns the customer/notification refinement on top of the seam this doc defines.
> Placement: **extends the M10 `sla` module** (decided). Requirements: `docs/escalation/DISCUSSION-3-FEATURE-SPLIT.md §(x)`,
> `docs/M10/M10-SLA-DESIGN.md`, PRD §12.4.
> This doc mirrors the M10 design style (decision IDs `ETA-D-xxx`, Part 0 physical reality, scope in/out, annexures).

## Table of contents
- **Part 0 — Physical reality** (the problem today, glossary, the Delhi-rain walkthrough)
- **Part I — What & boundaries** (scope, the model, key decisions)
- **Part II — The engine** (leg-additive prediction, the statistical predictor, calibration, live signals, weather)
- **Part III — Integration & governance** (the notification seam, contracts, phasing, testing, open questions)
- **Annexure A** — how the majors do ETA (Delhivery / Shiprocket / DoorDash / project44 / Locus)
- **Annexure B** — reuse map (every existing piece and its role)

---

## Part 0 — Physical reality

### The problem today
There is **no real ETA**. `EtaPort`'s only implementation is `app/stubs/StubEtaAdapter` — a hardcoded constant
(intercity → next-day **14:00 IST**, same-city → **20:00 IST**). It is called **once, at BOOKED**; the documented
`AT_ORIGIN_HUB` recompute is an unimplemented `// TODO` (`orders/.../events/ScanEventsConsumer.java:48`); and the
stub is `@Profile("!prod")`, so **in production every parcel has no ETA bean at all**. The delay-notification path
(`ShipmentEtaServiceImpl.reviseEta` → `SHIPMENT_DELAYED`) exists but only fires on a **manual ops revise**.

Every parcel therefore shows the *same* promised time regardless of city-pair, weather, DA load, flight schedule,
or how the journey is actually going. That is a config guess, not an ETA.

### What "accurate" must mean (the CEO's ask)
A **trustworthy, per-parcel ETA first, then notifications on top.** The ETA must move with reality from **real data**:
if it is raining in Delhi, the **first-mile leg** of a Delhi→Hyderabad parcel is genuinely slower, and the ETA must
reflect that — not because someone typed a bigger number, but because the weather feed and the historical actuals say so.

### Glossary
- **Leg** — one hop: FIRST_MILE, ORIGIN_HUB, ORIGIN_AIRPORT, AIR, DEST_AIRPORT, DEST_HUB, LAST_MILE (`SlaLegType`,
  already in `common`). Same-city collapses to FIRST_MILE → ORIGIN_HUB → LAST_MILE.
- **Promise vs. prediction** — two different numbers, both already stored on `sla_shipment`:
  - **Promise (contractual):** `public_promise_at` (24h) / `internal_target_at` (16h). Fixed, config-derived. *Unchanged by this work.*
  - **Prediction (the ETA):** `projected_finish_at`. Today a fixed-budget roll-forward. **This design makes it a real, learned, live ETA.**
- **Leg duration** — how long a leg *will* take. Today = a config budget (`sla.legs`). **This design replaces it with a
  data-driven estimate.**
- **Actual** — how long a leg *did* take = `sla_leg.completed_at − started_at`. The training signal.

### The Delhi-rain walkthrough (DEL→HYD, DA pickup, intercity)
1. **Book, 08:00, clear weather.** `EtaEngineAdapter.fetchEta` sums the P80 predicted duration of the 7 legs for the
   DEL→HYD lane at that hour → `eta_promised = 09:40 next-day` (illustrative). `/track` shows it.
2. **09:00 — Open-Meteo reports heavy rain in DEL.** The sweeper recomputes. The predictor multiplies the **FIRST_MILE**
   leg (weather-exposed, origin city = DEL) by the rain factor (×1.25). The parcel is still on first-mile, so its
   projected end moves out; the whole-journey ETA slides ~30 min later. Because the slip crosses the material-change
   threshold, `reviseEta` runs → `eta_updated` set; because it is past `eta_promised + grace`, one `SHIPMENT_DELAYED`
   fires. The DEST leg is **not** touched — rain in Delhi does not slow the Hyderabad last mile.
3. **11:30 — the pickup van is running late.** `VAN_RUNNING_LATE(minutesLate=20)` lands → the current ground leg's
   `projectedEndAt` is pushed 20 min; ETA recomputed. No second notification (de-dup: still the same delay episode).
4. **14:00 — the flight is retimed 40 min later.** `FlightTimeChanged` sets the **AIR** leg's projected end to the new
   real arrival; downstream legs re-sum off it. ETA moves; a fresh notification only if it crosses the threshold again.
5. **Delivered.** Every leg now has an `actual` (completed − started) → tonight's calibration job folds it into the
   DEL→HYD lane stats, so tomorrow's rainy-morning first-mile estimate is a touch sharper.

Throughout, `internal_target_at` / `public_promise_at` never moved — the **promise** is stable; only the **prediction** tracked reality.

---

## Part I — What & boundaries

### 1. What the engine does
Turns `sla`'s existing per-leg projection into a **real ETA**: predicts each leg's duration from **historical actuals**
(percentiles per lane / hour / delivery-type), adjusts it by **live weather**, **live van/flight signals**, and
**first/last-mile DA queue + cron slack**, sums the remaining legs onto the current one, and drives the **already-built**
`reviseEta → SHIPMENT_DELAYED` path automatically when the number moves. Replaces the `EtaPort` stub so the initial
BOOKED ETA is real too.

### 2. Scope
**In (v1):**
- A **swappable** `LegDurationPredictor`; v1 = **statistical-from-actuals** (percentiles), config-budget fallback on cold start.
- A nightly **actuals calibration job** → `eta_leg_stat`.
- **Weather** as a per-leg duration multiplier (reusing the existing Open-Meteo `WeatherService`).
- **Live correction** from `VAN_RUNNING_LATE` / flight retime / `van_live_status.minutes_late`.
- **First/last-mile** load awareness from `DaPickupQueuePort` + `DaCronSchedulePort` (both in `common`).
- **AIR leg** uses the **real flight schedule** (`flight_instance` arrival), not a statistic.
- Real `EtaPort` adapter (replaces the stub) + auto-drive of `reviseEta` on material change, with spam guards.

**Out (v1):**
- A trained **gradient-boosted / neural** model — the interface is built for it; the impl is v2 (needs volume + MLops).
- **Live traffic** feed (none exists; OSRM is free-flow, `congestionFactor=1.0`) — proxied by time-of-day actuals + weather + queue factors.
- Notification **content / channels / quiet-hours** and the customer **per-leg presentation** — Agniva's half (the seam is defined here).
- Automated mitigation / rebooking (M9/M11 own that; the ETA only informs).

### 3. Key decisions
- **ETA-D-001 — Extend M10 `sla`, don't add a module.** `sla` already owns the leg taxonomy, projection, weather, sweeper,
  and event subscriptions, and already separates promise from prediction. Least new surface, no new cross-module coupling
  (`sla` depends only on `common`, `orders`, `barcode` — every signal used is reachable from those).
- **ETA-D-002 — Promise ≠ prediction.** `internal_target_at` / `public_promise_at` stay contractual and fixed;
  `projected_finish_at` becomes the ETA. This is why extending M10 does **not** muddy the SLA promise.
- **ETA-D-003 — Statistical-from-actuals in v1, behind a swappable interface.** "Data-driven, not a config guess" is met
  by real per-lane percentiles now; ML slots in later without touching the projection. (Matches how the majors staged it — Annexure A.)
- **ETA-D-004 — Leg-additive, not end-to-end.** Predict each leg and sum the remainder; completed legs contribute actuals,
  the AIR leg contributes a real schedule. This is the industry-standard decomposition and it fits the existing leg ledger exactly.
- **ETA-D-005 — Graceful cold-start.** Below a min-sample threshold a lane/leg falls back to today's config budget, so early
  ETAs degrade to current behaviour rather than emitting noisy predictions.
- **ETA-D-006 — The engine computes; `orders` writes.** `sla` never writes `Shipment` rows directly — it emits an ETA-revised
  event that `orders` turns into `reviseEta`. Keeps the module boundary and reuses the notification authority.

---

## Part II — The engine

### 4. The model — leg-additive predictive ETA
```
ETA(now) =  predicted_end(current leg)  +  Σ predicted_duration(each downstream leg not yet started)

  completed legs      → their ACTUAL elapsed (already stamped: sla_leg.completed_at − started_at)
  current in-progress → max(now, started + predicted_remainder)   (+ live override if any)
  future legs         → predicted_duration = percentile(actuals | leg, lane, hour, deliveryType)
                                              × weatherFactor(relevant city, leg)
                                              × liveFactor(DA queue depth, cron slack)   [first/last mile only]
  AIR leg             → real flight arrival (flight_instance), not a statistic
```
This is exactly `ProjectionCalculator`'s current shape (`expected_end_of_current_leg + Σ downstream budgets`) with the
fixed **budget** replaced by a **prediction**, and the existing `projectedEndAt` enrichment still winning when a live
signal has set it. SLA's colour logic is untouched — it now simply colours off real durations.

### 5. `LegDurationPredictor` (the swappable estimator)
`Duration predict(LegPredictionInput)` where the input carries `{leg, deliveryType, originCity, destCity, hourBucket,
weather, liveContext}`. v1 `StatisticalLegDurationPredictor`:
- **Base** = P50 (live recompute) or **P80** (the promised ETA — deliberately conservative) of the calibrated actuals for
  `(leg, lane, hourBucket, deliveryType)`; fallback = `sla.legs` budget below `min-samples`.
- **× weatherFactor** — ground legs only (`WeatherService.isWeatherExposedLeg`), keyed on
  `WeatherService.relevantCity(leg, origin, dest)` — origin while picking up, destination once moving. Config map by WMO band.
- **× liveFactor** — FIRST_MILE / LAST_MILE only: DA queue depth (`DaPickupQueuePort`) and remaining cron slack
  (`DaCronSchedulePort`) push the estimate up when the DA is loaded or the cutoff is near.
- **AIR** — bypasses statistics: scheduled air duration, superseded by the real arrival once a flight is assigned.

Pure and side-effect free, so a `GbtLegDurationPredictor` (v2) is a drop-in.

### 6. Calibration — where the data comes from (ETA-D-003)
A nightly `LegActualsCalibrationJob` reads **completed `sla_leg` rows** (self-contained in `sla`) joined to
`sla_shipment` for the lane, computes P50/P80 elapsed minutes per `(leg, lane, hourBucket, deliveryType)` with a
min-sample guard, and upserts **`eta_leg_stat`**. The predictor reads a cached snapshot. `sla_leg` already records
`started_at`/`completed_at` per named leg for every parcel, so the training set accrues automatically from day one —
no new instrumentation. (Richer first-mile actuals from `shipment_leg_events` can sharpen FIRST_MILE later; that table
is referenced in code but not yet migrated.)

### 7. Live signals (all already on the bus)
- `VAN_RUNNING_LATE` / `VAN_ARRIVED` (`oneday.cron.events`) → set the current ground leg's `projectedEndAt` (mirrors the
  existing `enrichLoopOverflow`), then `recompute`.
- `FlightTimeChanged` / flight-assigned (`oneday.flight.events`) → set the **AIR** leg's `projectedEndAt` to the real
  arrival. (Needs arrival exposed — the event carries departure+cutoff today; add arrival, or a small
  `FlightTimingPort` over `flight_instance` — see open questions.)
- `van_live_status.minutes_late` — read on recompute for the in-progress last mile.

### 8. Weather (reuse, don't rebuild)
`sla/.../service/WeatherService.java` already pulls Open-Meteo (keyless, free) for all five metros hourly, with
`adverseCities()`, `relevantCity(leg, origin, dest)`, and `isWeatherExposedLeg(leg)`. Today it only nudges triage
priority. This design **promotes it to a duration multiplier** in the predictor — the single change that makes the
Delhi-rain requirement real. All weather I/O stays behind that one class (swap to a keyed provider later = one file).

---

## Part III — Integration & governance

### 9. The notification seam (hand-off to Agniva)
After `SlaEngine.recompute`, if `projected_finish_at` moved past a **material-change threshold** (config, e.g. ±10 min)
vs. the last published ETA, `sla` emits **`ShipmentEtaRevisedEvent{shipmentRef, newEta, reason}`** on `oneday.sla.events`.
A small **`orders`** listener calls `ShipmentEtaService.reviseEta(ref, newEta, reason, "SYSTEM", null)`, which writes
`eta_updated` and — when the new ETA slips past `eta_promised + grace` — fires **`SHIPMENT_DELAYED`** automatically
(no manual revise). **Spam guards:** publish only on material change; de-dup the delay notification to **once per delay
episode** (track `last_delay_notified_eta_at` on `sla_shipment`). Beyond the seam, notification content / channels /
quiet-hours and the customer per-leg presentation are **Agniva's**.

### 10. Data & contracts
- **New:** `eta_leg_stat` (`sla` V10_6) — `(leg, lane, hour_bucket, delivery_type, p50_min, p80_min, sample_count, computed_at)`.
- **Alter:** `sla_shipment` (`sla` V10_7) — `last_published_eta_at`, `last_delay_notified_eta_at` (spam guards).
- **New event:** `ShipmentEtaRevisedEvent` in `common/.../events/`.
- **Replaced:** `StubEtaAdapter` → `EtaEngineAdapter` implements `common.port.EtaPort` (default/prod bean).
- **No `orders` migration** — `eta_promised` / `eta_updated` already exist on `Shipment` (L165-169) and already flow to
  `GET /shipments/mine/{ref}/track` (`ShipmentTrackResponse.Eta{promised, updated}`), so the live ETA surfaces to the
  customer as soon as §9 lands.
- **Config** (app `application.yml`, `sla` prefix): percentiles, `min-samples`, `weather-factors`, `material-change-minutes`,
  `live-factor` knobs. Existing `sla.legs` budgets remain the cold-start fallback.

### 11. Phasing (engine before notifications, per the CEO sequencing)
1. **P1 — leg-additive core.** `LegDurationPredictor` + `StatisticalLegDurationPredictor` (config fallback only),
   `ProjectionCalculator` estimate-driven, `EtaEngineAdapter` replacing the stub. → a real, explainable leg-sum ETA at
   BOOKED and on the sweeper, testable with no new data.
2. **P2 — calibration.** `eta_leg_stat` (V10_6) + nightly job + predictor reads the table. Now data-driven.
3. **P3 — real-data signals.** Weather multiplier; live van-late + flight-arrival enrichment; first/last-mile queue+cron liveFactor.
4. **P4 — write-back + auto-notify.** `ShipmentEtaRevisedEvent` + material-change publish + `orders` consumer → `reviseEta`
   + spam guards (V10_7). Hand notification refinement to Agniva.
5. **P5 (Agniva-leaning) — per-leg customer + ops surface** (extend `ShipmentSlaPort.SlaStatus` with `projectedFinishAt` +
   optional per-leg ETA breakdown for `/track` and the control tower).

### 12. Testing / verification (design intent)
- **Unit:** predictor (percentile, weather ×, queue/cron ×, AIR bypass, cold-start fallback); estimate-driven
  `ProjectionCalculator`; calibration job over seeded `sla_leg`; material-change + de-dup guards. `sla` + `orders` suites stay green.
- **Live E2E (local app vs fresh DB, JDK 21):** (a) DEL→HYD booking yields a real leg-sum ETA, not the constant;
  (b) after calibration, ETA reflects learned lane durations; (c) forced rain in DEL pushes the **FIRST_MILE** leg only;
  (d) `VAN_RUNNING_LATE` / flight retime moves the ETA within one sweeper cycle and fires **exactly one**
  `SHIPMENT_DELAYED` per episode (verify with `rabbitmqadmin -N cloudamqp`); (e) `internal_target_at` /
  `public_promise_at` unchanged throughout.

### 13. Open questions
- **Flight arrival signal** — `FlightTimeChangedEvent` carries departure+cutoff, not arrival. Add arrival to the event, or a
  tiny `FlightTimingPort` over `flight_instance`? (Needed for AIR-leg enrichment; small.)
- **Weather factor calibration** — start with a conservative WMO→factor map; graduate to *learned* weather sensitivity per
  city/leg once `eta_leg_stat` can be split by adverse/clear.
- **Lane granularity** — city-pair is the v1 lane key; is hex-pair worth it for first/last mile, or over-fit given volume?
- **`shipment_leg_events`** — migrate it to sharpen first-mile actuals, or is `sla_leg` enough for v1? (Design assumes `sla_leg` for v1.)
- **P80 vs P50 for the promise** — confirm the promised-ETA percentile with the business (conservative under-promise vs. tighter windows).

---

## Annexure A — how the majors do ETA (research)
- **Leg/segment-additive, not one end-to-end guess.** Multi-hop carriers predict each stage and sum the remainder,
  correcting per stage as actuals land — the decomposition this design uses. (project44 real-time engine; DoorDash
  per-stage deep-learning ETA.)
- **Start statistical, graduate to ML.** Production ETAs begin as percentile/statistical models over historical actuals,
  validated against reality, *then* layer gradient-boosted / neural models — exactly ETA-D-003. (Locus last-mile guide;
  Delhivery feature-engineering studies frame ETA as a regression on lane, time-of-day, distance, and a per-trip factor.)
- **Continuous recompute is the differentiator.** The majors recompute on every meaningful signal (traffic, weather,
  dwell, incident), not in a nightly batch — our 60s sweeper + event-driven enrichment.
- **Weather enters as a per-segment adjustment** on the exposed ground legs, recalculated as conditions change — our
  weather multiplier keyed on the relevant city.
- References: project44 "ETA reimagined"; DoorDash "Deep learning for smarter ETA predictions"; Locus "Engineering
  predictive ETA accuracy"; PeerJ "Ten quick tips for ETA with ML"; Delhivery feature-engineering repos.

## Annexure B — reuse map (existing → role)
| Existing | Role in the ETA engine | Change |
|---|---|---|
| `SlaLegCatalog` (`STATE_TO_LEG`, `plan`) | Leg decomposition | reuse as-is |
| `ProjectionCalculator.expectedEnd` + downstream sum | The leg-additive roll-forward | **edit**: budget → predictor estimate |
| `SlaEngine.recompute` + `SlaSweeper` (60s) + `SlaShipmentEventsConsumer` | Recompute driver | **extend**: emit ETA-revised on material change |
| `WeatherService` (Open-Meteo, 5 metros) | Weather signal | **promote**: nudge → duration multiplier |
| `sla_leg` (`started_at`/`completed_at`) | Actuals / training set | reuse as source |
| `internal_target_at` / `public_promise_at` (`sla_shipment`) | Contractual promise | **unchanged** (kept separate from prediction) |
| `enrichFlightCutoff` / `enrichLastMileDeadline` / `enrichLoopOverflow` | Live-signal precedent | **add**: AIR-arrival + van-late enrichment |
| `ShipmentEtaService.reviseEta` → `SHIPMENT_DELAYED` (`orders`) | Write + notify authority | **drive** it from the engine (was manual only) |
| `EtaPort` @ BOOKED (`BookingServiceImpl:346`, `B2bBookingServiceImpl:343`) | Initial ETA hook | **replace** stub with real adapter |
| `DaPickupQueuePort`, `DaCronSchedulePort` (`common`) | First/last-mile load | reuse for liveFactor |
| `flight_instance.departure/arrival/cutoff` (`airline`) | Real AIR-leg timing | read via port/event |
| `VanRunningLateEvent`, `FlightTimeChangedEvent`, `van_live_status.minutes_late` | Live corrections | consume for enrichment |
