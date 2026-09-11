# Godspeed — Discussion 4: Pending Features, Current State + Sid/Agniva Split

## Context

The fourth CEO discussion (notes headed "Project Discussion 3" in the book; this is our **fourth** split
doc). This round is **grounded in the two handwritten pages** — the authoritative list of what we
actually agreed to do — not the AI‑summarised monologue (which pulled in adjacent points that aren't on
the real list). It does the same two things the earlier rounds did:

1. **Where we are today** — for each item, what already exists in the code (with file anchors), what's
   missing, and rough effort.
2. **A two-person split** along the same seam as before — **Sid owns Operations / DA / Field /
   Station; Agniva owns Merchant / Customer / Support / Platform** — full stack per feature.

**This round is mostly discussion, not build.** Only a handful of items are actual engineering; the rest
are **joint discussion points** — scenarios Sid and Agniva work through *together* before anything is
built (or that are deliberately parked). Those live in their own section and are **not** assigned to one
person. **Build tasks** and **joint discussion points** are kept strictly separate below.

**Two builds are carried / shared.** **(E) Global ETA** stays the joint build it was in Discussion 3
(item x) — a real ETA engine (Sid's routing/airline side) plus the proactive notification trigger
(Agniva's side); it **needs a working session with Sid before Agniva picks it up**. **(CASH) Driver cash
ledger** sits under Agniva's Discussion‑3 (ix) COD‑settlement umbrella; Sid consults on the DA
cash‑custody leg.

Current state was verified against the live code on branch `feat/da-disposition-v1` on 2026‑09‑11.
Cross‑references to Discussion 3 are noted where they overlap. **Note:** the dwell/location‑stub metric
(needed for D2) and the app‑download offer (C1) live on unmerged worktree branches (`od-location-stub`,
`feat/app-download-offer`).

Legend: 🟢 built · 🟡 partial (foundation exists) · 🔴 not built · **Type**: 🔧 build · 🗣️ discussion ·
🔧🗣️ discussion‑then‑build · **Effort**: S / M / L.

---

## Build tasks

### A. RTO / returns  *(Sid — refines Discussion‑3 (iii), which is 🟢 built)*

| # | Item (as noted) | State | Key anchors / what's missing | Type · Effort | Owner |
|---|---|---|---|---|---|
| R1 | **Reuse the same barcode for the return shipment** — no new barcode, no re‑boxing. | 🔴 (child mints a **new** barcode today) | `ReturnServiceImpl.mintChild` (`orders/.../service/impl/ReturnServiceImpl.java:155‑209`) mints the return child as a new `Shipment` `<ref>_R` (ref at `:158`) with **`parcelId=null`** — it copies dimensions/weight ("same physical parcel", `:182‑188`) but not the barcode. So when the `_R` child is dock‑received, `LabelServiceImpl.generateLabel` (`barcode/.../service/LabelServiceImpl.java:26‑40`) mints a **fresh** id off a different hub/day counter (`ParcelIdGenerator.java:31‑40`, `1DD-{destHubIATA}-{yyMMdd}-{seq6}`). **Build:** (1) carry `original.getParcelId()` onto the child (after `:188`); (2) a reuse guard in `generateLabel` (`:34‑35`) so a child that already carries a parcelId skips `generator.next()` and does **not** bump the counter; decide the child re‑scans cleanly under the original prefix. Re‑boxing removal is a no‑op (none exists). | 🔧 S | **Sid** |
| R4 | **Post‑sortation policy: "once hub sorting is done it's ~impossible to retrieve the packet, so we send it to dest even if RTO is recorded."** | 🟡 (refines the built mid‑transit RTO) | A physical‑recall boundary decision on top of Discussion‑3's mid‑transit RTO (`ReturnServiceImpl` derives the child's re‑entry from the original's current location). The rule: **before** hub sortation → recall/carry back; **after** sortation → let the parcel ride to destination, then RTO from there (don't pull it mid‑stream). Mostly a **policy encoding** of the seal/sortation boundary already modelled — bound the recall to pre‑sortation states and route post‑sortation RTOs through the destination. | 🔧🗣️ S | **Sid** |

### B. Merchant / Platform  *(Agniva)*

| # | Item | State | Key anchors / what's missing | Type · Effort | Owner |
|---|---|---|---|---|---|
| M1 | **Team‑member budget toggle when adding a teammate** — admin defaults to full wallet/credit access; limited users get a fixed amount or a percentage cap. | 🟡 (coarse OWNER/MEMBER, no budget) | **Refines Discussion‑3 (vi)** (per‑user RBAC + per‑member budget, already Agniva's). `b2b_account_member` (`V4_44`) is OWNER/MEMBER only, **no budget**; credit + wallet are account‑level and any member draws the shared pool (`B2bBookingServiceImpl` checks `outstanding + total > creditLimit`). **Build:** an unlimited/limited flag per member + a `spend_limit_paise` **or** `spend_limit_pct` (of wallet/credit), enforced alongside the account credit check; admin toggle in the business console. | 🔧 M–L | **Agniva** |

### C. Driver cash ledger  *(Agniva — under Discussion‑3 (ix) COD‑settlement; Sid consults DA cash‑custody leg)*

| # | Item | State | Key anchors / what's missing | Type · Effort | Owner |
|---|---|---|---|---|---|
| CASH | **(v) 60 days of cash‑balance history; a "station wallet" credited from actuals; money deposited to account.** Concretely: fix the deposit script & re‑demo; **deposit confirmed by the station/hub manager on physical handover** (not the DA, not Razorpay); **DA app shows cash‑in‑hand + a 60‑day deposit/collection trail with date filter + export**; a **cash‑in‑hand ceiling (~₹5,000)** forcing a deposit before the next trip (policy on partial deposits / petrol deductions / UPI); **independent station‑level verification** (the station manager is as much a fraud risk as the DA — August has ~₹7 L pending reconciliation). | 🟡 (ledger + self‑declared deposit exist; DA app + controls missing) | Backend ledger exists: `da_cod_balance.cash_in_hand_paise` + `da_cod_ledger` (`orders/.../V4_50`; COLLECTION/DEPOSIT/ADJUSTMENT), deposit + reconcile schema (`cod_cash_deposit`, `V4_33`; dedup `V4_36`), DA endpoints (`DaCodController.java:40` deposit / `:48` summary / `:54` ledger), admin reconcile (`AdminCodController.java:163`), `CodCashServiceImpl` (record `:58`, summary `:90`, reconcile `:179`), admin console (`apps/admin/.../cod-cash/page.tsx`). **Gaps vs. the ask:** (a) deposit is **DA‑self‑declared** then a city admin flips status — **no station‑manager physical counter‑sign at deposit time**; (b) the **DA app shows no ledger‑backed cash‑in‑hand** — `WorkScreen.tsx:119‑143` derives it locally from the task list, and no `/cod/da/*` call exists in `oneday-driver-app`; (c) **no date‑range / 60‑day window / CSV export** on either side; (d) **no ~₹5k ceiling gate** (`CodCashServiceImpl.java:204` comment "no gate", balances not clamped); (e) **no independent station‑level count** distinct from the reconciler; (f) the "deposit script" isn't a file — no seed creates deposits (docs note API‑only "smoke deposits"), so the demo gap is simply that **no DA‑app deposit UI exists**. `V4_50` also defers opening‑balance backfill (#185). | 🔧🗣️ L | **Agniva** (Sid consults DA leg) |

### D. Ops infrastructure — **joint tasks**  *(Sid + Agniva)*

| # | Item | State | Key anchors / what's missing | Type · Effort | Owner |
|---|---|---|---|---|---|
| A1 | **(vi) Daily closing of the asset registry, based on shifts.** | 🟡 (point‑in‑time snapshot only) | M13 `assets` has the custody ledger (`AssetCustodyEvent`, `AssetEventType`) + shift‑aware van select (`AssetController.java:171` select‑van / `:178` return‑van) + an **on‑demand** "still‑out" list (`GET /assets/reconciliation` `:72` → `AssetRepository.java:39`) + station view (`apps/station/.../assets/page.tsx`). **Missing:** no shift‑tied **close** — no `@Scheduled` end‑of‑shift reconcile, no persisted closing record that checks all custody (vans/scanners/bags) against expected holders at shift boundary. **Build:** a shift‑close job + closing‑record table + a console close screen. | 🔧 M–L | **Sid + Agniva** |
| SC1 | **(vii) Shift closing: a DA must return in‑hand parcels back to the hub** before going offline. | 🟡 (carry‑back exists per‑failure, not at shift end) | `ShiftEndJob.endShift` (`dispatch/.../batch/ShiftEndJob.java:67‑109`, cron `0 5 14,22`) only **defers** never‑started `QUEUED` tasks to M11 (`DEFERRED` + `SHIFT_ENDED` `:88‑92`) and marks the DA `OFFLINE` (`:99`) — it does **not** force a DA holding parcels to empty their bag. The `RETURN_TO_HUB` machinery already exists but fires only on an individual delivery failure (`DaTaskServiceImpl.java:199‑230`, completes on the hub‑return dock scan, `DaDispatchController.java:254`). **Build:** at shift end, enumerate each DA's in‑hand/undelivered parcels → spawn `RETURN_TO_HUB` carry‑backs → gate `OFFLINE` until reconciled. Reuses existing plumbing. | 🔧 M | **Sid + Agniva** |

### E. Global ETA — **joint task** *(Sid + Agniva, carried from Discussion 3)*

| # | Item | State | Key anchors / what's missing | Type · Effort | Owner |
|---|---|---|---|---|---|
| E | **Global ETA** — a real ETA in place first, then notifications on top. **Needs a working session with Sid before Agniva picks it up.** | 🟡 (stub only) | Unchanged from Discussion‑3 (x). `EtaPort`'s only impl is `app/.../stubs/StubEtaAdapter` (fixed next‑day 14:00 / same‑city 20:00, `@Profile("!prod")`); no real M9 adapter. ETA set at BOOKED (`BookingServiceImpl.java:346`, `B2bBookingServiceImpl.java:336`) + AT_ORIGIN_HUB recalc. The delay‑notification path is **already built** (`ShipmentEtaServiceImpl.reviseEta:61` → `SHIPMENT_DELAYED` via `NotificationPort` `:120`, past `delayThresholdMinutes`) but only fires on a manual revise. **Joint scope:** real ETA computation (routing/airline — Sid) + a proactive recompute driving the built notification path (Agniva). ETA must be trustworthy *before* notifications layer on. | 🔧 M–L | **Sid + Agniva** |

---

## Joint discussion points  *(Sid + Agniva work the scenarios together — not assigned to one person, and not a build until we agree the shape)*

These are the bulk of Discussion 4: things to **think through and design together** before any code. The
"current state" column is context for the discussion, not a work order.

| # | Discussion topic (as noted) | Current state / context | Notes for the discussion |
|---|---|---|---|
| S1 | **Sorting module — bin/stand number on a large LED board (not just the operator screen), keep the voice‑over, and prove scan‑to‑display latency is ~instant.** *(Document as a potential future use.)* | Stand is assigned in `SortServiceImpl` (outbound `:66‑92`, inbound `:99‑140`) → `HubEventProducer.emitStandAssigned`, surfaced **only** in the operator Next.js tables (`apps/hub/.../page.tsx:152,170`; DTO `ParcelLocationResponse.java:12‑13`). No external LED/LCD board, **no voice‑over** (nothing to "keep" yet), **no latency instrumentation** anywhere. | Potential future capability. Discuss: board hardware vs. a full‑screen browser board view, audio announce, and how we'd measure/guarantee the scan→display p99 under the ½‑s failure threshold that killed the earlier software. |
| D1 | **Auxiliary‑time buffer in capacity planning — cut the shift by ~48 min (briefing/instructions/info‑exchange) → plan on ~432 min; the reduction doubles as the buffer.** | No 480‑min constant exists. Capacity today = `(endHour−startHour)×60 × targetUtilisation` = `(20−7)×60 × 0.70 = 546` engaged min, in 4 sites (`grid/.../BalancedBfsAssignmentServiceImpl.java:83‑84`, `CpSatAssignmentServiceImpl.java:138‑139`, `BfsAssignmentServiceImpl.java:71‑72`, `GridReplanServiceImpl.java:91‑92`); config `GridProperties.java:49‑59`. | The 13‑h planning **window** ≠ a per‑DA 8‑h/480‑min shift — decide what base the "432 = 480 − 48" refinement applies to, and reconcile with `disposition.dailyAllowanceMinutes=60` (a separate break lever). |
| D2 | **Utilisation formula — define a "productive utilisation" metric (excludes hub wait, load/unload) as a system‑generated report; justify the 70 % target with the math.** | No productive‑utilisation metric exists; adjacent pace‑only metrics (`DispatchMetricsService` scorecards, `stopsPerHour`). Raw dwell signal exists but isn't aggregated (`da_gps_ping` `V5_8` + `arrived_at` `V5_12`); the dwell/location‑stub model lives only in an unmerged worktree. Only written 70 % rationale is `CLAUDE.md:60`. | Amit wants the math and will check with Anubhav. Discuss the formula (engaged ÷ shift, exclusions), then whether the report is worth building (gated on location‑stub merging). |
| D4 | **Longer term: the system infers DA unavailability on its own rather than depending on punch‑in (assume ~90 % won't punch); GPS could detect whether a DA is moving.** | Signal exists (`da_gps_ping` breadcrumbs, `arrived_at`); the absent‑detection heartbeat flips idle→`ABSENT`, but no motion/idle inference raises a disposition on its own. | Discuss what "moving vs. stopped vs. on‑break" means, false‑positive tolerance, and whether a detector feeds the disposition sweep. Ties to D1/D2. |
| D5 | **"Why will DAs take breaks?" + incentive/penalty policy tied to break compliance and monthly ranking/payout.** | Nothing exists. | Break‑motivation rationale + a ranking/payout scheme — parked, to design later. |
| SUB | **End‑customer subscription / loyalty** — ~₹200/yr, ~5 % shipping discount or wallet cashback, merchant‑ID linked by mobile; a subscribed receiver → the merchant gets a discount; ratings / credit‑card‑style loyalty. | Nothing customer‑subscription/loyalty/cashback exists (only B2B team "membership"). Plug‑in path: discount via `RateCard.discountBps` (`PricingEngine.java:46‑49`), cashback via the customer wallet (`WalletService`, `wallet_transaction`), a new mobile‑keyed subscription entity (none today), UI in `apps/customer`. | Aspiration, not a spec. Discuss how we'd actually achieve it (economics, the mobile→merchant linkage, discount vs. cashback) before scoping any build. |

---

## Already shipped

- **C1 — Promotional banner / app‑download offer** on the no‑login accept/reject page. **🟢 Done** —
  the `PromoCard` ("Have you tried Godspeed?") on `apps/customer/app/d/[token]/page.tsx` (branch
  `feat/app-download-offer`). No further work this round.

---

## The split

Same seam as Discussions 1–3. **Build tasks** and **joint discussion points** are kept separate.

### SID — Operations / DA / Field / Station *(build)*
- **(R1) Reuse the same barcode** for the return shipment — carry `parcelId` to the child + a label‑mint reuse guard. **[🔧 S]**
- **(R4) Post‑sortation RTO policy** — bound physical recall to pre‑sortation; post‑sortation RTOs ride to dest then return. **[🔧🗣️ S]**

### AGNIVA — Merchant / Customer / Support / Platform *(build)*
- **(M1) Team‑member budget toggle** — unlimited/limited + fixed or % cap; refines Discussion‑3 (vi). **[🔧 M–L]**
- **(CASH) Driver cash ledger** — station‑manager‑confirmed deposits, DA‑app cash‑in‑hand + 60‑day trail + export, ~₹5k ceiling, independent station verification; under his Discussion‑3 (ix) COD umbrella. **Sid consults the DA cash‑custody leg.** **[🔧🗣️ L]**

### JOINT — build tasks *(Sid + Agniva)*
- **(E) Global ETA** — real ETA engine (Sid) + proactive notification trigger (Agniva). **Working session with Sid first.** **[🔧 M–L]**
- **(A1) Daily/shift close of the asset registry** — shift‑close job + closing record + console. **[🔧 M–L]**
- **(SC1) Shift‑closing return‑to‑hub** — force in‑hand parcels back at shift end via `RETURN_TO_HUB`. **[🔧 M]**

### JOINT — discussion points *(worked through together, not a build yet)*
- **(S1)** Sorting LED board + voice‑over + latency proof — document as potential future use.
- **(D1)** Auxiliary‑time / ~432‑min capacity buffer.
- **(D2)** Productive‑utilisation formula + report + the 70 % justification (Amit ↔ Anubhav).
- **(D4)** Infer DA unavailability from GPS/motion.
- **(D5)** Why DAs take breaks + incentive/penalty policy (parked).
- **(SUB)** End‑customer subscription / loyalty — how we'd achieve it.

**Balance.** Only four build items this round — two small RTO items (Sid), two heavier platform builds
(Agniva) — plus three joint builds (Global ETA, asset shift‑close, shift return‑to‑hub). Everything else
is a joint discussion the two of us resolve together before scoping.

---

## Dependencies & coordination
- **(R1)+(R4)** are one RTO workstream on top of Discussion‑3's built mid‑transit RTO — design the
  barcode reuse and the recall boundary together.
- **(CASH)** — Agniva owns end‑to‑end (DA app + station verification + ceiling + export + bank
  reconciliation under his (ix)); **Sid consults on the DA cash‑custody handoff leg**. The "~₹7 L
  pending reconciliation" makes the **independent station‑level verification** the priority slice.
- **(M1)** extends Agniva's Discussion‑3 (vi) — don't re‑model membership; add the budget dimension.
- **(A1)+(SC1)** are joint ops builds — asset custody close and the shift‑end bag‑return reconcile
  overlap (both fire at shift boundary), so design the shift‑close sequence once.
- **(E) Global ETA** — Sid's routing/airline engine lands first; Agniva's proactive trigger drives the
  already‑built `SHIPMENT_DELAYED` path. **Kickoff = a working session with Sid.**
- **(D2)** report is gated on the location‑stub / dwell work merging from its worktree.

## Verification (per build task, end‑to‑end)
- **(R1) barcode reuse:** an RTO child `<ref>_R` is dock‑received and its label shows the **original**
  barcode — the per‑hub counter is **not** bumped.
- **(R4) post‑sortation:** an RTO recorded after sortation rides to destination and returns from there;
  a pre‑sortation RTO is recalled/carried back.
- **(M1) member budget:** a limited member is blocked past their fixed/% cap while the account still has
  credit.
- **(CASH) cash ledger:** a station manager confirms a physical deposit → the DA app shows updated
  cash‑in‑hand and a 60‑day filterable/exportable trail → the ~₹5k ceiling forces a deposit before the
  next trip → an independent station count reconciles.
- **(E) Global ETA:** a real ETA is computed system‑wide → a live delay recomputes it → the built
  `SHIPMENT_DELAYED` notification fires automatically (no manual revise).
- **(A1) asset close:** at shift end a closing record reconciles all custody against expected holders
  and flags discrepancies.
- **(SC1) shift return‑to‑hub:** a DA still holding parcels at shift end gets `RETURN_TO_HUB` tasks and
  can't go `OFFLINE` until reconciled.

## Deliverable
This document (analysis + split) plus a **`Project Discussion 4/`** folder in Drive — Requirements +
Split doc, matching the earlier rounds.
