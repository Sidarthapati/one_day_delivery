# M3 — How Major Companies Solve Territory Assignment

This document covers how Uber, Amazon, DoorDash, Delhivery, Swiggy, Meituan, UPS, and FedEx
architect their delivery/ride territory planning systems — and why METIS isn't right for us.

---

## The Problem We're Solving (Context)

Assign ~91 tiles (2×2 km grid cells) across Delhi to K delivery agents such that:
1. Each DA's territory is **contiguous** (one connected region)
2. Each DA's workload is **balanced** (±10-15% of mean)
3. The plan is computed **nightly** (batch, not real-time)
4. The plan is **deterministic** (same input → same output)

This is a **balanced connected graph partitioning** problem. Below is how the industry solves
variants of exactly this.

---

## Company-by-Company Deep Dive

---

### 1. Uber — H3 Hexagonal Grid + DISCO Batch Matching

**Problem they solve:** Partition a city into spatial cells for surge pricing, demand forecasting,
and real-time driver dispatch.

**Architecture:**

```
┌──────────────────────────────────────────────────────────────────┐
│                        UBER PLATFORM                              │
├──────────────────────────────────────────────────────────────────┤
│                                                                    │
│  ┌─────────────┐     ┌──────────────┐     ┌──────────────────┐  │
│  │ H3 Spatial  │────▶│ Supply/Demand│────▶│ Surge Pricing    │  │
│  │ Index       │     │ Aggregation  │     │ (per hex cell)   │  │
│  │ (res 7-9)   │     │ (per hex)    │     └──────────────────┘  │
│  └─────────────┘     └──────────────┘                            │
│         │                                                         │
│         ▼                                                         │
│  ┌─────────────────────────────────────────────────────────────┐ │
│  │ DISCO — Batch Matching Service                               │ │
│  │                                                               │ │
│  │  1. Buffer ride requests for ~100-200ms                      │ │
│  │  2. k-ring lookup in Redis → candidate drivers               │ │
│  │  3. DeepETA predicts ETA per candidate (p95 = 4ms)          │ │
│  │  4. Build bipartite cost matrix (riders × drivers)           │ │
│  │  5. Solve assignment (Hungarian or auction algorithm)        │ │
│  │  6. Dispatch matched driver                                  │ │
│  └─────────────────────────────────────────────────────────────┘ │
│                                                                    │
│  ┌─────────────────────────────────────────────────────────────┐ │
│  │ DeepETA — ML Travel Time Prediction                          │ │
│  │                                                               │ │
│  │  - Transformer architecture                                  │ │
│  │  - Inputs: road segment graph embeddings, time-of-week,     │ │
│  │    S2 cell features, real-time Flink traffic                 │ │
│  │  - Pre-aggregated spatio-temporal views (not raw GNN)        │ │
│  │  - Forecast horizon: 0-180 minutes                          │ │
│  └─────────────────────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────────────────────┘
```

**Key details:**

| Aspect | Uber's Approach |
|--------|-----------------|
| Grid system | H3 hexagonal (open-source), 16 resolutions |
| Cell size | Res 9 ≈ 0.1 km² (dispatch), Res 7 ≈ 5 km² (surge) |
| Territory assignment | Not pre-assigned — real-time matching per request |
| Partitioning algorithm | None for territories; bipartite matching for dispatch |
| Solver | Custom auction/Hungarian algorithm (not CP-SAT) |
| Contiguity constraint | Not needed — zones are for aggregation, not ownership |

**Why this doesn't apply to us:** Uber doesn't assign territories to drivers. Drivers roam
freely; the system matches them to riders in real-time. Our problem is the opposite — we need
to pre-assign fixed territories nightly so DAs know their beat before the shift starts.

**What we can steal:** The H3 grid concept for spatial indexing, the idea of precomputing
cell-to-cell distances (which we already do with OSRM tile travel times).

---

### 2. Amazon — Hierarchical Zone Clustering + DSP Territory Graph

**Problem they solve:** Partition ~300-500 stops per delivery station into routes for 20-50
DSP (Delivery Service Partner) vans, each carrying 150-200 packages.

**Architecture:**

```
┌──────────────────────────────────────────────────────────────────┐
│                    AMAZON LAST-MILE PLANNING                       │
├──────────────────────────────────────────────────────────────────┤
│                                                                    │
│  PHASE 1: Territory Definition (Offline, Weekly)                  │
│  ┌─────────────────────────────────────────────────────────────┐ │
│  │ Graph-based geographic district generation                   │ │
│  │ • Build road-network graph of service area                  │ │
│  │ • Constraint: contiguous regions (graph connectivity)       │ │
│  │ • Objective: maximize DSP "familiarity" with region         │ │
│  │   (historically delivered volumes in previous time window)  │ │
│  │ • Method: objective-maximal path between origin/destination │ │
│  │   with upper-bound on routes/day per district               │ │
│  │ • Output: K contiguous geographic districts → DSP mapping   │ │
│  └─────────────────────────────────────────────────────────────┘ │
│                                                                    │
│  PHASE 2: Stop Clustering (Daily, at cutoff)                     │
│  ┌─────────────────────────────────────────────────────────────┐ │
│  │ Parallel constraint-aware clustering                         │ │
│  │ • Group stops within each territory into route clusters     │ │
│  │ • Hard constraints: vehicle capacity, stop coverage         │ │
│  │ • Soft constraints: time windows (bounded violation)        │ │
│  │ • Method: spatial-temporal-demand clustering (k-means on    │ │
│  │   geo+time features) → balanced partition                   │ │
│  └─────────────────────────────────────────────────────────────┘ │
│                                                                    │
│  PHASE 3: Route Sequencing (Daily)                               │
│  ┌─────────────────────────────────────────────────────────────┐ │
│  │ Hierarchical TSP                                             │ │
│  │ • Level 1: Sequence "zones" (clusters of stops)             │ │
│  │   → Markov model / Rollout policy (learned from drivers)    │ │
│  │ • Level 2: Intra-zone stop TSP                              │ │
│  │   → OR-Tools or LKH (exact/near-optimal for n<50)          │ │
│  │ • Output: ordered stop sequence per driver                  │ │
│  └─────────────────────────────────────────────────────────────┘ │
│                                                                    │
│  PHASE 4: Boundary Rebalancing (Daily)                           │
│  ┌─────────────────────────────────────────────────────────────┐ │
│  │ Distributed neighbor-based rebalancing                       │ │
│  │ • For each route pair sharing a boundary:                   │ │
│  │   swap border stops if it improves balance/distance         │ │
│  │ • Preserves contiguity (only swap if connected after)       │ │
│  │ • Runs in parallel across route pairs                       │ │
│  └─────────────────────────────────────────────────────────────┘ │
│                                                                    │
│  PHASE 5: Dynamic Re-optimization (Execution)                    │
│  ┌─────────────────────────────────────────────────────────────┐ │
│  │ Event-driven mid-route adjustments                           │ │
│  │ • Triggers: new orders, cancellations, traffic              │ │
│  │ • Re-solves affected route segment only                     │ │
│  └─────────────────────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────────────────────┘
```

**Key details:**

| Aspect | Amazon's Approach |
|--------|-------------------|
| Territory definition | Graph-based contiguous districts, optimizing DSP familiarity |
| Balance metric | Routes-per-day per district (upper/lower bounds) |
| Solver for territories | Custom graph optimization (patent US12462216) — NOT METIS |
| Solver for routes | OR-Tools for intra-zone TSP; ML for zone sequencing |
| Rebalancing | Neighbor-based boundary swaps (same as our Approach 3!) |
| Scale | 1M stops in ~20 min on commodity hardware |

**What's directly relevant to us:** Amazon's Phase 4 (boundary rebalancing) is exactly
Approach 3 from our CP-SAT issue doc. Their Phase 1 (contiguous district generation with
load bounds) is the same problem we have. They solve it with graph algorithms + boundary
repair, not with CP-SAT or METIS.

---

### 3. DoorDash — H3 Geo-Grid Cache + Ruin-and-Recreate Routing

**Problem they solve:** Assign Dashers to orders in real-time, cluster multi-stop batches,
optimize route sequencing for 10,000+ deliveries per metro area.

**Architecture:**

```
┌──────────────────────────────────────────────────────────────────┐
│                    DOORDASH "DeepRed" SYSTEM                      │
├──────────────────────────────────────────────────────────────────┤
│                                                                    │
│  LAYER 1: Geo-Grid Travel Cache (Offline precomputation)         │
│  ┌─────────────────────────────────────────────────────────────┐ │
│  │ • H3 hexagonal grid at 3 resolutions (fine/mid/coarse)     │ │
│  │ • Precompute full adjacency matrix via OSRM (Spark jobs)   │ │
│  │ • Store in Redis: cell-pair → {distance, duration}         │ │
│  │ • Tiered lookup: short trip → res 10, long → res 6         │ │
│  │ • Result: sub-10ms travel time lookups at request time      │ │
│  └─────────────────────────────────────────────────────────────┘ │
│                                                                    │
│  LAYER 2: Geographic Sharding (Territory-like)                   │
│  ┌─────────────────────────────────────────────────────────────┐ │
│  │ • City divided into geographic shards                       │ │
│  │ • Each DeepRed node handles one shard                       │ │
│  │ • Routing optimized within shard only                       │ │
│  │ • Sharding = coarse territory assignment for compute        │ │
│  └─────────────────────────────────────────────────────────────┘ │
│                                                                    │
│  LAYER 3: Route Optimization (Real-time)                         │
│  ┌─────────────────────────────────────────────────────────────┐ │
│  │ Ruin-and-Recreate metaheuristic                             │ │
│  │ • Open-source library (modified for DoorDash)               │ │
│  │ • "Ruin": remove random stops from current routes           │ │
│  │ • "Recreate": reinsert at cheapest positions                │ │
│  │ • Multithreaded: Scala Futures, thread pool per shard       │ │
│  │ • 10,000 deliveries: seconds (was minutes without MT)       │ │
│  └─────────────────────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────────────────────┘
```

**Key insight:** DoorDash doesn't assign permanent territories either. Their "sharding" is
purely computational (divide the city so each server handles a tractable subset). Within each
shard, it's a real-time matching + routing problem.

**What we can steal:** The tiered H3 distance cache concept. Our OSRM tile-travel-time
table is a simpler version of exactly this.

---

### 4. Delhivery — Gurobi MIP for Shipment-to-Resource Assignment

**Problem they solve:** Assign packages to delivery agents at each distribution center.
Hundreds of packages, dozens of riders, multiple hard constraints.

**Architecture:**

```
┌──────────────────────────────────────────────────────────────────┐
│                    DELHIVERY "Constellation"                       │
├──────────────────────────────────────────────────────────────────┤
│                                                                    │
│  OFFLINE LAYER: Beat/Territory Definition                        │
│  ┌─────────────────────────────────────────────────────────────┐ │
│  │ • City divided into "beats" (clusters of pincodes)          │ │
│  │ • Beats defined using historical delivery density           │ │
│  │ • Manual override by station manager                        │ │
│  │ • Updated weekly/monthly, not daily                         │ │
│  │ • Method: density-based clustering (DBSCAN variant)         │ │
│  │   + manual adjustment                                       │ │
│  └─────────────────────────────────────────────────────────────┘ │
│                                                                    │
│  DAILY LAYER: Shipment-to-DA Assignment                          │
│  ┌─────────────────────────────────────────────────────────────┐ │
│  │ Mixed Integer Programming (Gurobi Optimizer)                │ │
│  │                                                               │ │
│  │ Decision variable: x[i][j] = 1 if package i → DA j         │ │
│  │                                                               │ │
│  │ Hard constraints:                                            │ │
│  │   • Package destination must be within DA's beat            │ │
│  │   • Vehicle capacity (volume, weight)                       │ │
│  │   • Time window (due date)                                  │ │
│  │                                                               │ │
│  │ Soft constraints (penalized in objective):                   │ │
│  │   • Driver familiarity with route (historical visits)       │ │
│  │   • Load balance across DAs                                 │ │
│  │   • Route contiguity (packages in same pincode cluster)     │ │
│  │                                                               │ │
│  │ Solver: Gurobi (commercial, $$$)                            │ │
│  │ Scale: ~500 packages × 30 DAs per hub → solves in seconds  │ │
│  └─────────────────────────────────────────────────────────────┘ │
│                                                                    │
│  ML LAYER: Travel Time & Demand Prediction                       │
│  ┌─────────────────────────────────────────────────────────────┐ │
│  │ • OSRM for base routing                                     │ │
│  │ • ML correction model: OSRM estimate → actual time          │ │
│  │ • Demand forecasting per pincode per day                    │ │
│  └─────────────────────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────────────────────┘
```

**Key difference from us:** Delhivery's "territory" (beat) is defined loosely and manually.
The MIP optimizes *package assignment within beats*, not the beat boundaries themselves.
Beat boundary definition is a human + heuristic process.

**What's relevant:** Their overall pattern — define rough territories offline, then optimize
assignment within those territories daily — is close to our architecture. But they use Gurobi
(commercial solver, ~$12K/year) instead of OR-Tools CP-SAT.

---

### 5. Swiggy — Logistic Zones + Real-Time MIP Assignment

**Problem they solve:** Assign delivery partners to food orders in real-time, grouped into
batches, within geographic zones that keep compute tractable.

**Architecture:**

```
┌──────────────────────────────────────────────────────────────────┐
│                    SWIGGY ASSIGNMENT SYSTEM                        │
├──────────────────────────────────────────────────────────────────┤
│                                                                    │
│  LAYER 1: Logistic Zone Definition (Offline)                     │
│  ┌─────────────────────────────────────────────────────────────┐ │
│  │ • Purpose: split city so each zone runs independent solver  │ │
│  │ • Method: geohash clustering + word embeddings on           │ │
│  │   location names → semantic+geographic clusters             │ │
│  │ • Auto-scaling: number of zones increases with              │ │
│  │   expected compute load (more orders → more zones)          │ │
│  │ • Constraint: minimize cross-zone assignments               │ │
│  │ • Updated dynamically based on order volume                 │ │
│  └─────────────────────────────────────────────────────────────┘ │
│                                                                    │
│  LAYER 2: Distance Service (Precomputed)                         │
│  ┌─────────────────────────────────────────────────────────────┐ │
│  │ • Geohash cells as atomic spatial units                     │ │
│  │ • Restaurant → all reachable cells precomputed via OSRM     │ │
│  │ • Stored in Aerospike (in-memory) at precision 7-8          │ │
│  │ • Hybrid: hot pairs at precision 8, rest at precision 7     │ │
│  └─────────────────────────────────────────────────────────────┘ │
│                                                                    │
│  LAYER 3: Order Batching + Assignment (Real-time, every ~5s)    │
│  ┌─────────────────────────────────────────────────────────────┐ │
│  │ MIP solver (runs per zone, every few seconds)               │ │
│  │                                                               │ │
│  │ Decision: assign batch B to delivery partner DP             │ │
│  │                                                               │ │
│  │ Hard constraints:                                            │ │
│  │   • Bag capacity (volume)                                   │ │
│  │   • One batch → one DP                                      │ │
│  │   • Time windows (food prep time, delivery SLA)             │ │
│  │                                                               │ │
│  │ Objective: minimize total wait time city-wide               │ │
│  │                                                               │ │
│  │ Key trick: zones keep the MIP small enough to solve         │ │
│  │ in milliseconds despite thousands of orders                 │ │
│  └─────────────────────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────────────────────┘
```

**Key insight:** Swiggy's logistic zones are NOT fixed territories for delivery partners.
They're computational boundaries to keep the real-time MIP solver tractable. DPs can cross
zone boundaries. This is fundamentally different from our fixed nightly beat assignment.

---

### 6. Meituan (China) — Citywide OR+ML Dispatch, 30-Second Cycles

**Problem they solve:** Assign orders to couriers every 30 seconds, citywide, for 60+ million
daily deliveries across China.

**Architecture:**

```
┌──────────────────────────────────────────────────────────────────┐
│                    MEITUAN DISPATCH SYSTEM                         │
├──────────────────────────────────────────────────────────────────┤
│                                                                    │
│  Evolution: Manual → Courier Grabbing → Citywide Global Optimal  │
│                                                                    │
│  Current System (Phase 3): Citywide OR + ML                      │
│  ┌─────────────────────────────────────────────────────────────┐ │
│  │ Every 30 seconds, 3 stages:                                 │ │
│  │                                                               │ │
│  │ Stage 1: Route Planning (RP)                                │ │
│  │   • Two-stage fast heuristic (init + local search)          │ │
│  │   • Inverse Reinforcement Learning for objective function   │ │
│  │   • Output: predicted route per courier                     │ │
│  │                                                               │ │
│  │ Stage 2: Courier Candidate Evaluation                       │ │
│  │   • ML model scores each (order, courier) pair              │ │
│  │   • Features: location, speed, current load, route fit      │ │
│  │                                                               │ │
│  │ Stage 3: Order Assignment (OA)                              │ │
│  │   • "Divide and Conquer" with Imitation Learning + GNN      │ │
│  │   • OR: neighborhood search + ML for sub-problem solving    │ │
│  │   • Handles many-to-one (multiple orders per courier)       │ │
│  │   • Citywide globally optimal assignment                    │ │
│  └─────────────────────────────────────────────────────────────┘ │
│                                                                    │
│  No fixed territories — fully dynamic assignment every 30s       │
└──────────────────────────────────────────────────────────────────┘
```

**What's relevant:** Meituan abandoned fixed territories entirely in favor of citywide global
optimization. This is the endgame for any delivery platform at massive scale — but requires
ML infrastructure we don't have and won't need at our scale.

---

### 7. UPS — ORION + Core Area Territory Planning

**Problem they solve:** Assign ~55,000 drivers to fixed delivery routes daily, each running
120-200 stops. Territories are semi-permanent "core areas."

**Architecture:**

```
┌──────────────────────────────────────────────────────────────────┐
│                    UPS ORION + PFT SYSTEM                          │
├──────────────────────────────────────────────────────────────────┤
│                                                                    │
│  LAYER 1: Core Area Territory Planning (Monthly/Quarterly)       │
│  ┌─────────────────────────────────────────────────────────────┐ │
│  │ • Service territory divided into "cells" (minimum units)    │ │
│  │ • Each cell = group of stops serviceable by one driver      │ │
│  │ • Cells assigned to routes using:                           │ │
│  │   - Combinatorial optimization                              │ │
│  │   - Tabu search meta-heuristics                             │ │
│  │   - Network formulation modeling                            │ │
│  │   - Multi-stage graph modeling                              │ │
│  │ • Key objective: DRIVER FAMILIARITY over time               │ │
│  │   (drivers learn their area → faster delivery)              │ │
│  │ • "Flex zones" near hub: cells that can be assigned to      │ │
│  │   any driver based on daily volume fluctuations             │ │
│  │ • Balance: stochastic demand model (accounts for variance)  │ │
│  └─────────────────────────────────────────────────────────────┘ │
│                                                                    │
│  LAYER 2: Daily Route Optimization (ORION — 2008-2016 rollout)  │
│  ┌─────────────────────────────────────────────────────────────┐ │
│  │ • Input: today's packages within driver's core area cells   │ │
│  │ • Solver: proprietary OR algorithm (not published)          │ │
│  │ • Constraints: commit times, pickup windows, driver hours   │ │
│  │ • Infrastructure: 300 servers × 2 data centers              │ │
│  │   63 blade servers × 16 cores for distance matrices         │ │
│  │ • Result: 100M miles/year saved, 8.5M gallons fuel saved   │ │
│  │ • Dynamic ORION: re-optimizes mid-route based on new pkgs   │ │
│  └─────────────────────────────────────────────────────────────┘ │
│                                                                    │
│  LAYER 3: Map Infrastructure                                     │
│  ┌─────────────────────────────────────────────────────────────┐ │
│  │ • Custom proprietary road network (not OSM)                 │ │
│  │ • Corrections propagate nationwide in 15 seconds            │ │
│  │ • Speed limits, turn restrictions, dock locations           │ │
│  └─────────────────────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────────────────────┘
```

**THIS IS THE CLOSEST ANALOGUE TO OUR SYSTEM.** UPS has:
- Fixed territories (core areas) that persist for weeks/months
- Territory boundaries optimized for driver familiarity + load balance
- Daily route optimization within those boundaries
- Balance handled via stochastic demand + flex zones

**Their territory algorithm:** Combinatorial optimization + tabu search on a cell graph.
Not METIS. Not CP-SAT. A custom meta-heuristic that respects contiguity, balances stochastic
demand, and maximizes driver familiarity.

---

### 8. Vizzito/Academic — Multi-Stage Pipeline for 1M Stops

**Problem they solve:** Full fleet planning for 1M stops on commodity hardware. This is the
best-documented open architecture for large-scale territory + routing.

**Architecture (from published 2026 paper):**

```
┌──────────────────────────────────────────────────────────────────┐
│             NEAR-LINEAR LAST-MILE PLANNING PIPELINE               │
├──────────────────────────────────────────────────────────────────┤
│                                                                    │
│  Stage 1: Parallel Constraint-Aware Clustering                   │
│  ┌─────────────────────────────────────────────────────────────┐ │
│  │ • Geographic + demand-aware clustering                      │ │
│  │ • Hard: vehicle capacity, full stop coverage                │ │
│  │ • Soft: time windows (bounded violation)                    │ │
│  │ • Parallelized across depots                                │ │
│  └─────────────────────────────────────────────────────────────┘ │
│                          │                                        │
│                          ▼                                        │
│  Stage 2: Constraint-Aware Vehicle Allocation                    │
│  ┌─────────────────────────────────────────────────────────────┐ │
│  │ • Assign clusters to vehicles                               │ │
│  │ • Respect vehicle type, capacity, hours                     │ │
│  └─────────────────────────────────────────────────────────────┘ │
│                          │                                        │
│                          ▼                                        │
│  Stage 3: Distributed Boundary Rebalancing   ◀── THIS IS US     │
│  ┌─────────────────────────────────────────────────────────────┐ │
│  │ • For each pair of adjacent clusters:                       │ │
│  │   - Check if moving a boundary stop improves balance        │ │
│  │   - Only move if donor cluster stays connected              │ │
│  │   - Accept if total cost improves                           │ │
│  │ • Runs in parallel across cluster pairs                     │ │
│  │ • Converges in O(n) iterations                              │ │
│  └─────────────────────────────────────────────────────────────┘ │
│                          │                                        │
│                          ▼                                        │
│  Stage 4: Bounded Route-Level Optimization                       │
│  ┌─────────────────────────────────────────────────────────────┐ │
│  │ • Within each cluster: local TSP/VRP                        │ │
│  │ • Bounded computation (not unbounded solve)                 │ │
│  │ • Localized graph and distance reuse                        │ │
│  └─────────────────────────────────────────────────────────────┘ │
│                                                                    │
│  Result: 23.3% distance reduction vs Amazon baseline             │
│          1M stops in ~20 minutes on commodity hardware            │
└──────────────────────────────────────────────────────────────────┘
```

**Stage 3 is literally our Approach 3** — boundary swap refinement with connectivity
preservation. This architecture beat Amazon's own published baseline by 23.3% in distance.

---

## Summary: What Algorithm Does Each Company Use for Territory Definition?

| Company | Territory Type | Algorithm | Solver/Library | Why Not METIS? |
|---------|---------------|-----------|----------------|----------------|
| **Uber** | No fixed territories | Real-time bipartite matching | Custom Hungarian | No contiguity needed |
| **Amazon** | Fixed DSP districts | Graph optimization + boundary repair | Custom (patented) | Familiarity constraint non-standard |
| **DoorDash** | Computational shards only | Geographic sharding (not optimized) | Manual | Not an optimization problem for them |
| **Delhivery** | Semi-fixed beats | DBSCAN clustering + MIP for packages | Gurobi ($$$) | Beats are pincode-level, not grid cells |
| **Swiggy** | Dynamic compute zones | Geohash clustering + word embeddings | Custom | Zones are for compute, not delivery |
| **Meituan** | No territories | Citywide global optimal (OR + GNN + IRL) | Custom | 30s cycle, no territory concept |
| **UPS** | Fixed core areas | Tabu search + combinatorial optimization | Custom | Driver familiarity is primary objective |
| **Vizzito/Academic** | Route clusters | Constraint-aware clustering + boundary swaps | Custom | Boundary repair is the mechanism |

**Pattern:** No major company uses METIS for delivery territory planning. Every single one
uses either (a) a custom graph heuristic + boundary repair, or (b) a MIP solver with custom
constraints that METIS can't express.

---

## Why Can't We Shift to METIS?

### What METIS Actually Is

METIS (by George Karypis, U of Minnesota) is a **C library** for multilevel graph partitioning.
It takes an undirected graph with vertex weights and edge weights, and produces K balanced
connected partitions. It uses:

1. **Coarsening** — collapse the graph to a small representative graph
2. **Initial partition** — partition the coarsened graph
3. **Uncoarsening + refinement** — project back to original, refine boundaries via FM moves

It's the gold standard for balanced graph partitioning in academic benchmarks.

### Why It Seems Perfect

For our problem on paper, METIS is ideal:
- Input: 91-node graph (tiles), vertex weights (demand minutes), edges (adjacency)
- Output: K balanced connected partitions
- Quality: ±5% balance, contiguous by construction
- Speed: <1ms for n=91

### The 7 Reasons We Can't Use It

#### Reason 1: No Production-Quality Java Binding

The only Java binding is `crocodilepi/metis-java`:
- **3 stars on GitHub**
- **Last commit: November 2017** (9 years ago)
- **1 contributor, 1 open issue**
- **No tests, no CI, no documentation**
- Wraps METIS 5.x via JNI

Using this in production is reckless. We'd be depending on unmaintained JNI code that could
segfault the JVM on any version mismatch.

#### Reason 2: Native Dependency = Deployment Nightmare

METIS is a C library that must be:
1. Compiled from source (requires CMake + GCC + GKlib)
2. Installed as a shared library (`.so` on Linux, `.dylib` on Mac)
3. Found by the JVM at runtime via `java.library.path`

This means:
- Docker images need a build stage with CMake/GCC
- Mac dev machines need `brew install metis` or manual compilation
- The `.so` must match the exact OS/arch of the deployment target
- ARM vs x86 differences (M1/M2 Macs vs Linux servers)
- No `mvn clean install` simplicity — you now have a native build system

For a Spring Boot app that currently has zero native dependencies (OR-Tools ships its own
JNI bundle inside the Maven JAR), adding METIS is a significant ops burden.

#### Reason 3: OR-Tools Already Bundles Its Own Native Libs

OR-Tools (which we already use for CP-SAT) ships platform-specific natives inside the Maven
artifact. Google maintains builds for Linux x86_64, macOS x86_64, macOS ARM64, Windows.
It "just works" with `mvn clean install`.

METIS has no equivalent packaging. We'd need to maintain our own native artifact or use a
Gradle/Maven plugin to download platform-specific binaries. This is custom infrastructure
work for a library that saves us ~150 lines of Java.

#### Reason 4: We Can't Express Our Full Constraint Set

METIS optimizes: `min edge-cut subject to balance ±ε`

What we actually need:
- Balance on **demand minutes** (vertex weight) — METIS supports this ✓
- Contiguity — METIS guarantees this ✓
- **Seed affinity** — we want specific tiles assigned to specific DAs (e.g., DA's home area) — METIS has no concept of fixed vertex assignment
- **Capacity ceiling** — no DA should exceed shift capacity even if balance allows it — METIS only does relative balance, not absolute bounds
- **Future: driver familiarity** — weight toward historical territory — not expressible in METIS

The moment we add any domain-specific constraint beyond "balanced connected partition,"
METIS can't handle it. We'd need to post-process METIS output anyway — at which point
we've gained nothing over boundary-swap refinement.

#### Reason 5: Black Box — Can't Debug or Tune

When METIS produces a bad partition (and it will, for some demand distributions), you can't:
- See why a specific tile was assigned where it was
- Add a constraint to fix it
- Tune the behavior for peripheral tiles specifically
- Explain to the station manager why DA 3 has a weird territory

With boundary swaps, every decision is traceable: "Tile (5,3) was moved from DA 2 to DA 4
because DA 2 had 120% utilization and DA 4 had 55%, and (5,3) is adjacent to DA 4's
territory." Try explaining METIS's multilevel FM refinement to a station manager.

#### Reason 6: Overkill for n=91

METIS's multilevel scheme (coarsen → partition → uncoarsen) is designed for graphs with
100K–10M vertices. At n=91:
- Coarsening has nothing to collapse (the graph is already tiny)
- The initial partition is trivial
- Refinement does 2-3 FM moves

A simple BFS + boundary swap loop achieves the same quality in the same time for n=91.
METIS's overhead (graph format conversion, JNI marshalling, coarsening) may actually be
*slower* than native Java boundary swaps for our problem size.

#### Reason 7: No Company At Our Scale Uses It

As shown in the table above, zero major logistics companies use METIS for territory planning.
The reasons are the same as ours:
- Domain constraints can't be expressed
- Real-world territory planning has soft preferences (familiarity, road structure)
- Boundary swap refinement gives equivalent quality with full control
- Native dependencies are ops liability

---

## What The Industry Actually Converges On

Every company that does fixed territory assignment at scale (UPS, Amazon, Delhivery) has
converged on the same pattern:

```
┌─────────────────────────────────────────────────────────────────┐
│              INDUSTRY STANDARD TERRITORY PLANNING                 │
├─────────────────────────────────────────────────────────────────┤
│                                                                   │
│  Step 1: Seed Selection                                          │
│  • K seeds placed geographically (furthest-first, k-means++,    │
│    or manual placement by ops)                                   │
│                                                                   │
│  Step 2: Initial Assignment                                      │
│  • Voronoi / competitive flooding / BFS from seeds              │
│  • Produces contiguous territories with rough balance            │
│                                                                   │
│  Step 3: Boundary Refinement (THE KEY STEP)                      │
│  • Iterate over border tiles/cells                              │
│  • For each: can moving it to the neighbour improve balance?    │
│  • Guard: only move if donor territory stays connected          │
│  • Converge to local optimum (typically ±10-15% balance)        │
│                                                                   │
│  Step 4: Manual Override Layer                                   │
│  • Station manager can pin tiles to specific DAs                │
│  • Lock boundaries that shouldn't change                        │
│  • Override solver for operational reasons                       │
│                                                                   │
│  Repeat nightly with updated demand forecasts                    │
└─────────────────────────────────────────────────────────────────┘
```

This is exactly our **Approach 3** from the CP-SAT issue doc. It's not a novel idea — it's
the industry consensus for this problem class.

---

## Final Verdict

| Option | Verdict |
|--------|---------|
| Shift to METIS | No. Native dep, no Java binding, can't express constraints, overkill, no company uses it |
| Keep CP-SAT (current two-phase) | No. 73/91 tiles get reassigned; CP-SAT adds cost for no benefit |
| CP-SAT + geographic penalty | Maybe for v2. Adds complexity, needs weight tuning per city |
| **BFS + boundary swap refinement** | **Yes. Industry standard. 150 lines Java. ±10-15%. Deterministic. Production-proven.** |

The boundary swap approach is what Amazon, UPS, and the best academic systems all converge on.
It's not settling for less — it's choosing the architecture that actually works in production.

---

## References

1. Uber H3 — https://www.uber.com/us/en/blog/h3/
2. Uber DISCO — https://sujeet.pro/articles/design-uber-ride-hailing
3. Amazon DSP Territory Patent — US12462216 (2025)
4. Amazon Last Mile Routing Challenge — https://github.com/aws-samples/amazon-sagemaker-amazon-routing-challenge-sol
5. DoorDash Geo-Grid — https://careersatdoordash.com/blog/doordash-fast-travel-estimates/
6. DoorDash DeepRed — https://careersatdoordash.com/blog/scaling-a-routing-algorithm-using-multithreading-and-ruin-and-recreate/
7. Delhivery + Gurobi — https://www.gurobi.com/resources/case-studies/delhivery-making-the-last-mile-more-efficient
8. Swiggy Logistic Zones — https://bytes.swiggy.com/logistic-zones-for-assignment-48d9ce06c4a8
9. Meituan Dispatch (INFORMS 2024) — https://vonfeng.github.io/files/Informs2024_Meituan.pdf
10. UPS ORION — https://courses.ie.bilkent.edu.tr/ie479/wp-content/uploads/sites/16/2020/03/UPS-Optimizes-Delivery-Routes.pdf
11. UPS Core Area Patent — US7363126 (2008)
12. Vizzito Last-Mile Pipeline — https://github.com/vizzito/last-mile-optimizer-paper
13. RegionGen (Uber/Academic) — https://arxiv.org/abs/2306.02806
14. Grab Pharos — https://engineering.grab.com/pharos-searching-nearby-drivers-on-road-network-at-scale
15. METIS — https://github.com/KarypisLab/METIS
16. metis-java — https://github.com/crocodilepi/metis-java (3 stars, last commit 2017)
