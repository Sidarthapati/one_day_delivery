import { useEffect, useRef, useState } from 'react'
import ExecutionMap from './ExecutionMap.jsx'
import { hashDaColor } from '../utils/daColors.js'
import { buildRoutes } from '../utils/buildRoutes.js'
import { demandCount, seedDemand, replan, approveProposal } from '../api/gridApi.js'
import {
  m6Replan, m6Approve, getAllStops, getLive, getFleet, putFleet,
  runDay, runStop, runStatus, runEvents,
} from '../api/routingApi.js'
import { getDispatchState } from '../api/dispatchApi.js'

// Execution runs the whole chain for TODAY (telemetry resolves manifests by today's date), distinct
// from the planning tab's "tomorrow". One date for seed → M3 → M6 → run.
const _n = new Date()
const TODAY = `${_n.getFullYear()}-${String(_n.getMonth() + 1).padStart(2, '0')}-${String(_n.getDate()).padStart(2, '0')}`

const KIND_STYLE = {
  FEED: 'text-violet-700', BIND: 'text-blue-700', LOAD: 'text-slate-700',
  ARRIVE: 'text-emerald-700', LATE: 'text-red-600 font-semibold', SCAN: 'text-teal-700',
  RETURN: 'text-indigo-700', INFO: 'text-gray-500', ERROR: 'text-red-700 font-semibold',
}

export default function ExecutionView({ cityCode, cityId, center, nodes = [] }) {
  // Prepare inputs (seed → M3 territories → M6 fleet). Fleet inputs prefill from getFleet on mount.
  const [daCount, setDaCount] = useState(25)
  const [seedMin, setSeedMin] = useState(4)
  const [seedMax, setSeedMax] = useState(10)
  const [vansAvail, setVansAvail] = useState(6)
  const [capacity, setCapacity] = useState(120)
  const [cycleMax, setCycleMax] = useState(180)

  // Run inputs.
  const [deliveries, setDeliveries] = useState(40)
  const [collects, setCollects] = useState(20)
  const [speed, setSpeed] = useState(60)

  const [preparing, setPreparing] = useState(false)
  const [prepared, setPrepared] = useState(false)
  const [routes, setRoutes] = useState([])
  const [planInfo, setPlanInfo] = useState(null)
  const [error, setError] = useState(null)

  const [running, setRunning] = useState(false)
  const [stat, setStat] = useState(null)
  const [vans, setVans] = useState([])
  const [das, setDas] = useState([])   // M5 DAs at their cron vertices (handoff overlay)
  const [daTs, setDaTs] = useState({}) // per-DA progress 0→1 (collect → wait at vertex for van → return)
  const runStartRef = useRef(0)
  const metRef = useRef({})            // daId → ms when its van first reached the DA's vertex
  const [events, setEvents] = useState([])
  const lastSeq = useRef(0)
  const logRef = useRef(null)
  const nodesRef = useRef(nodes)
  nodesRef.current = nodes
  const vansRef = useRef([]); vansRef.current = vans
  const dasRef = useRef([]); dasRef.current = das

  // On city change: reset run state, prefill fleet inputs, and auto-restore an already-approved
  // plan for today (so the map + Run come back after a tab switch, and an existing plan is reused
  // without re-preparing).
  useEffect(() => {
    setPrepared(false); setRoutes([]); setPlanInfo(null); setError(null)
    setRunning(false); setStat(null); setVans([]); setDas([]); setEvents([]); lastSeq.current = 0
    let alive = true
    ;(async () => {
      try {
        const f = await getFleet(cityId)
        if (alive && f) {
          if (f.vansAvailable != null) setVansAvail(f.vansAvailable)
          if (f.capacityPackets != null) setCapacity(f.capacityPackets)
          if (f.cycleTimeMaxMinutes != null) setCycleMax(f.cycleTimeMaxMinutes)
        }
      } catch { /* no fleet config yet — keep defaults */ }
      // M5's DAs are independent of the M6 route geometry — load them first so the map reflects the
      // current shift even if buildRoutes (OSRM) is slow or there's no approved plan yet.
      if (alive) fetchDas()
      try {
        const stops = await getAllStops(cityId, TODAY)
        if (alive && stops && stops.length) {
          const r = await buildRoutes(stops, nodesRef.current)
          if (!alive) return
          setRoutes(r)
          setPlanInfo({ vansUsed: r.length, restored: true })
          setPrepared(true)
        }
      } catch { /* no approved plan for today yet */ }
    })()
    return () => { alive = false }
  }, [cityCode, cityId])

  // Keep the DA overlay in sync with M5 while idle (a shift loaded / pickups assigned in the Dispatch
  // tab shows up here within a few seconds). During a run, das is a fixed snapshot the animation drives.
  useEffect(() => {
    if (running) return
    const id = setInterval(() => fetchDas(), 4000)
    return () => clearInterval(id)
  }, [running, cityId])

  // M5 DA cron points (vertex coords + queue depth + the van each meets). Static during a run, so we
  // fetch on prepare / run-start rather than polling.
  async function fetchDas() {
    try {
      const s = await getDispatchState(cityId, TODAY)
      setDas((s.das || []).filter(d => d.cronVertexLat != null).map(d => {
        const vertex = [d.cronVertexLat, d.cronVertexLon]
        const home = d.lat != null ? [d.lat, d.lon] : vertex
        const pTasks = (d.queue || []).filter(t => t.taskType === 'PICKUP' && t.taskLat != null)
        const dTasks = (d.queue || []).filter(t => t.taskType === 'DELIVERY' && t.taskLat != null)
        return {
          daId: d.daId, vanId: d.vanId, home, vertex,
          pickups: pTasks.map(t => [t.taskLat, t.taskLon]),
          deliveries: dTasks.map(t => [t.taskLat, t.taskLon]),
          pickupIds: pTasks.map(t => t.shipmentId),      // DA → van (collected, fly out)
          deliveryIds: dTasks.map(t => t.shipmentId),    // van → DA (inbound, to deliver)
          queueDepth: (d.queue || []).length, cronTime: d.cronMeetingTime, distanceKm: d.distanceToCronKm,
        }
      }))
    } catch { setDas([]) }
  }

  async function prepare() {
    setPreparing(true); setError(null)
    try {
      // Seed with the chosen min/max so the inputs actually take effect (seed upserts for today).
      await seedDemand(cityCode, TODAY, { minMinutes: seedMin, maxMinutes: seedMax })
      const prop = await replan(cityCode, daCount, TODAY)
      await approveProposal(prop.id)            // → APPROVED, the state M6 reads (no activate on this branch)
      // Persist the fleet config the route solve reads, then replan.
      await putFleet(cityId, {
        vans_available: vansAvail,
        capacity_packets: capacity,
        cycle_time_max_minutes: cycleMax,
      })
      const p = await m6Replan(cityId, TODAY)
      if (!p.vansUsed) throw new Error(`No feasible routes: ${p.notes || 'under-provisioned'}`)
      await m6Approve(p.planId)
      const stops = await getAllStops(cityId, TODAY)
      setRoutes(await buildRoutes(stops, nodes))
      setPlanInfo({ vansUsed: p.vansUsed, planId: p.planId })
      setPrepared(true)
      fetchDas()
    } catch (e) {
      setError(e.message)
    } finally {
      setPreparing(false)
    }
  }

  async function run() {
    setError(null); setEvents([]); setVans([]); lastSeq.current = 0
    try {
      await fetchDas()   // refresh queue depths (what M5 will hand to the vans)
      runStartRef.current = Date.now(); metRef.current = {}; setDaTs({})   // DAs start in their territory
      const s = await runDay(cityId, { deliveries, collects, speed })
      setStat(s); setRunning(true)
    } catch (e) {
      setError(e.message)
    }
  }

  async function stop() {
    try { await runStop() } catch { /* ignore */ }
  }

  // Poll live status, events and run status while a run is active.
  useEffect(() => {
    if (!running) return
    let alive = true
    const tick = async () => {
      try {
        const [live, evs, st] = await Promise.all([
          getLive(cityId), runEvents(lastSeq.current), runStatus(),
        ])
        if (!alive) return
        setVans(live)
        if (evs.length) {
          lastSeq.current = evs[evs.length - 1].seq
          setEvents(prev => [...prev, ...evs].slice(-300))
        }
        setStat(st)
        if (['DONE', 'ERROR', 'STOPPED'].includes(st.phase)) {
          setRunning(false)
          if (st.error) setError(st.error)
        }
      } catch { /* transient — keep polling */ }
    }
    const id = setInterval(tick, 1000)
    tick()
    return () => { alive = false; clearInterval(id) }
  }, [running, cityId])

  // Drive each DA's travel *by its van*: collect to the vertex (~18 s), then WAIT at the vertex until its
  // van actually arrives there, then return to territory (~14 s). This keeps the DA next to its van at the
  // handoff instead of finishing on a fixed clock while the van is elsewhere. (t bands match daState:
  // 0–0.4 collect, 0.4–0.55 at vertex, 0.55–1 return.)
  useEffect(() => {
    if (!running) return
    const OUT_MS = 18000, RETURN_MS = 14000, MAX_WAIT_MS = 45000
    const dist = (a, b) => Math.hypot(a[0] - b[0], a[1] - b[1])
    const id = setInterval(() => {
      const now = Date.now()
      const elapsed = now - runStartRef.current
      const next = {}
      for (const d of dasRef.current) {
        // Has this DA's van reached its vertex yet? Record the first time it does.
        if (!metRef.current[d.daId] && d.vertex) {
          const v = vansRef.current.find(v => v.vanId === d.vanId && v.lastLat != null)
          if (v && dist([v.lastLat, v.lastLon], d.vertex) < 0.013) metRef.current[d.daId] = now
        }
        if (elapsed < OUT_MS) {
          next[d.daId] = (elapsed / OUT_MS) * 0.4                 // travelling home → pickups → vertex
        } else {
          // Reached the vertex; wait there until the van arrives (or a max-wait fallback), then return.
          let met = metRef.current[d.daId]
          if (!met && elapsed - OUT_MS > MAX_WAIT_MS) met = runStartRef.current + OUT_MS + MAX_WAIT_MS
          next[d.daId] = met == null ? 0.47 : Math.min(1, 0.55 + ((now - met) / RETURN_MS) * 0.45)
        }
      }
      setDaTs(next)
    }, 150)
    return () => clearInterval(id)
  }, [running])

  // Auto-scroll the feed.
  useEffect(() => {
    if (logRef.current) logRef.current.scrollTop = logRef.current.scrollHeight
  }, [events])

  const lateVans = vans.filter(v => v.minutesLate != null && v.minutesLate >= 10).length

  return (
    <div className="flex flex-1 overflow-hidden">
      <div className="flex-1 relative">
        <ExecutionMap center={center} routes={routes} nodes={nodes} vans={vans} das={das} daTs={daTs} vanColor={hashDaColor} />
        {!prepared && (
          <div className="absolute inset-0 flex items-center justify-center bg-white/60 z-[500]">
            <div className="text-center text-gray-600">
              <div className="text-lg font-semibold mb-1">No plan loaded for today</div>
              <div className="text-sm">Click <strong>Prepare today's plan</strong> to seed demand,
                build &amp; approve the M6 route plan, then run the day.</div>
            </div>
          </div>
        )}
      </div>

      {/* Sidebar */}
      <div className="w-96 bg-white border-l border-gray-200 flex flex-col overflow-hidden">
        <div className="p-3 border-b border-gray-100 space-y-2">
          <div className="text-sm font-bold text-gray-800">M6 Execution — run the day</div>

          {/* Prepare config: territories + demand seed + fleet */}
          <div className="grid grid-cols-3 gap-2 text-xs text-gray-600">
            <label className="flex flex-col gap-0.5">DAs
              <input type="number" value={daCount} min={1} onChange={e => setDaCount(+e.target.value)}
                className="border rounded px-1 py-0.5" disabled={preparing || running} /></label>
            <label className="flex flex-col gap-0.5">demand min
              <input type="number" value={seedMin} min={1} onChange={e => setSeedMin(+e.target.value)}
                className="border rounded px-1 py-0.5" disabled={preparing || running} /></label>
            <label className="flex flex-col gap-0.5">demand max
              <input type="number" value={seedMax} min={1} onChange={e => setSeedMax(+e.target.value)}
                className="border rounded px-1 py-0.5" disabled={preparing || running} /></label>
            <label className="flex flex-col gap-0.5">vans
              <input type="number" value={vansAvail} min={1} onChange={e => setVansAvail(+e.target.value)}
                className="border rounded px-1 py-0.5" disabled={preparing || running} /></label>
            <label className="flex flex-col gap-0.5">capacity
              <input type="number" value={capacity} min={1} onChange={e => setCapacity(+e.target.value)}
                className="border rounded px-1 py-0.5" disabled={preparing || running} /></label>
            <label className="flex flex-col gap-0.5">cycle max
              <input type="number" value={cycleMax} min={1} onChange={e => setCycleMax(+e.target.value)}
                className="border rounded px-1 py-0.5" disabled={preparing || running} /></label>
          </div>
          <button onClick={prepare} disabled={preparing || running}
            className="w-full text-sm px-3 py-1 rounded bg-slate-700 text-white disabled:opacity-50">
            {preparing ? 'Preparing…' : prepared ? 'Re-prepare today\'s plan' : 'Prepare today\'s plan'}
          </button>
          {planInfo && <div className="text-xs text-emerald-700">
            {planInfo.restored ? 'Existing plan loaded' : 'Plan approved'} · {planInfo.vansUsed} vans · {routes.length} loops</div>}
          {das.length > 0 && (() => {
            const m5P = das.reduce((a, d) => a + (d.pickups?.length || 0), 0)
            const m5D = das.reduce((a, d) => a + (d.deliveries?.length || 0), 0)
            return <div className="text-xs text-emerald-700">👤 {das.length} DAs ·
              {' '}M5 drives the run: <b>{m5P} pickups</b>, <b>{m5D} drops</b>
              {(m5P + m5D) === 0 && <span className="text-gray-400"> (assign in Dispatch to populate)</span>}</div>
          })()}

          {/* Run config — ▼ deliveries (hub→DA) · ▲ pickups (DA→van). Used only as a fallback when no M5
              shift is loaded; otherwise the counts above (from M5's queue) drive the run. */}
          <div className="flex items-center gap-3 text-xs text-gray-600 pt-1">
            <label className="flex items-center gap-1" title="deliveries (fallback if no M5 shift)">▼ drops
              <input type="number" value={deliveries} min={0}
              onChange={e => setDeliveries(+e.target.value)} className="w-12 border rounded px-1 py-0.5"
              disabled={running} /></label>
            <label className="flex items-center gap-1" title="pickups (fallback if no M5 shift)">▲ pickups
              <input type="number" value={collects} min={0}
              onChange={e => setCollects(+e.target.value)} className="w-12 border rounded px-1 py-0.5"
              disabled={running} /></label>
            <label className="flex items-center gap-1">speed<input type="number" value={speed} min={1}
              onChange={e => setSpeed(+e.target.value)} className="w-14 border rounded px-1 py-0.5"
              disabled={running} /></label>
          </div>
          <div className="flex gap-2">
            <button onClick={run} disabled={!prepared || running}
              className="flex-1 text-sm px-3 py-1.5 rounded bg-blue-600 text-white disabled:opacity-50">
              {running ? 'Running…' : '▶ Run the day'}
            </button>
            <button onClick={stop} disabled={!running}
              className="text-sm px-3 py-1.5 rounded border border-gray-300 disabled:opacity-40">Stop</button>
          </div>
          {error && <div className="text-xs text-red-600">{error}</div>}
        </div>

        {/* Stats */}
        {stat && (
          <div className="grid grid-cols-4 gap-px bg-gray-100 text-center text-xs border-b border-gray-100">
            {[['published', stat.published], ['bound', stat.bound],
              ['delivered', stat.delivered], ['collected', stat.collected]].map(([k, v]) => (
              <div key={k} className="bg-white py-1.5">
                <div className="font-bold text-gray-800 text-sm">{v}</div>
                <div className="text-gray-400">{k}</div>
              </div>
            ))}
          </div>
        )}
        {stat && (
          <div className="px-3 py-1 text-xs flex items-center gap-3 border-b border-gray-100">
            <span className="font-semibold text-gray-700">{stat.phase}</span>
            <span className="text-gray-500">{vans.length} vans live</span>
            {lateVans > 0 && <span className="text-red-600 font-semibold">{lateVans} running late</span>}
          </div>
        )}

        {/* RabbitMQ feed */}
        <div className="px-3 py-1 text-xs font-semibold text-gray-500 border-b border-gray-100">RabbitMQ feed ▸ live</div>
        <div ref={logRef} className="flex-1 overflow-y-auto px-3 py-2 font-mono text-[11px] leading-relaxed">
          {events.length === 0 && <div className="text-gray-400">No events yet — run the day to see parcels flow.</div>}
          {events.map(e => (
            <div key={e.seq} className={KIND_STYLE[e.kind] || 'text-gray-600'}>
              <span className="text-gray-300">{e.kind.padEnd(6)}</span> {e.message}
            </div>
          ))}
        </div>
      </div>
    </div>
  )
}
