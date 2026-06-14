# M6 — Far Corners, Loop Cadence, and Drop-and-Flag

How M6 keeps the one-day guarantee meaningful when a few meeting vertices sit too far
from the hub to fit a normal loop. Covers the problem, why the obvious fixes don't work,
the two changes shipped, and the levers for tuning it.

## 1. The problem

A nightly van plan routes vans over `{hub} ∪ meeting-vertices`. Each van repeats a **loop**
(hub → vertices → hub) several times a day; more loops = more **sweeps** = a stronger
one-day delivery guarantee. The target loop length is the **cycle** (≈2h, hard max 3h).

A handful of vertices are far from the hub. The **solo round-trip** for vertex *v* —
`hub→v` + dwell + `v→hub` + hub turnaround — can on its own exceed the cycle target.
Two bad things follow:

1. **One slow vertex throttles the whole city.** When the plan clocks every van to a single
   shared cadence (the slowest van's loop), that far vertex drags the entire fleet down to
   2 sweeps/day even though most vans could sweep 4–5×.
2. **Adding vans doesn't help.** The solver minimises `total travel + 100 × makespan`. Once
   one van must make a 222-min solo trip, the makespan floor is ~222 min regardless of fleet
   size — extra vans only add empty-leg travel without lowering the binding makespan. This is
   why a 25-van input was still only using 15: vans 16–25 couldn't cut the outlier-pinned
   makespan, so the solver correctly left them idle.

The far vertex is **geometry**, not a provisioning gap. No number of vans fixes it.

## 2. What does NOT solve it

- **More vans** — see above; capped by the outlier-pinned makespan.
- **More meeting vertices** — splitting territories raises the vertex count (Delhi resolves to
  ~91), which lets more vans be used in principle, but a far vertex's *own* round-trip is
  unchanged, so it still pins makespan.
- **Globally relaxing the cycle to the full operating window** — serves everything, but at the
  cost of the slow cadence (2 sweeps). This is the old behaviour and the thing we moved off of.

## 3. What we shipped

### 3a. Per-van cadence (remove the artificial throttle)

The plan assembler used to clock **every** van to `max(allSpans)` and one global loop count.
That uniformity was never an operational requirement — **M5 (dispatch) reads each van's stamped
meeting times into the DA's priority queue**, so the fleet does not need a shared cadence.

Now each van runs its **own** cadence = `max(itsSpan, cronFreeze)` and sweeps
`window / itsCadence` times. A near-hub van sweeps more; the slow van keeps its 2. Each
vertex's daily demand is re-split by its serving van's loop count so day-totals stay exact.

*Effect (Delhi, all vertices served):* loops/van went from a uniform 2 to a 2–4 spread.

### 3b. Drop-and-flag (stop letting outliers set the floor)

The solve now runs against the **cycle target** (not the window) and makes each vertex
**optional** via an OR-Tools disjunction with a prohibitive penalty (`1e9`, far above any
seconds-based cost). The solver therefore serves a vertex whenever it can and **defers**
(drops) only the vertices that *cannot* be reached within the cycle by any van. Dropped
vertices are returned in `SolveResult.droppedVertexIds` and surfaced in the plan `notes`.

With the outliers removed from the binding set, the makespan floor falls, so the solver
**uses the whole fleet** and every loop fits under the target.

*Effect (Delhi, cycle 180 min):*

| | Before (relax-to-window) | After (drop-and-flag) |
|---|---|---|
| Vans used | 15 of 25 | 25 |
| Sweeps/van/day | 2 | 4–5 |
| Realised cycle | 277 min | 163 min |
| Vertices served | 91 | 71 |
| Corners deferred | 0 | 20 |

## 4. The tradeoff and how to tune it

Drop-and-flag trades **coverage for sweep frequency**, and the **cycle target is the dial**:

- **Lower cycle** → more sweeps, more drops.
- **Higher cycle** → fewer drops, fewer sweeps.

At cycle 180 Delhi defers ~20 of 91 corners (~22%); raising the cycle toward ~220 cuts the
drop count sharply (the worst outlier sits ~222 min) while pulling sweeps back toward 3/day.
Pick the point where the deferred fraction is operationally acceptable.

**Config:** `routing.solver.drop-infeasible-vertices` (default `true`).
Set `false` to revert to serve-everything-on-a-relaxed-cycle. Cycle target is per city in
`city_fleet_config.cycle_time_max_minutes` (editable live via `PUT /routing/fleet/{cityId}`).

## 5. What "deferred" means operationally (open)

A deferred corner's DAs get **no meeting schedule** in the plan — it is *flagged*, not solved.
The plan is honest about which corners and how many; deciding what happens to them is the next
design question. Options, roughly in increasing effort:

1. **Dedicated long-loop van** — assign a van (or shared van) to the deferred corners on a
   relaxed, fewer-sweeps cadence. Keeps the main fleet tight; corners get a slower promise.
2. **Relaxed SLA for the long tail** — accept that the furthest corners are next-day or
   late-day, and price/communicate accordingly.
3. **Satellite mini-hub** — a forward staging point near a cluster of far corners shortens
   every solo round-trip into range. Highest effort, best structural fix.
4. **Territory reshaping (M3)** — if a corner is far only because its meeting vertex was placed
   poorly, feedback to M3's meeting-point selection may move it into range.

The current code makes the problem **visible and bounded**; (1)/(2) are the cheapest next steps
and are naturally an M5/M6 boundary (M6 emits the deferred set; M5 decides their handling).

## 6. Where it lives

- `OrToolsVanRouteSolver.solveInternal(..., allowDrops)` — disjunctions + dropped detection.
- `VanRouteSolver.solve(..., boolean allowDrops)` / `SolveResult.droppedVertexIds`.
- `RoutePlanningServiceImpl.plan()` — drop-mode wiring, served-set recommendation, notes.
- `RoutePlanAssembler` — per-van cadence + per-van demand split.
- `RoutingProperties.Solver.dropInfeasibleVertices` — the toggle.
