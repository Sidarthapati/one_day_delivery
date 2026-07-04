import { useEffect, useRef, useState } from 'react'
import ExecutionMap, { daState } from './ExecutionMap.jsx'
import { hashDaColor } from '../utils/daColors.js'
import { buildRoutes } from '../utils/buildRoutes.js'
import { demandCount, seedDemand, replan, approveProposal } from '../api/gridApi.js'
import {
  m6Replan, m6Approve, getAllStops, getLive, getFleet, putFleet, getPlan,
  runDay, runStop, runStatus, runEvents, runReturnToHub,
} from '../api/routingApi.js'
import { getDispatchState, autoVerifyPickups, dispatchDrops, autoVerifyDeliveries, seedSpread, loadShift, resetDispatch, pickupsToHub, pickupsToVan, clearBookings, getAmqpTap, pingDaGps } from '../api/dispatchApi.js'
import { CITIES } from '../cities.js'

// Execution runs the whole chain for TODAY (telemetry resolves manifests by today's date), distinct
// from the planning tab's "tomorrow". One date for seed → M3 → M6 → run.
const _n = new Date()
const TODAY = `${_n.getFullYear()}-${String(_n.getMonth() + 1).padStart(2, '0')}-${String(_n.getDate()).padStart(2, '0')}`

// Per-event-kind styling for the live RabbitMQ feed: an emoji + a coloured badge chip + a matching
// message colour, so different events are visually distinct and the feed reads at a glance.
const KIND_BADGE = {
  PUBLISH:{ icon: '📤', badge: 'bg-fuchsia-100 text-fuchsia-700', text: 'text-fuchsia-800' },
  CONSUME:{ icon: '📥', badge: 'bg-cyan-100 text-cyan-700',       text: 'text-cyan-800' },
  FEED:   { icon: '📡', badge: 'bg-violet-100 text-violet-700',   text: 'text-violet-800' },
  BIND:   { icon: '🔗', badge: 'bg-blue-100 text-blue-700',       text: 'text-blue-800' },
  LOAD:   { icon: '📦', badge: 'bg-amber-100 text-amber-700',     text: 'text-amber-800' },
  ARRIVE: { icon: '📍', badge: 'bg-emerald-100 text-emerald-700', text: 'text-emerald-800' },
  LATE:   { icon: '⏰', badge: 'bg-red-100 text-red-700',         text: 'text-red-700 font-semibold' },
  SCAN:   { icon: '✅', badge: 'bg-teal-100 text-teal-700',       text: 'text-teal-800' },
  RETURN: { icon: '↩️', badge: 'bg-indigo-100 text-indigo-700',   text: 'text-indigo-800' },
  INFO:   { icon: '·',  badge: 'bg-gray-100 text-gray-500',       text: 'text-gray-500' },
  ERROR:  { icon: '✕',  badge: 'bg-red-200 text-red-800',         text: 'text-red-700 font-semibold' },
}
const DEFAULT_BADGE = { icon: '•', badge: 'bg-gray-100 text-gray-500', text: 'text-gray-600' }

export default function ExecutionView({ cityCode, cityId, center, nodes = [] }) {
  // Prepare inputs (seed → M3 territories → M6 fleet). Fleet inputs prefill from getFleet on mount.
  const [daCount, setDaCount] = useState(25)
  const [seedMin, setSeedMin] = useState(4)
  const [seedMax, setSeedMax] = useState(10)
  const [vansAvail, setVansAvail] = useState(6)
  const [capacity, setCapacity] = useState(120)
  const [cycleMax, setCycleMax] = useState(180)

  // The run carries ONLY real M5 parcels (verified pickups + dispatched drops) — no synthetic fallback.
  const [speed, setSpeed] = useState(60)

  const [preparing, setPreparing] = useState(false)
  const [approving, setApproving] = useState(false)
  const [prepared, setPrepared] = useState(false)
  const [routes, setRoutes] = useState([])
  const [planInfo, setPlanInfo] = useState(null)
  const [error, setError] = useState(null)

  const [verifying, setVerifying] = useState(false)
  const [verifyMsg, setVerifyMsg] = useState(null)
  const [hubMsg, setHubMsg] = useState(null)
  const [deliverMsg, setDeliverMsg] = useState(null)
  // Content zoom for the two side panels (the map has its own zoom) — bump text up/down during a demo.
  const [feedZoom, setFeedZoom] = useState(1)
  const [sideZoom, setSideZoom] = useState(1)
  const zoomStep = (set) => (d) => set(z => Math.min(1.8, Math.max(0.7, +(z + d).toFixed(2))))

  // Draggable panel widths — the map (flex-1) absorbs whatever the side panels give up. w-80=320, w-96=384.
  const [feedW, setFeedW] = useState(320)
  const [sideW, setSideW] = useState(384)
  function startDrag(e, set, startW, side) {
    e.preventDefault()
    const startX = e.clientX
    const move = ev => {
      const dx = (ev.clientX - startX) * (side === 'left' ? 1 : -1)
      set(Math.max(220, Math.min(760, startW + dx)))
      window.dispatchEvent(new Event('resize'))   // Leaflet (trackResize) re-fits the middle map
    }
    const up = () => { window.removeEventListener('mousemove', move); window.removeEventListener('mouseup', up) }
    window.addEventListener('mousemove', move)
    window.addEventListener('mouseup', up)
  }
  const gutterCls = 'w-1 shrink-0 cursor-col-resize bg-gray-200 hover:bg-blue-400 transition-colors'
  const [toHubBusy, setToHubBusy] = useState(false)
  const [droppingDrops, setDroppingDrops] = useState(false)
  const [verifyingDrops, setVerifyingDrops] = useState(false)
  const [dropMsg, setDropMsg] = useState(null)
  const [spreadCount, setSpreadCount] = useState(12)
  const [seeding, setSeeding] = useState(false)
  const [seedMsg, setSeedMsg] = useState(null)
  const [shiftBusy, setShiftBusy] = useState(null)   // 'load' | 'reset' | null
  const [shiftMsg, setShiftMsg] = useState(null)
  const [clearing, setClearing] = useState(false)
  const [clearMsg, setClearMsg] = useState(null)
  const [running, setRunning] = useState(false)
  const [stat, setStat] = useState(null)
  const [vans, setVans] = useState([])
  const [das, setDas] = useState([])   // M5 DAs at their cron vertices (handoff overlay)
  const [m5State, setM5State] = useState(null)   // raw M5 dispatch state, for the ops tracker box
  const [daTs, setDaTs] = useState({}) // per-DA progress 0→1 (collect → wait at vertex for van → return)
  const runStartRef = useRef(0)
  const handoffDoneRef = useRef(false) // guard: fire the end-of-run "handed to van" once per run
  const metRef = useRef({})            // daId → ms when its van first reached the DA's vertex
  const [events, setEvents] = useState([])
  const lastSeq = useRef(0)
  const tapSeq = useRef(0)        // cursor into the real RabbitMQ publish/consume tap
  const logRef = useRef(null)
  const nodesRef = useRef(nodes)
  nodesRef.current = nodes
  const vansRef = useRef([]); vansRef.current = vans
  const dasRef = useRef([]); dasRef.current = das
  const daTsRef = useRef({}); daTsRef.current = daTs

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
  // Poll at 10s and skip while the tab is hidden — the state() read hits the remote DB per DA, so we
  // keep the Render chatter (and its timeout risk) down without losing liveness when you're looking.
  useEffect(() => {
    if (running) return
    const id = setInterval(() => { if (!document.hidden) fetchDas() }, 10000)
    return () => clearInterval(id)
  }, [running, cityId])

  // M5 DA cron points (vertex coords + queue depth + the van each meets). Static during a run, so we
  // fetch on prepare / run-start rather than polling.
  // In-flight guard: the state() read can be slow against the remote DB; without this, a poll tick
  // fires a new request before the previous returns and they pile up (the "too many requests" flood).
  const dasBusy = useRef(false)
  async function fetchDas() {
    if (dasBusy.current) return
    dasBusy.current = true
    try {
      const s = await getDispatchState(cityId, TODAY)
      setM5State(s)
      setDas((s.das || []).filter(d => d.cronVertexLat != null).map(d => {
        const vertex = [d.cronVertexLat, d.cronVertexLon]
        const home = d.lat != null ? [d.lat, d.lon] : vertex
        const pTasks = (d.queue || []).filter(t => t.taskType === 'PICKUP' && t.taskLat != null)
        const dTasks = (d.queue || []).filter(t => t.taskType === 'DELIVERY' && t.taskLat != null)
        return {
          daId: d.daId, vanId: d.vanId, home, vertex,
          pickups: pTasks.map(t => [t.taskLat, t.taskLon]),
          deliveries: dTasks.map(t => [t.taskLat, t.taskLon]),
          // Only OTP-verified (IN_PROGRESS) pickups are actually collected by the van; QUEUED ones are
          // assigned but un-verified and the run leaves them behind unless Auto-verify flips them.
          pickupsVerified: pTasks.filter(t => t.status === 'IN_PROGRESS').length,
          pickupIds: pTasks.map(t => t.shipmentId),      // DA → van (collected, fly out)
          deliveryIds: dTasks.map(t => t.shipmentId),    // van → DA (inbound, to deliver)
          pickupRefs: pTasks.map(t => ({ ref: t.ref || ('#' + String(t.shipmentId).slice(0, 4)), state: t.m4State })),
          deliveryRefs: dTasks.map(t => ({ ref: t.ref || ('#' + String(t.shipmentId).slice(0, 4)), state: t.m4State })),
          queueDepth: (d.queue || []).length, cronTime: d.cronMeetingTime, distanceKm: d.distanceToCronKm,
        }
      }))
    } catch { setDas([]) } finally { dasBusy.current = false }
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
      const built = await buildRoutes(stops, nodes)
      setRoutes(built)
      const stopCount = built.reduce((a, r) => a + (r.markers?.length || 0), 0)
      setPlanInfo({ vansUsed: p.vansUsed, planId: p.planId, das: daCount, loops: built.length,
        stops: stopCount, fresh: true })
      setPrepared(true)
      fetchDas()
    } catch (e) {
      setError(e.message)
    } finally {
      setPreparing(false)
    }
  }

  // Approve today's PROPOSED plan without re-solving (Prepare auto-approves; this is the resilience
  // path if a plan was left PROPOSED, or to re-confirm before Run). run-day needs an APPROVED plan.
  async function approvePlan() {
    setApproving(true); setError(null)
    try {
      const plan = await getPlan(cityId, TODAY)
      if (!plan || !plan.planId) {
        setError('No plan for today — click "Prepare today\'s plan" first (it builds + auto-approves).')
        return
      }
      const wasApproved = plan.status === 'APPROVED'
      if (!wasApproved) await m6Approve(plan.planId)
      const stops = await getAllStops(cityId, TODAY)
      const built = await buildRoutes(stops, nodes)
      setRoutes(built)
      const stopCount = built.reduce((a, r) => a + (r.markers?.length || 0), 0)
      setPlanInfo({ vansUsed: plan.vansUsed, planId: plan.planId, restored: true,
        loops: built.length, stops: stopCount, wasApproved })
      setPrepared(true)
      fetchDas()
    } catch (e) {
      setError(e.message)
    } finally {
      setApproving(false)
    }
  }

  // Simulate every DA's door OTP handshake so the van carries only OTP-verified pickups.
  async function autoVerify() {
    setVerifying(true); setVerifyMsg(null)
    try {
      const r = await autoVerifyPickups(cityId, TODAY)
      setVerifyMsg('✅ ' + (r.message || (r.verified + ' verified')))
      await fetchDas()
    } catch (e) {
      setVerifyMsg('✕ ' + e.message)
    } finally {
      setVerifying(false)
    }
  }

  // Close the first mile: picked-up parcels → origin hub (AT_ORIGIN_HUB), the pickup-side mirror of
  // "Dispatch drops". Run after "Run the day". Refreshes the DA overlay (completed pickups leave the queue).
  async function pickupsToHubHandler() {
    setToHubBusy(true); setHubMsg(null)
    try {
      const r = await pickupsToHub(CITIES[cityCode]?.label || cityCode, TODAY)
      // Option 1: drive the vans back to the hub (the return leg the run deferred) and animate it —
      // poll van telemetry for a few seconds so the marker moves from its last stop to the hub.
      try {
        await runReturnToHub(cityId, TODAY)
        const t0 = Date.now()
        const anim = setInterval(async () => {
          try { setVans(await getLive(cityId)) } catch { /* transient */ }
          if (Date.now() - t0 > 6000) clearInterval(anim)
        }, 500)
      } catch { /* no plan/vans to drive home — the state transition already ran */ }
      setHubMsg('🏭 ' + (r.message || (r.advanced + ' reached the hub')))
      await fetchDas()
    } catch (e) {
      setHubMsg('✕ ' + e.message)
    } finally {
      setToHubBusy(false)
    }
  }

  // M5 shift controls, brought into Execution so the whole demo path lives in one tab (no Dispatch
  // hop). Load shift = clock today's planned DAs onto their cron meetings (queues start empty). Reset =
  // clear the in-memory roster + tasks (start over). The detailed ops board stays on the Dispatch tab.
  async function loadShiftHandler() {
    setShiftBusy('load'); setShiftMsg(null)
    try {
      const r = await loadShift(cityId, TODAY)
      const n = r?.das?.length ?? r?.loaded ?? r?.count
      setShiftMsg(`👷 Shift loaded — ${n ?? '?'} DAs on duty, each seated at its cron van meeting (queues start empty). Next → 🌐 Spread pickups / drops.`)
      await fetchDas()
    } catch (e) {
      setShiftMsg('✕ ' + e.message)
    } finally {
      setShiftBusy(null)
    }
  }

  async function resetHandler() {
    if (!window.confirm('Reset the shift? Clears the in-memory DA roster + all queued tasks for today.')) return
    setShiftBusy('reset'); setShiftMsg(null)
    try {
      await resetDispatch(cityId, TODAY)
      setShiftMsg('♻️ shift reset — roster + queues cleared')
      setDas([])
    } catch (e) {
      setShiftMsg('✕ ' + e.message)
    } finally {
      setShiftBusy(null)
    }
  }

  // Wipe the demo customer's entire booking history (+ child rows) so a demo starts from scratch.
  // Destructive but scoped to b2c@demo.in only. Follow with Reset to drop the in-memory M5 roster.
  async function clearBookingsHandler() {
    if (!window.confirm('Delete ALL b2c@demo.in bookings and their tasks/OTPs/payments? This cannot be undone.')) return
    setClearing(true); setClearMsg(null)
    try {
      const r = await clearBookings()
      setClearMsg('🧹 ' + (r?.message || `${r?.shipments ?? 0} shipments cleared`))
      await resetDispatch(cityId, TODAY).catch(() => {})   // also drop the in-memory roster/queues
      setDas([])
    } catch (e) {
      setClearMsg('✕ ' + e.message)
    } finally {
      setClearing(false)
    }
  }

  // Spread seed: book `count` real shipments across many DA territories (one per DA) so the demo
  // involves multiple DAs, not one hex. Refreshes the DA overlay so the spread is visible.
  async function seedSpreadHandler(kind) {
    setSeeding(true); setSeedMsg(null)
    try {
      const r = await seedSpread(cityId, CITIES[cityCode]?.label || cityCode, kind, spreadCount, TODAY)
      setSeedMsg('🌐 ' + (r.message || (r.booked + ' booked')))
      await fetchDas()
    } catch (e) {
      setSeedMsg('✕ ' + e.message)
    } finally {
      setSeeding(false)
    }
  }

  // Last-mile: push booked drop shipments (e.g. uploaded drop20) into out-for-delivery + bind to loops,
  // then (after Run carries them hub→van→DA) simulate every recipient's door OTP → Delivered.
  async function dispatchDropsHandler() {
    setDroppingDrops(true); setDropMsg(null)
    try {
      const r = await dispatchDrops(cityId, CITIES[cityCode]?.label || cityCode, TODAY)
      setDropMsg('📦 ' + (r.message || (r.dispatched + ' dispatched')))
    } catch (e) {
      setDropMsg('✕ ' + e.message)
    } finally {
      setDroppingDrops(false)
    }
  }

  async function autoVerifyDeliveriesHandler() {
    setVerifyingDrops(true); setDeliverMsg(null)
    try {
      const r = await autoVerifyDeliveries(CITIES[cityCode]?.label || cityCode, TODAY)
      setDeliverMsg('🏠 ' + (r.message || (r.delivered + ' delivered')))
    } catch (e) {
      setDeliverMsg('✕ ' + e.message)
    } finally {
      setVerifyingDrops(false)
    }
  }

  async function run() {
    // The bus feed is always-live (see the tap poller below) — don't clear it; Run just adds its own
    // narration on top of whatever publish/consume traffic is already streaming.
    setError(null); setVans([]); lastSeq.current = 0
    try {
      await fetchDas()   // refresh queue depths (what M5 will hand to the vans)
      runStartRef.current = Date.now(); metRef.current = {}; handoffDoneRef.current = false; setDaTs({})
      const s = await runDay(cityId, { deliveries: 0, collects: 0, speed })
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
        // Run narration only — the publish/consume tap is streamed by the always-live poller below.
        if (evs.length) {
          lastSeq.current = evs[evs.length - 1].seq
          setEvents(prev => [...prev, ...evs].slice(-300))
        }
        setStat(st)
        if (['DONE', 'ERROR', 'STOPPED'].includes(st.phase)) {
          setRunning(false)
          if (st.error) setError(st.error)
          // End of the day: the vans met the DAs, so the collected pickups are now ON the van —
          // advance M4 PICKED_UP → HANDED_TO_PICKUP_VAN (visible on :8080 / M4). Fire once per run.
          if (st.phase === 'DONE' && !handoffDoneRef.current) {
            handoffDoneRef.current = true
            pickupsToVan(CITIES[cityCode]?.label || cityCode, TODAY)
              .then(r => { setHubMsg('🤝 ' + (r.message || (r.advanced + ' handed to the pickup van'))); fetchDas() })
              .catch(() => {})
          }
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

  // Always-live RabbitMQ tap: stream real PUBLISH/CONSUME from mount (not just during a run) so EVERY
  // action's bus traffic — spread, auto-verify, dispatch drops, prepare, and the run itself — shows in
  // the left feed. Starts at the current head so it streams from "now" (no historical-ring dump).
  useEffect(() => {
    let alive = true
    let synced = false
    const poll = async () => {
      try {
        if (!synced) { tapSeq.current = (await getAmqpTap(0)).head; synced = true; return }
        const tap = await getAmqpTap(tapSeq.current)
        if (!alive) return
        if (tap?.entries?.length) {
          const tapEvs = tap.entries.map(e => ({
            seq: `tap-${e.seq}`, kind: e.dir,
            message: (e.dir === 'PUBLISH'
              ? `${e.exchange} ▸ ${e.type}${e.routingKey && e.routingKey !== '#' ? ` (${e.routingKey})` : ''}`
              : `${e.queue} ◂ ${e.type}`)
              + (e.detail ? ` — ${e.detail}` : ''),
          }))
          setEvents(prev => [...prev, ...tapEvs].slice(-300))
        }
        if (tap?.head != null) tapSeq.current = tap.head
      } catch { /* transient — keep polling */ }
    }
    poll()
    const id = setInterval(poll, 1500)
    return () => { alive = false; clearInterval(id) }
  }, [])

  // While a run animates, mirror each DA's on-screen position to M5 as a REAL GPS heartbeat every ~3s
  // (POST /api/demo/dispatch/gps → updateGps → da_status), so the telemetry table tracks the moving DAs.
  useEffect(() => {
    if (!running) return
    const tick = () => {
      const ts = daTsRef.current
      const pings = dasRef.current
        .map(d => {
          const pos = daState(d, ts[d.daId] ?? 0).pos
          // snake_case da_id — the backend Jackson is SNAKE_CASE, so { daId } would bind to null.
          return (pos && pos[0] != null) ? { da_id: d.daId, lat: pos[0], lon: pos[1] } : null
        })
        .filter(Boolean)
      if (pings.length) pingDaGps(pings).catch(() => {})
    }
    tick()
    const id = setInterval(tick, 3000)
    return () => clearInterval(id)
  }, [running])

  // Auto-scroll the feed.
  useEffect(() => {
    if (logRef.current) logRef.current.scrollTop = logRef.current.scrollHeight
  }, [events])

  const lateVans = vans.filter(v => v.minutesLate != null && v.minutesLate >= 10).length

  return (
    <div className="flex flex-1 overflow-hidden">
      {/* RabbitMQ feed — left rail (drag its right edge to resize) */}
      <div style={{ width: feedW }} className="shrink-0 bg-white border-r border-gray-200 flex flex-col overflow-hidden">
        <div className="px-3 py-2 text-xs font-semibold text-gray-500 border-b border-gray-100 flex items-center justify-between gap-2">
          <span>RabbitMQ feed ▸ live</span>
          <div className="flex items-center gap-2">
            {events.length > 0 && <span className="text-gray-400 font-normal">{events.length}</span>}
            <div className="flex border border-gray-300 rounded overflow-hidden">
              <button onClick={() => zoomStep(setFeedZoom)(-0.1)} className="px-1.5 leading-none text-gray-600 hover:bg-gray-100" title="Zoom feed out">−</button>
              <button onClick={() => zoomStep(setFeedZoom)(0.1)} className="px-1.5 leading-none text-gray-600 hover:bg-gray-100 border-l border-gray-300" title="Zoom feed in">+</button>
            </div>
            <button onClick={() => setEvents([])} disabled={events.length === 0}
              className="text-[11px] px-1.5 py-0.5 rounded border border-gray-300 text-gray-500 hover:bg-gray-50 disabled:opacity-40 font-normal"
              title="Clear the displayed events (the feed keeps streaming new ones)">
              Clear
            </button>
          </div>
        </div>
        <div ref={logRef} style={{ zoom: feedZoom }} className="flex-1 overflow-y-auto px-3 py-2 font-mono text-[11px] leading-relaxed">
          {events.length === 0 && <div className="text-gray-400">Live — any action that hits the bus (spread, auto-verify, dispatch, run) appears here.</div>}
          {events.map(e => {
            const s = KIND_BADGE[e.kind] || DEFAULT_BADGE
            return (
              <div key={e.seq} className="flex items-start gap-1.5 py-0.5 hover:bg-gray-50 rounded">
                <span className={`inline-flex items-center gap-0.5 px-1.5 rounded text-[10px] font-semibold shrink-0 ${s.badge}`}>
                  {s.icon} {e.kind}
                </span>
                <span className={s.text}>{e.message}</span>
              </div>
            )
          })}
        </div>
      </div>

      <div onMouseDown={e => startDrag(e, setFeedW, feedW, 'left')} className={gutterCls}
        title="Drag to resize the feed / map" />

      <div className="flex-1 relative">
        <ExecutionMap center={center} routes={routes} nodes={nodes} vans={vans} das={das} daTs={daTs} vanColor={hashDaColor} onRefresh={fetchDas} />

        {/* Ops tracker — live M5/M6 counts, refreshed manually with the ⟳ button. */}
        {prepared && (() => {
          const daList = m5State?.das || []
          const absent = daList.filter(d => d.status === 'ABSENT').length
          const offline = daList.filter(d => d.status === 'OFFLINE').length
          const pickTasks = daList.flatMap(d => (d.queue || []).filter(t => t.taskType === 'PICKUP'))
          const dropTasks = daList.flatMap(d => (d.queue || []).filter(t => t.taskType === 'DELIVERY'))
          const pickups = pickTasks.length
          const drops = dropTasks.length
          // "With DA" = the parcel is physically in the DA's hands right now:
          //  · pickup IN_PROGRESS  → DA collected it after the sender's door OTP (first-mile)
          //  · delivery IN_PROGRESS → DA took it off the van, out for delivery (last-mile)
          const daHoldsPickup = pickTasks.filter(t => t.status === 'IN_PROGRESS').length
          const daHoldsDrop = dropTasks.filter(t => t.status === 'IN_PROGRESS').length
          const Metric = ({ icon, label, value, sub, cls, title }) => (
            <div className="flex flex-col leading-tight" title={title}>
              <span className="text-[10px] text-gray-400">{icon} {label}</span>
              <span className={`text-sm font-bold ${cls || 'text-gray-800'}`}>{value}
                {sub != null && <span className="text-[10px] font-normal text-gray-400"> · {sub}</span>}</span>
            </div>
          )
          return (
            <div className="absolute top-2 left-14 z-[600] bg-white/95 backdrop-blur rounded-lg shadow-md border border-gray-200 px-3 py-2">
              <div className="flex items-center justify-between gap-4 mb-1.5">
                <span className="font-bold text-gray-600 uppercase tracking-wide text-[10px]">📊 Ops tracker</span>
                <button onClick={fetchDas} disabled={running}
                  className="text-[10px] px-1.5 py-0.5 rounded border border-gray-300 text-gray-600 hover:bg-gray-50 disabled:opacity-40"
                  title="Refresh the tracker counts from M5/M6 now (manual)">⟳ Refresh</button>
              </div>
              <div className="grid grid-cols-4 gap-x-4 gap-y-1.5">
                <Metric icon="👤" label="DAs" value={daList.length} sub={absent > 0 ? `${absent} absent` : (offline > 0 ? `${offline} offline` : null)} />
                <Metric icon="🚐" label="Vans" value={routes.length} />
                <Metric icon="🔒" label="Cron-locked" value={m5State?.summary?.cronLocked ?? 0} cls={(m5State?.summary?.cronLocked ?? 0) > 0 ? 'text-amber-700' : 'text-gray-800'} />
                <Metric icon="⛔" label="Deferred" value={m5State?.summary?.deferred ?? 0} cls={(m5State?.summary?.deferred ?? 0) > 0 ? 'text-red-600' : 'text-gray-800'} />
                <Metric icon="📥" label="First-mile" value={pickups} cls="text-blue-700" />
                <Metric icon="✋" label="DA has (pickup)" value={daHoldsPickup} cls="text-blue-700"
                  title="Pickups the DA collected after the sender's door OTP (PICKED_UP)" />
                <Metric icon="📤" label="Last-mile" value={drops} cls="text-indigo-700" />
                <Metric icon="🏠" label="DA has (drop)" value={daHoldsDrop} cls="text-indigo-700"
                  title="Drops the DA took off the van, out for delivery (DROP_COLLECTED)" />
              </div>
            </div>
          )
        })()}

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

      <div onMouseDown={e => startDrag(e, setSideW, sideW, 'right')} className={gutterCls}
        title="Drag to resize the map / controls" />

      {/* Sidebar — scrolls on its own hover (overflow-y-auto) so the controls are reachable when they
          exceed the viewport, independent of the map and feed. Drag its left edge to resize. */}
      <div style={{ width: sideW }} className="shrink-0 bg-white border-l border-gray-200 flex flex-col overflow-y-auto">
        <div style={{ zoom: sideZoom }} className="p-3 border-b border-gray-100 space-y-2">
          <div className="flex items-center justify-between">
            <div className="text-sm font-bold text-gray-800">M6 Execution — run the day</div>
            <div className="flex border border-gray-300 rounded overflow-hidden">
              <button onClick={() => zoomStep(setSideZoom)(-0.1)} className="px-1.5 leading-none text-gray-600 hover:bg-gray-100" title="Zoom controls out">−</button>
              <button onClick={() => zoomStep(setSideZoom)(0.1)} className="px-1.5 leading-none text-gray-600 hover:bg-gray-100 border-l border-gray-300" title="Zoom controls in">+</button>
            </div>
          </div>

          {/* Demo runbook — the numbered path through the buttons below, with what each step really does. */}
          <details className="text-xs border border-amber-200 bg-amber-50/60 rounded px-2 py-1.5">
            <summary className="cursor-pointer font-semibold text-amber-800">📋 Demo runbook (click to expand)</summary>
            <ol className="list-decimal ml-4 mt-1.5 space-y-1.5 text-gray-700">
              <li><b>Prepare today's plan</b> — seeds demand, M3 carves DA territories, M6 solves + auto-approves today's van routes.</li>
              <li><b>👷 Load shift</b> — clocks the planned DAs on duty (seated at their van meeting points). Until this, nothing can be assigned to anyone.</li>
              <li><b>🌐 Spread pickups</b> — books real Delhi→Mumbai shipments, one per DA territory. Publishes <code>ShipmentCreated</code> → M5 consumes it and auto-assigns each pickup. Watch the feed.</li>
              <li><b>🔑 Auto-verify pickups</b> — simulates each sender's door OTP → <code>PICKED_UP</code>; only verified parcels ride the van.</li>
              <li><b>🌐 Spread drops</b> — books real Mumbai→Delhi shipments (sender self-drops at the origin hub). <i>No DA yet</i> — a drop's DA is assigned only at the destination hub.</li>
              <li><b>📦 Dispatch drops</b> — stands in for the unbuilt hub/flight (M7–M9): walks each drop to the dest hub, publishes <code>ParcelSortedForDelivery</code> (M6 binds a van loop), and at <code>HANDED_TO_DROP_VAN</code> M5 assigns the DA <i>event-driven</i>. Ends out-for-delivery with the recipient OTP minted.</li>
              <li><b>▶ Run the day</b> — vans drive their loops: collect the verified pickups (↑), hand drops to DAs (↓). When the van meets the DA (end of run) the pickups become <code>HANDED_TO_PICKUP_VAN</code> in M4 — refresh your bookings on :8080 to see it. Fallback ▼/▲ stay 0 so only real parcels ride.</li>
              <li><b>🏭 Complete first-mile</b> — closes the pickup lane: <code>HANDED_TO_PICKUP_VAN → AT_ORIGIN_HUB</code> (van drives to the hub; stands in for the hub receiving scan).</li>
              <li><b>🏠 Auto-verify deliveries</b> — closes the drop lane: recipient OTP → <code>DROPPED</code>. On :8080 (login <code>b2c@demo.in</code>) the bookings show Delivered.</li>
            </ol>
            <div className="mt-1.5 text-gray-500">Left rail shows every real RabbitMQ publish/consume as it happens. Reset a demo with 🧹 Clear bookings + ♻️ Reset.</div>
          </details>

          {/* ── ① SETUP — plan + shift ───────────────────────────────────────────── */}
          <div className="text-[11px] font-bold uppercase tracking-wider text-slate-500 pt-1">① Setup — plan &amp; shift</div>
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
          <button onClick={prepare} disabled={preparing || approving || running}
            className="w-full text-sm px-3 py-1 rounded bg-slate-700 text-white disabled:opacity-50">
            {preparing ? 'Preparing…' : prepared ? 'Re-prepare today\'s plan' : 'Prepare today\'s plan'}
          </button>
          <button onClick={approvePlan} disabled={preparing || approving || running}
            className="w-full text-xs px-3 py-1 rounded border border-slate-300 text-slate-600 hover:bg-slate-50 disabled:opacity-50"
            title="Approve today's route plan (Prepare already auto-approves; use this only if a plan is left PROPOSED). run-day needs an APPROVED plan.">
            {approving ? 'Approving…' : '✓ Approve today\'s plan'}
          </button>
          {planInfo && <div className="text-xs text-emerald-800 bg-emerald-50 border border-emerald-100 rounded px-2 py-1.5 leading-relaxed">
            <div className="font-semibold">✅ {planInfo.restored
              ? (planInfo.wasApproved ? 'Existing plan loaded' : 'Plan approved')
              : 'Plan built & approved'}</div>
            <div>M3 carved <b>{planInfo.das ?? daCount}</b> DA territories · M6 solved <b>{planInfo.vansUsed}</b> van
              {' '}route{planInfo.vansUsed === 1 ? '' : 's'} over <b>{planInfo.loops ?? routes.length}</b> loop
              {(planInfo.loops ?? routes.length) === 1 ? '' : 's'}{planInfo.stops ? <> · <b>{planInfo.stops}</b> cron stops</> : null}.</div>
            <div className="text-emerald-600">Next → 👷 Load shift to clock the DAs on duty.</div>
          </div>}
          <div className="flex gap-2">
            <button onClick={loadShiftHandler} disabled={shiftBusy != null || running}
              className="flex-1 text-sm px-3 py-1.5 rounded border border-slate-400 text-slate-700 hover:bg-slate-50 disabled:opacity-50"
              title="Clock today's planned DAs onto the shift, each seated on its cron van meeting. Queues start empty.">
              {shiftBusy === 'load' ? 'Loading…' : '👷 Load shift'}
            </button>
            <button onClick={resetHandler} disabled={shiftBusy != null || running}
              className="text-sm px-3 py-1.5 rounded border border-rose-300 text-rose-600 hover:bg-rose-50 disabled:opacity-50"
              title="Clear the in-memory DA roster + all queued tasks for today (start over).">
              {shiftBusy === 'reset' ? 'Resetting…' : '♻️ Reset'}
            </button>
          </div>
          <button onClick={clearBookingsHandler} disabled={clearing || running || shiftBusy != null}
            className="w-full text-xs px-3 py-1.5 rounded border border-rose-300 text-rose-700 bg-rose-50/40 hover:bg-rose-100 disabled:opacity-50"
            title="Delete every b2c@demo.in shipment + its tasks/OTPs/payments from the DB — clean slate for a fresh demo. Then Prepare again.">
            {clearing ? 'Clearing…' : '🧹 Clear all b2c@demo.in bookings (DB wipe)'}
          </button>
          {clearMsg && <div className="text-[11px] text-rose-700">{clearMsg}</div>}
          {shiftMsg && <div className="text-[11px] text-slate-600">{shiftMsg}</div>}

          {/* ── ② SEED THE DAY — book real shipments ─────────────────────────────── */}
          <div className="text-[11px] font-bold uppercase tracking-wider text-slate-500 pt-2 mt-1 border-t border-gray-200">② Seed the day — book real shipments</div>
          <div className="flex items-center justify-between">
            <span className="text-[11px] text-gray-400">one shipment per DA territory (wraps for extras)</span>
            <label className="flex items-center gap-1 text-xs text-gray-600" title="how many shipments to book; > #territories wraps onto the same DAs">count
              <input type="number" value={spreadCount} min={1} max={60}
                onChange={e => setSpreadCount(+e.target.value)} className="w-12 border rounded px-1 py-0.5"
                disabled={seeding || running} /></label>
          </div>
          <div className="flex gap-2">
            <button onClick={() => seedSpreadHandler('PICKUP')} disabled={seeding || running}
              className="flex-1 text-sm px-2 py-1.5 rounded border border-sky-300 text-sky-700 hover:bg-sky-50 disabled:opacity-50"
              title="Book real first-mile DA pickups spread across DA territories — M5 auto-assigns each to a different DA (real pickup leg, OTP at the door).">
              {seeding ? '…' : '🌐 Spread pickups'}
            </button>
            <button onClick={() => seedSpreadHandler('DROP')} disabled={seeding || running}
              className="flex-1 text-sm px-2 py-1.5 rounded border border-sky-300 text-sky-700 hover:bg-sky-50 disabled:opacity-50"
              title="Book real last-mile drops spread across DA territories (no DA yet — drops are assigned at the dest hub, event-driven off HANDED_TO_DROP_VAN). First-mile is SELF_DROP (sender drops at the origin hub). Then use 'Dispatch drops' below.">
              {seeding ? '…' : '🌐 Spread drops'}
            </button>
          </div>
          {seedMsg && <div className="text-[11px] text-sky-700">{seedMsg}</div>}

          {/* ── ③ PREP THE LANES — before the run ────────────────────────────────── */}
          <div className="text-[11px] font-bold uppercase tracking-wider text-slate-500 pt-2 mt-1 border-t border-gray-200">③ Prep the lanes (before run)</div>
          <button onClick={autoVerify} disabled={verifying || running}
            className="w-full text-sm px-3 py-1.5 rounded border border-emerald-300 text-emerald-700 hover:bg-emerald-50 disabled:opacity-50"
            title="Pickups: simulate each DA's door OTP handshake so the van carries ONLY verified pickups (un-verified are left behind)">
            {verifying ? 'Verifying…' : '🔑 Auto-verify pickups (simulate door OTP)'}
          </button>
          {verifyMsg && <div className="text-[11px] text-emerald-700">{verifyMsg}</div>}
          <button onClick={dispatchDropsHandler} disabled={droppingDrops || running}
            className="w-full text-sm px-3 py-1.5 rounded border border-indigo-300 text-indigo-700 hover:bg-indigo-50 disabled:opacity-50"
            title="Drops: fast-forward booked drops to the dest hub → out-for-delivery, mint each delivery OTP, bind to a drop-van loop. Do this before Run.">
            {droppingDrops ? 'Dispatching…' : '📦 Dispatch drops out for delivery'}
          </button>
          {dropMsg && <div className="text-[11px] text-indigo-700">{dropMsg}</div>}

          {/* ── ④ RUN THE DAY ────────────────────────────────────────────────────── */}
          <div className="text-[11px] font-bold uppercase tracking-wider text-slate-500 pt-2 mt-1 border-t border-gray-200">④ Run the day</div>
          {(() => {
            const pAssigned = das.reduce((a, d) => a + (d.pickups?.length || 0), 0)
            const pVerified = das.reduce((a, d) => a + (d.pickupsVerified || 0), 0)
            const pUnverified = pAssigned - pVerified
            const m5D = das.reduce((a, d) => a + (d.deliveries?.length || 0), 0)
            const pickupsReal = pVerified > 0
            const dropsReal = m5D > 0
            const anyReal = pickupsReal || dropsReal
            return (
              <div className={`text-xs rounded px-2 py-1.5 border leading-relaxed ${anyReal
                ? 'text-emerald-800 bg-emerald-50 border-emerald-100'
                : 'text-amber-800 bg-amber-50 border-amber-100'}`}>
                <div className="flex items-center justify-between">
                  <span>▶ <b>Run source</b> — what the vans will actually carry:</span>
                  <button onClick={fetchDas} disabled={running}
                    className="text-[11px] px-1.5 py-0.5 rounded border border-current/30 hover:bg-white/50 disabled:opacity-40"
                    title="Re-read the DA queues (e.g. after verifying an OTP in the DA app)">⟳</button>
                </div>
                <div className="mt-0.5">
                  ▲ pickups:{' '}
                  {pickupsReal
                    ? <b className="text-emerald-700">{pVerified} verified (M5 real)</b>
                    : <b className="text-gray-500">none — Spread pickups + Auto-verify first</b>}
                  {pUnverified > 0 && (
                    <span className="text-amber-700"> · {pUnverified} assigned but un-verified — press
                    Auto-verify to include them (else left behind)</span>
                  )}
                </div>
                <div>
                  ▼ drops:{' '}
                  {dropsReal
                    ? <b className="text-emerald-700">{m5D} (M5 real)</b>
                    : <b className="text-gray-500">none — Spread drops + Dispatch drops first</b>}
                </div>
                {das.length > 0 && <div className="text-gray-400">{das.length} DAs on shift.</div>}
              </div>
            )
          })()}
          <div className="flex items-center gap-3 text-xs text-gray-600 pt-1">
            <label className="flex items-center gap-1" title="animation pacing only">speed<input type="number" value={speed} min={1}
              onChange={e => setSpeed(+e.target.value)} className="w-14 border rounded px-1 py-0.5"
              disabled={running} /></label>
          </div>
          <div className="flex gap-2">
            <button onClick={run} disabled={!prepared || running}
              title={"Executes today's approved M6 plan:\n1) Publishes the day's real parcels to RabbitMQ (verified pickups + dispatched drops).\n2) M6 binds each parcel to a van loop (creates the van manifest).\n3) Drives the vans along their routes — GPS + custody scans at each DA vertex: COLLECT pickups (DA→van, fires PICKUP_COMPLETED) and DELIVER drops (van→DA).\n4) At the end (van meets DA) the collected pickups become HANDED_TO_PICKUP_VAN in M4 — refresh your bookings on :8080 to see it.\nThe van→hub leg (→ AT_ORIGIN_HUB) is the separate 'Complete first-mile' step."}
              className="flex-1 text-sm px-3 py-1.5 rounded bg-blue-600 text-white disabled:opacity-50">
              {running ? 'Running…' : '▶ Run the day'}
            </button>
            <button onClick={stop} disabled={!running}
              className="text-sm px-3 py-1.5 rounded border border-gray-300 disabled:opacity-40">Stop</button>
          </div>
          {error && <div className="text-xs text-red-600">{error}</div>}

          {/* ── ⑤ CLOSE THE LANES — after the run ────────────────────────────────── */}
          <div className="text-[11px] font-bold uppercase tracking-wider text-slate-500 pt-2 mt-1 border-t border-gray-200">⑤ Close the lanes (after run)</div>
          <button onClick={pickupsToHubHandler} disabled={toHubBusy || running}
            className="w-full text-sm px-3 py-1.5 rounded border border-slate-300 text-slate-700 hover:bg-slate-50 disabled:opacity-50"
            title="Pickup lane: van drives the collected pickups back to the origin hub (HANDED_TO_PICKUP_VAN → AT_ORIGIN_HUB). Stands in for M8's HUB_ORIGIN_IN scan.">
            {toHubBusy ? 'Moving to hub…' : '🏭 Complete first-mile (van → origin hub)'}
          </button>
          {hubMsg && <div className="text-[11px] text-slate-700">{hubMsg}</div>}
          <button onClick={autoVerifyDeliveriesHandler} disabled={verifyingDrops || running}
            className="w-full text-sm px-3 py-1.5 rounded border border-emerald-300 text-emerald-700 hover:bg-emerald-50 disabled:opacity-50"
            title="Drop lane: simulate every recipient's door OTP → out-for-delivery parcels become Delivered (DROPPED). Run first so the van reaches the DA.">
            {verifyingDrops ? 'Delivering…' : '🏠 Auto-verify deliveries (recipient OTP → Delivered)'}
          </button>
          {deliverMsg && <div className="text-[11px] text-emerald-700">{deliverMsg}</div>}
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

      </div>
    </div>
  )
}
