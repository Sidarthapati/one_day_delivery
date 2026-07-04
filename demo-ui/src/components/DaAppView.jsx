import { useCallback, useEffect, useRef, useState } from 'react'
import { hashDaColor } from '../utils/daColors.js'
import { getDispatchState } from '../api/dispatchApi.js'
import { getDaTasks, verifyPickupOtp, verifyDeliveryOtp, markPickupCollected } from '../api/daApi.js'

// The DA app runs for TODAY (same operating date as M5 dispatch + the M6 run).
const _n = new Date()
const TODAY = `${_n.getFullYear()}-${String(_n.getMonth() + 1).padStart(2, '0')}-${String(_n.getDate()).padStart(2, '0')}`

const short = (id) => (id ? id.slice(0, 8) : '—')
const coord = (n) => (n == null ? '—' : Number(n).toFixed(4))
const clock = (iso) => { if (!iso) return '—'; try { return new Date(iso).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }) } catch { return '—' } }

export default function DaAppView({ cityId, cityCode }) {
  const [roster, setRoster] = useState([])      // loaded DAs (id, van, P/D counts)
  const [daId, setDaId] = useState(null)
  const [tasks, setTasks] = useState(null)       // selected DA's enriched tasks
  const [otp, setOtp] = useState({})             // shipmentId → typed OTP
  const [msg, setMsg] = useState({})             // shipmentId → verify result {ok, text}
  const [err, setErr] = useState(null)
  const [busy, setBusy] = useState(false)
  const [rosterRefreshing, setRosterRefreshing] = useState(false)
  const [tasksRefreshing, setTasksRefreshing] = useState(false)
  const rosterBusy = useRef(false)   // in-flight guard so slow DB reads don't pile up

  // Board-level refresh: reload the whole shift roster (M5 dispatch state) with its live P/D counts.
  // A transient failure (e.g. a Render-DB blip) must NOT wipe a loaded roster — keep the last good list
  // and surface the error instead of blanking to "0 DAs on shift".
  const refreshRoster = useCallback(async () => {
    if (rosterBusy.current) return                  // skip if a read is still in flight
    rosterBusy.current = true
    setRosterRefreshing(true)
    try {
      const s = await getDispatchState(cityId, TODAY)
      setRoster((s.das || []).filter(d => d.cronVertexLat != null))
      setErr(null)
    } catch (e) {
      setErr('Could not refresh roster (' + e.message + ') — showing last known.')
    } finally {
      rosterBusy.current = false
      setRosterRefreshing(false)
    }
  }, [cityId])

  // On mount / city change: clear selection, load the roster, and poll every 10s (skip while hidden).
  useEffect(() => {
    setDaId(null); setTasks(null); setErr(null)
    refreshRoster()
    const id = setInterval(() => { if (!document.hidden) refreshRoster() }, 10000)
    return () => clearInterval(id)
  }, [cityId, refreshRoster])

  // When a DA is picked, pull its enriched task list.
  useEffect(() => {
    if (!daId) { setTasks(null); return }
    let alive = true
    getDaTasks(daId, cityId, TODAY)
      .then(t => { if (alive) setTasks(t) })
      .catch(e => { if (alive) { setErr(e.message); setTasks(null) } })
    return () => { alive = false }
  }, [daId, cityId])

  // DA-level refresh: reload the selected DA's pickups & drops on demand (also called after verify).
  async function reloadTasks() {
    if (!daId) return
    setTasksRefreshing(true)
    try { setTasks(await getDaTasks(daId, cityId, TODAY)) } catch (e) { setErr(e.message) }
    finally { setTasksRefreshing(false) }
  }

  // Verify the door OTP. Branches by task type: pickups hit the pickup-OTP endpoint (→ PICKED_UP),
  // drops hit the delivery-OTP endpoint (→ DROPPED).
  async function verify(task) {
    if (!task.ref) { setMsg(m => ({ ...m, [task.shipmentId]: { ok: false, text: 'No booking ref (synthetic task) — cannot verify' } })); return }
    const code = (otp[task.shipmentId] || '').trim()
    if (!code) { setMsg(m => ({ ...m, [task.shipmentId]: { ok: false, text: 'Enter the OTP the customer gave you' } })); return }
    const isDrop = task.taskType === 'DELIVERY'
    setBusy(true)
    try {
      if (isDrop) await verifyDeliveryOtp(task.ref, code)
      else { await verifyPickupOtp(task.ref, code); await markPickupCollected(task.shipmentId) }
      setMsg(m => ({ ...m, [task.shipmentId]: { ok: true,
        text: isDrop ? '✅ Delivered (DROP_COLLECTED → DROPPED)' : '✅ Verified — picked up (PICKUP_ASSIGNED → PICKED_UP)' } }))
      await reloadTasks()
    } catch (e) {
      setMsg(m => ({ ...m, [task.shipmentId]: { ok: false, text: '✕ ' + e.message } }))
    } finally { setBusy(false) }
  }

  const counts = (d) => {
    const q = d.queue || []
    return { p: q.filter(t => t.taskType === 'PICKUP').length, d: q.filter(t => t.taskType === 'DELIVERY').length }
  }

  return (
    <div className="flex flex-1 overflow-hidden">
      {/* roster */}
      <div className="w-64 bg-white border-r border-gray-200 flex flex-col overflow-y-auto">
        <div className="px-3 py-2 border-b border-gray-100">
          <div className="flex items-center justify-between">
            <div className="text-xs uppercase tracking-wide text-gray-400">📱 DA Phones</div>
            <button onClick={refreshRoster} disabled={rosterRefreshing}
              className="text-[11px] px-2 py-0.5 rounded border border-gray-300 text-gray-600 hover:bg-gray-50 disabled:opacity-50"
              title="Refresh the whole roster now (pickups/drops assigned on the fly)">
              {rosterRefreshing ? '⟳…' : '⟳ Refresh'}
            </button>
          </div>
          <div className="text-sm font-bold text-gray-800">Open a DA's phone</div>
          <div className="text-[11px] text-gray-500">{roster.length} DAs on shift · tap one to see their field app</div>
        </div>
        {err && <div className="px-3 py-2 text-[11px] text-amber-600 bg-amber-50 border-b border-amber-100">{err}</div>}
        {roster.length === 0 && !err && <div className="p-3 text-xs text-gray-400">No DAs loaded — Load shift (Execution tab) first.</div>}
        {roster.map(d => {
          const c = counts(d)
          const sel = d.daId === daId
          return (
            <button key={d.daId} onClick={() => setDaId(d.daId)}
              className={`text-left px-3 py-2 border-b border-gray-50 hover:bg-gray-50 ${sel ? 'bg-blue-50' : ''}`}
              style={{ borderLeft: `4px solid ${hashDaColor(d.daId)}` }}>
              <div className="font-mono text-xs text-gray-700">DA <b>{short(d.daId)}</b></div>
              <div className="text-[11px] text-gray-500">
                🚐 {d.vanId ? short(d.vanId) : 'no van'} · <span className="text-blue-700">P {c.p}</span> · <span className="text-indigo-700">D {c.d}</span>
              </div>
            </button>
          )
        })}
      </div>

      {/* selected DA's run — rendered as the DA's PHONE so the demo reads as "the worker's field app",
          visually distinct from the Dispatch ops control tower (which shows the whole roster at once). */}
      <div className="flex-1 overflow-y-auto p-5 bg-gray-200/60 flex justify-center">
        {!daId ? (
          <div className="self-center text-center text-gray-500 text-sm max-w-xs">
            <div className="text-4xl mb-2">📱</div>
            This is the <b>DA Phone</b> — the delivery associate's field app.<br />
            <span className="text-gray-400">Tap a DA on the left to open their phone.</span>
          </div>
        ) : !tasks ? (
          <div className="self-center text-gray-400 text-sm">Loading…</div>
        ) : (
          <div className="w-full" style={{ maxWidth: 420 }}>
            {/* phone device frame */}
            <div className="rounded-[2.2rem] border-[10px] border-gray-900 bg-white shadow-2xl overflow-hidden">
              {/* status bar / notch */}
              <div className="bg-gray-900 text-white text-[10px] px-5 py-1 flex items-center justify-between">
                <span>{clock(new Date().toISOString())}</span>
                <span className="font-semibold tracking-wide">1DD Driver</span>
                <span className="text-gray-300">●●● 4G 🔋</span>
              </div>
              {/* app bar — this DA's identity + van + refresh */}
              <div className="px-4 py-3 border-b border-gray-100" style={{ borderLeft: `5px solid ${hashDaColor(daId)}` }}>
                <div className="flex items-center gap-2">
                  <span className="font-mono text-sm">DA <b>{short(daId)}</b></span>
                  <div className="flex-1" />
                  <button onClick={reloadTasks} disabled={tasksRefreshing}
                    className="text-[11px] px-2 py-0.5 rounded border border-gray-300 text-gray-600 hover:bg-gray-50 disabled:opacity-50"
                    title="Refresh this DA's pickups & drops now">
                    {tasksRefreshing ? '⟳…' : '⟳ Refresh'}
                  </button>
                </div>
                <div className="text-[11px] text-gray-500 mt-1 flex flex-wrap gap-x-3">
                  <span>🚐 van {tasks.vanShort || '—'}</span>
                  <span>📍 cron {coord(tasks.vertexLat)}, {coord(tasks.vertexLon)}</span>
                  {tasks.distanceToCronKm != null && <span>📏 {tasks.distanceToCronKm} km</span>}
                </div>
                {tasks.meetingTimes?.length > 0 && (
                  <div className="text-[11px] text-gray-500 mt-0.5">🕐 van meets at: {tasks.meetingTimes.join(', ')}</div>
                )}
              </div>
              {/* app body — scrollable task feed */}
              <div className="p-3 bg-gray-50 overflow-y-auto" style={{ maxHeight: '68vh' }}>
                {err && <div className="text-xs text-red-600 bg-red-50 rounded p-2 mb-3">{err}</div>}
                <TaskSection title="📥 Pickups — collect from sender" tasks={tasks.pickups} color="text-blue-700"
                  kind="pickup" otp={otp} setOtp={setOtp} msg={msg} onVerify={verify} busy={busy} />
                <TaskSection title="📤 Drops — deliver to receiver" tasks={tasks.deliveries} color="text-indigo-700"
                  kind="delivery" otp={otp} setOtp={setOtp} msg={msg} onVerify={verify} busy={busy} />
              </div>
            </div>
            <div className="text-center text-[11px] text-gray-400 mt-2">
              📱 DA Phone — what the delivery associate taps in the field. Ops supervision lives in the <b>Dispatch</b> tab.
            </div>
          </div>
        )}
      </div>
    </div>
  )
}

// The OTP/verify state of a task card, derived from the M4 shipment state (the source of truth) so the
// card matches the customer's reality — not the M5 task status. Synthetic tasks (no booking) have no ref.
function taskPhase(kind, t) {
  const s = t.m4State
  if (kind === 'delivery') {
    if (!t.ref) return { synthetic: true }
    if (s === 'DROPPED') return { done: true, label: '✓ Delivered (OTP verified)' }
    if (s === 'DROP_COLLECTED') return { verify: true, btn: 'Verify delivery', ph: 'OTP from recipient' }
    return { pending: true, label: '⏳ Awaiting dispatch — run “Dispatch drops” first' }
  }
  if (!t.ref) return { synthetic: true }
  if (s === 'BOOKED' || s === 'PICKUP_ASSIGNED') return { verify: true, btn: 'Verify pickup', ph: 'OTP from customer' }
  return { done: true, label: '✓ Collected — picked up (OTP verified)' }
}

function TaskSection({ title, tasks = [], color, kind, otp, setOtp, msg, onVerify, busy }) {
  return (
    <div className="mb-5">
      <div className={`text-sm font-semibold mb-2 ${color}`}>{title} <span className="text-gray-400 font-normal">({tasks.length})</span></div>
      {tasks.length === 0 && <div className="text-xs text-gray-400">none</div>}
      <div className="grid grid-cols-1 gap-3">
        {tasks.map(t => {
          const ph = taskPhase(kind, t)
          return (
          <div key={t.shipmentId} className="bg-white rounded-lg border border-gray-200 p-3 text-xs">
            <div className="flex items-center gap-2 mb-1">
              <span className={`text-[9px] px-1 rounded font-bold ${t.taskType === 'DELIVERY' ? 'bg-indigo-100 text-indigo-700' : 'bg-blue-100 text-blue-700'}`}>
                {t.taskType === 'DELIVERY' ? 'DROP' : 'PICK'}</span>
              <span className="font-mono font-semibold text-gray-800">{t.ref || short(t.shipmentId)}</span>
              <div className="flex-1" />
              {t.cod && <span className="text-gray-600" title="cash on delivery">💵 COD</span>}
              <span className={t.cronSafe ? 'text-emerald-600' : 'text-red-600'} title="fits before the van cron meeting">{t.cronSafe ? '✓ cron' : '⚠ at-risk'}</span>
              {t.crossTerritory && <span className="text-purple-600">XT</span>}
            </div>
            <div className="text-gray-600">📍 {t.address || '—'}</div>
            <div className="text-gray-400 mt-0.5">
              ⬢ hex {short(t.tileId)} · {coord(t.lat)}, {coord(t.lon)} · #{t.position} · {t.m4State || t.status}
              {t.eta ? ` · ~${clock(t.eta)}` : ''}
            </div>
            {/* OTP UI shown only in the verifiable window (pickup awaiting collection / drop out-for-delivery),
                keyed off the M4 state. The DA NEVER sees or generates the code — it's the customer's secret.
                The DA types the code the customer reads out (from their Refresh status), then verifies. */}
            {ph.verify && (
              <div className="mt-2">
                <div className="flex items-center gap-2">
                  <input value={otp[t.shipmentId] || ''} onChange={e => setOtp(o => ({ ...o, [t.shipmentId]: e.target.value }))}
                    placeholder={ph.ph} className="flex-1 border rounded px-2 py-1 text-xs" />
                  <button onClick={() => onVerify(t)} disabled={busy}
                    className="text-xs px-2 py-1 rounded bg-blue-600 text-white disabled:opacity-40">{ph.btn}</button>
                </div>
                <div className="text-[10px] text-gray-400 mt-1">Ask the customer for their code and enter it.</div>
              </div>
            )}
            {ph.done && <div className="mt-2 text-[11px] text-emerald-700 font-medium">{ph.label}</div>}
            {ph.pending && <div className="mt-2 text-[11px] text-gray-500">{ph.label}</div>}
            {ph.synthetic && <div className="mt-2 text-[11px] text-gray-400">synthetic — no booking, OTP n/a</div>}
            {msg[t.shipmentId] && (
              <div className={`mt-1 text-[11px] ${msg[t.shipmentId].ok ? 'text-emerald-700' : 'text-red-600'}`}>{msg[t.shipmentId].text}</div>
            )}
          </div>
          )
        })}
      </div>
    </div>
  )
}
