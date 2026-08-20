# Scan-flow field checklist

A field-test pass over the **physical barcode-scan lifecycle** (M8 append-only scan ledger) end to
end, so we catch scan-node gaps before the pilot — not during it. Complements the operational
[`PHASE-2-PHYSICAL-TEST-PLAN.md`](../PHASE-2-PHYSICAL-TEST-PLAN.md); this one focuses specifically on
**every scan node and its ledger/state/SLA side-effects**.

Run it per parcel, per lane. Tick each node only when *all three* effects are confirmed:
**(L)** an append-only `scan_ledger` row was written, **(S)** the shipment state advanced, and
**(SLA)** the SLA leg reacted (where applicable).

## Happy-path scan nodes (first-mile → hub → flight → hub → last-mile)

| # | Scan node | Actor / device | (L) Ledger row | (S) State transition | (SLA) Leg |
|---|-----------|----------------|----------------|----------------------|-----------|
| 1 | Pickup OTP verified | DA app | — (OTP, not a scan) | `PICKUP_ASSIGNED → PICKED_UP` | first-mile starts |
| 2 | Parcel scanned at pickup | DA app | `PICKED_UP` scan | picked-up confirmed | first-mile running |
| 3 | Van load (first-mile) | Van driver app | `VAN_LOAD` | manifest LOADED | — |
| 4 | Van → hub unload | Van driver app | `VAN_UNLOAD` | at origin hub | first-mile closes |
| 5 | Hub inbound dock scan | Hub console | inbound scan | `AT_ORIGIN_HUB` | hub-sort starts |
| 6 | Bagged for flight | Hub console | bag-add scan | assigned to flight bag | — |
| 7 | Shuttle hub→airport | Shuttle app | `ORIGIN_SHUTTLE_OUT` | in transit to airport | — |
| 8 | AWB / flight handoff | Airline (GHA) | flight-out scan | `IN_FLIGHT` | line-haul running |
| 9 | Destination airport in | Shuttle app | `DEST_SHUTTLE_IN` | landed | line-haul closes |
| 10 | Dest hub inbound | Hub console | inbound scan | `AT_DEST_HUB` | dest-sort starts |
| 11 | Sorted for delivery | Hub console | `PARCEL_SORTED_FOR_DELIVERY` | route/territory bag | — |
| 12 | Delivery van load | Van driver app | `VAN_LOAD` | manifest LOADED | last-mile starts |
| 13 | Van → DA handoff | Van driver app | `VAN_TO_DA` | out for delivery | last-mile running |
| 14 | Delivered scan | DA app | `DELIVERED` | `DELIVERED` (terminal) | last-mile closes GREEN |

## Edge cases each device must handle (verify, don't assume)

- [ ] **Duplicate scan** (same node twice): ledger stays append-only, state does **not** double-advance, no error to the operator that blocks them.
- [ ] **Out-of-order scan** (a later node before its predecessor): rejected/queued, not silently applied (C12 legal-predecessor rule — see routing `CustodyService`).
- [ ] **Wrong-hub / wrong-van scan**: flagged as an EXTRA / mis-route at reconcile, not accepted as normal.
- [ ] **Offline scan then sync**: scan captured offline is uploaded with its **original timestamp**, ordering preserved; no data loss on reconnect.
- [ ] **Unknown / damaged barcode**: clear operator error, parcel routed to the exception/locator flow (`ParcelLocatorService`), not dropped.
- [ ] **Cancelled parcel scanned**: node recognises the cancelled state and diverts (no forward movement).
- [ ] **Reconciliation at each handoff**: per-DA / per-stop scanned-set matches the manifest; discrepancies raise `HANDOFF_DISCREPANCY`.

## Ledger integrity (spot-check in DB after a run)

- [ ] `scan_ledger` rows are **append-only** — no UPDATE/DELETE on any scanned row (audit invariant).
- [ ] Every scan row carries actor, device/loop, timestamp, and parcel id; queryable by parcel.
- [ ] The scan sequence for one parcel reconstructs the full journey with no gaps at the nodes above.
- [ ] SLA legs opened/closed by scans match the leg catalog (no leg left RED that was actually served).

## Sign-off

Record: lane (e.g. DEL↔BOM), parcel ids, date, operator, and any node where (L)/(S)/(SLA) failed →
file against the owning module. A lane is "scan-flow verified" only when a full parcel completes
nodes 1–14 with all three effects green and the edge cases above were exercised at least once.
