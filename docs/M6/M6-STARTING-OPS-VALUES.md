# M6 Execution — realistic starting-operations values

What to put in the **Execution → "run the day"** prepare panel (and why) when launching a brand-new
one-day intercity operation in a **single city** (e.g. Delhi), with low initial volume. These are
demo/launch defaults, not hard limits — they exist to keep the plan internally consistent and the cost
floor honest (DA utilisation ~70%, cron-meeting is a hard constraint).

## The fields

| Field | What it means |
|---|---|
| **DAs** | Delivery associates on shift for the city. Drives M3 territory partition (1 DA : N hexes). |
| **demand min / demand max** | Per-hex demand **seed**, in service-minutes. M3 sizes territories and M6 plans loops off this. Higher → bigger territories / more DAs needed. |
| **vans** | Shuttle vans available for the city's hub↔meeting-vertex loops. |
| **capacity** | Parcels a van carries per loop. |
| **cycle max** | Max minutes for one van loop (hub → meeting vertices → hub). Must fit inside the cron/flight cadence. |

## Launch (Phase 1 — ~200–400 parcels/day, one city, lean crew)

| Field | Value | Why |
|---|---|---|
| **DAs** | **10** | At ~70% utilisation a DA does ~22–28 stops/shift; 10 DAs cover ~250 parcels (pickups + drops). |
| **demand min** | **3** | Low per-hex service-minute seed matching low real volume. |
| **demand max** | **8** | Keeps territories from over-sizing on a thin book. |
| **vans** | **4** | ~2–3 DA meeting points per van per loop, several loops/day. |
| **capacity** | **120** | Realistic small cargo van (Tata Ace / pickup) per loop. 1000 is a full truck — too high for launch. |
| **cycle max** | **150** | A loop that still leaves SLA headroom before the hub cron cutoff. |

> ⚠️ The panel in the screenshot showed **DAs 0 / vans 0** — with those, Prepare produces no roster and
> no loops (nothing to plan). Set DAs and vans to non-zero before Prepare.

## Ramp (Phase 2 — ~800–1500 parcels/day)

| Field | Value |
|---|---|
| DAs | 18–25 |
| demand min / max | 5 / 12 |
| vans | 6–8 |
| capacity | 150 |
| cycle max | 180 |

## Keep it internally consistent

- **DAs × stops-per-DA ≈ daily parcels.** ~25 stops/DA at 70% utilisation. Don't add DAs beyond demand —
  idle DAs are the cost floor, not speed.
- **vans × capacity × loops-per-day ≥ daily parcels.** 4 vans × 120 × ~2 loops = ~960 parcel-moves/day —
  comfortably above a 200–400 launch book.
- **cycle max ≤ time between cron meetings.** A loop that overruns the cron makes the meeting infeasible
  (the M5 hard constraint) and parcels miss the flight.
- **demand seed should track real volume.** Inflated demand minutes oversize territories → more DAs than
  the book justifies.

## Spread seed (so the demo involves many DAs, not one hex)

The pincode-centroid bulk upload collapses every Delhi destination to **one hex → one DA**. For a
realistic multi-DA picture use the **Execution → "spread seed"** buttons (one real shipment per DA
territory, lat/lon-placed):

1. **Prepare today's plan** (territories + loops must exist first).
2. **🌐 Spread pickups** (count ≈ DAs) → books real pickups, one per DA territory → M5 auto-assigns each
   to a **different** DA. Then **Auto-verify pickups** → **Run**.
3. **🌐 Spread drops** (count ≈ DAs) → books real drops, one per DA territory → **📦 Dispatch drops** →
   set ▼ drops fallback 0 → **Run** → **🏠 Auto-verify deliveries** (recipient OTP) → Delivered.

Both seed under the demo customer (`b2c@demo.in`), so they appear in Your Bookings and complete the
symmetric pickup ⇄ delivery story with real shipments and real OTPs.
