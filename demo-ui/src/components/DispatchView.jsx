import { useEffect, useState } from 'react'
import { hashDaColor } from '../utils/daColors.js'
import {
  retryDeferred, getDispatchState,
  resetDispatch, cancelTask, markAbsent, reconcileM4, endShift,
} from '../api/dispatchApi.js'

// M5 dispatch runs for TODAY (DispatchService stamps operating_date = today); the roster + cron come
// from the M3/M6 work the Execution tab produced for today.
const _n = new Date()
const TODAY = `${_n.getFullYear()}-${String(_n.getMonth() + 1).padStart(2, '0')}-${String(_n.getDate()).padStart(2, '0')}`

const short = (id) => (id ? id.slice(0, 8) : '—')

const STATUS_STYLE = {
  IDLE: 'bg-gray-100 text-gray-700', IN_PROGRESS: 'bg-blue-100 text-blue-700',
  CRON_LOCKED: 'bg-amber-100 text-amber-800', AT_CRON: 'bg-violet-100 text-violet-700',
  ABSENT: 'bg-red-100 text-red-700', OFFLINE: 'bg-gray-100 text-gray-400',
}

const DEFER_STYLE = {
  NO_DA_AVAILABLE: 'text-gray-600', CRON_INFEASIBLE: 'text-red-600',
  CRON_LOCKED: 'text-amber-700', DA_ABSENT: 'text-red-600', SHIFT_ENDED: 'text-gray-500',
}

const DEFER_EXPLAIN = {
  NO_DA_AVAILABLE: 'No DA serves this hex on shift — load a shift, or this territory has no assignable DA.',
  CRON_INFEASIBLE: 'No assignable DA can fit this task and still reach the hub cron before the flight cutoff.',
  CRON_LOCKED: 'The serving DA is locked at its cron meeting — retries open after the meeting window.',
  DA_ABSENT: 'The serving DA is marked absent (heartbeat lapsed).',
  SHIFT_ENDED: 'Shift ended — deferred to the next operating window.',
}

function slackColor(min) {
  if (min == null) return 'text-gray-400'
  if (min < 30) return 'text-red-600 font-semibold'
  if (min < 60) return 'text-amber-700'
  return 'text-emerald-700'
}

// Format an ISO instant as a local HH:MM clock time (the cron meeting time).
const clock = (iso) => {
  if (!iso) return '—'
  try { return new Date(iso).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }) } catch { return '—' }
}
const coord = (n) => (n == null ? '—' : Number(n).toFixed(4))
const ago = (sec) => (sec == null ? '—' : sec < 90 ? `${sec}s` : `${Math.round(sec / 60)}m`)
// GPS-freshness dot: green < 1 min, amber < 5 min, red older / absent.
function pingDot(sec, status) {
  if (status === 'ABSENT' || status === 'OFFLINE') return 'bg-red-500'
  if (sec == null) return 'bg-gray-300'
  if (sec < 60) return 'bg-emerald-500'
  if (sec < 300) return 'bg-amber-500'
  return 'bg-red-500'
}

// Roll a DA's queue into the at-a-glance numbers shown on the card: pickup/delivery split,
// in-progress, cron-at-risk, cross-territory, and the soonest upcoming ETA.
function summarizeQueue(queue = []) {
  const s = { total: queue.length, pickups: 0, deliveries: 0, enRoute: 0, atRisk: 0, xt: 0, nextEta: null }
  for (const t of queue) {
    if (t.taskType === 'DELIVERY') s.deliveries++; else s.pickups++
    if (t.status === 'IN_PROGRESS') s.enRoute++
    if (!t.cronSafe) s.atRisk++
    if (t.crossTerritory) s.xt++
    if (t.expectedEta) {
      const e = new Date(t.expectedEta).getTime()
      if (!Number.isNaN(e) && (s.nextEta == null || e < s.nextEta)) s.nextEta = e
    }
  }
  return s
}

export default function DispatchView({ cityId, cityCode }) {
  const [state, setState] = useState(null)
  const [busy, setBusy] = useState('')
  const [err, setErr] = useState(null)
  const [msg, setMsg] = useState(null)         // { ok, text } success/failure note after an action
  const [selDef, setSelDef] = useState(null)   // deferred parcel selected for the detail panel

  // Run an action; `describe(before, after)` builds a success message from the state delta.
  async function run(label, fn, describe) {
    setBusy(label); setErr(null); setMsg(null)
    const before = state
    try {
      const res = await fn()
      if (res && res.das != null) setState(res)
      if (describe) setMsg({ ok: true, text: describe(before, res) })
      return res
    } catch (e) {
      setErr(e.message); setMsg({ ok: false, text: e.message })
    } finally { setBusy('') }
  }

  useEffect(() => {
    getDispatchState(cityId, TODAY).then(setState).catch(() => setState(null))
  }, [cityId])

  const s = state?.summary
  const das = state?.das ?? []
  const deferred = state?.deferred ?? []
  const qP = das.reduce((a, d) => a + (d.queue || []).filter(t => t.taskType === 'PICKUP').length, 0)
  const qD = das.reduce((a, d) => a + (d.queue || []).filter(t => t.taskType === 'DELIVERY').length, 0)
  const vanCount = new Set(das.map(d => d.vanId).filter(Boolean)).size
  const noVan = das.filter(d => !d.vanId).length

  // Group DAs by the van they meet at their cron (M6 rendezvous). No-van DAs (no approved M6 plan) sort last.
  const vanGroups = (() => {
    const m = new Map()
    for (const d of das) {
      const k = d.vanId || '__novan__'
      if (!m.has(k)) m.set(k, [])
      m.get(k).push(d)
    }
    return [...m.entries()].sort(([a], [b]) =>
      a === '__novan__' ? 1 : b === '__novan__' ? -1 : a.localeCompare(b))
  })()

  // Manual board refresh — pull the latest M5 state on demand (e.g. after seeding/assigning in Execution).
  async function refresh() {
    setBusy('refresh'); setErr(null); setMsg(null)
    try {
      const res = await getDispatchState(cityId, TODAY); setState(res)
      const sm = res.summary || {}
      setMsg({ ok: true, text: `Board refreshed — ${sm.das ?? 0} DAs · ${sm.assigned ?? 0} queued · ${sm.deferred ?? 0} deferred.` })
    } catch (e) { setErr(e.message); setMsg({ ok: false, text: e.message }) } finally { setBusy('') }
  }

  return (
    <div className="flex flex-1 overflow-hidden">
      {/* ── left: controls + summary + deferred ── */}
      <div className="w-80 bg-white border-r border-gray-200 flex flex-col overflow-y-auto p-4 gap-4">
        <div>
          <div className="flex items-center justify-between mb-2">
            <div className="text-xs uppercase tracking-wide text-gray-400">M5 · Dispatch</div>
            <button disabled={!!busy} onClick={refresh}
              className="text-[11px] px-2 py-0.5 rounded border border-gray-300 text-gray-600 hover:bg-gray-50 disabled:opacity-50"
              title="Refresh the board now (pickups/drops assigned on the fly from Execution)">
              {busy === 'refresh' ? '⟳…' : '⟳ Refresh'}
            </button>
          </div>
          <p className="text-xs text-gray-500 leading-relaxed">
            Set up on the <strong>Execution</strong> tab first (① Prepare → Load shift, ② Spread pickups).
            Each task lands on the least-loaded DA that can still make its <strong>cron van meeting</strong>
            (the hard constraint) — else it's <strong>deferred</strong> here.
          </p>
          <p className="text-[11px] text-gray-500 bg-gray-50 border border-gray-100 rounded px-2 py-1.5 mt-2 leading-relaxed">
            💡 To load the engine, book <strong>real</strong> parcels on the <strong>Execution</strong> tab
            (② Seed → <strong>Spread pickups</strong>, e.g. count 40 across 10 DAs). M5 auto-assigns each via
            its event consumer through the same cron-feasibility engine; overflow beyond a DA's cron capacity
            defers here. Then use <strong>Work next</strong> / <strong>Retry deferred</strong> below.
          </p>
        </div>

        <div className="flex flex-col gap-2">
          <p className="text-[11px] text-gray-500 leading-snug">
            Load the shift from the <strong>Execution</strong> tab (① Setup → 👷 Load shift), then book real
            parcels (② Spread pickups). The controls below drive M5's engine on whatever's queued.
          </p>

          <div className="flex gap-2">
            <button disabled={!!busy} onClick={() => run('retry', () => retryDeferred(cityId, TODAY),
                (b, a) => { const r = (b?.summary?.deferred ?? 0) - (a.summary?.deferred ?? 0);
                  return r > 0 ? `${r} deferral(s) reassigned to feasible DAs · ${a.summary?.deferred ?? 0} still deferred.`
                              : `No deferral could be reassigned yet · ${a.summary?.deferred ?? 0} still deferred.` })}
              className="flex-1 px-3 py-2 text-sm rounded border border-gray-300 hover:bg-gray-50 disabled:opacity-50"
              title="Re-attempt every PENDING deferral through the cron-feasibility engine. Ones that now fit are placed on a DA; still-infeasible ones stay deferred. Use after freeing capacity, or after marking a DA absent.">
              {busy === 'retry' ? '…' : 'Retry deferred'}
            </button>
          </div>
          <div className="flex gap-2">
            <button disabled={!!busy} onClick={() => run('endshift', () => endShift(cityId, TODAY),
                (b, a) => `Shift ended — remaining queued tasks deferred (SHIFT_ENDED), DAs set OFFLINE. ${a.summary?.deferred ?? 0} deferred.`)}
              className="flex-1 px-3 py-2 text-sm rounded border border-amber-200 text-amber-700 hover:bg-amber-50 disabled:opacity-50"
              title="End of the operating window: defer every remaining QUEUED task with reason SHIFT_ENDED and set all DAs OFFLINE. Clean end-of-day.">
              {busy === 'endshift' ? '…' : 'End shift'}
            </button>
            <button disabled={!!busy}
              onClick={async () => { await run('reset', () => resetDispatch(cityId, TODAY), () => 'Board reset — in-memory roster + all queued/deferred tasks cleared for today. Re-load the shift from Execution.'); setState(null) }}
              className="flex-1 px-3 py-2 text-sm rounded border border-red-200 text-red-600 hover:bg-red-50 disabled:opacity-50"
              title="Clear the in-memory DA roster + every queued/deferred task for today (start the M5 board over). Re-load the shift from the Execution tab afterward.">
              Reset
            </button>
          </div>
        </div>

        {msg && <div className={`text-xs rounded p-2 break-words border ${msg.ok
          ? 'text-emerald-800 bg-emerald-50 border-emerald-100'
          : 'text-red-700 bg-red-50 border-red-100'}`}>{msg.ok ? '✅ ' : '✕ '}{msg.text}</div>}

        {s && (
          <div className="grid grid-cols-2 gap-2 text-center">
            <Stat label="DAs" value={s.das} />
            <Stat label="Vans" value={vanCount} sub={noVan > 0 ? `${noVan} w/o van` : undefined}
              cls="text-teal-700" />
            <Stat label="Queued tasks" value={s.assigned} sub={`P ${qP} · D ${qD}`} />
            <Stat label="Deferred" value={s.deferred} cls="text-red-600" />
            <Stat label="Cron-locked" value={s.cronLocked} cls="text-amber-700" />
          </div>
        )}


        <div className="flex-1">
          <div className="text-xs uppercase tracking-wide text-gray-400 mb-2">
            Deferred ({deferred.length})
          </div>
          <div className="flex flex-col gap-1">
            {deferred.length === 0 && <div className="text-xs text-gray-400">none</div>}
            {deferred.map((d) => (
              <button key={d.shipmentId} onClick={() => setSelDef(d)}
                title="Click for details"
                className={`text-xs flex justify-between items-center gap-2 border-b border-gray-100 py-1 text-left hover:bg-gray-50 ${selDef?.shipmentId === d.shipmentId ? 'bg-blue-50' : ''}`}>
                <span className="font-mono text-gray-600 truncate">{d.ref || short(d.shipmentId)}</span>
                <span className={`${DEFER_STYLE[d.reason] || 'text-gray-600'} shrink-0`}>{d.reason}</span>
              </button>
            ))}
          </div>
        </div>
      </div>

      {/* ── main: deferred detail (if selected) + per-DA queue cards ── */}
      <div className="flex-1 overflow-y-auto p-4 bg-gray-50">
        {selDef && <DeferredDetail d={selDef} onClose={() => setSelDef(null)} />}
        {das.length === 0 ? (
          <div className="h-full flex items-center justify-center text-gray-400 text-sm">
            No DAs loaded — click <b className="mx-1">Load shift</b> (after running the Execution tab).
          </div>
        ) : (
          <div className="flex flex-col gap-5">
            {vanGroups.map(([vanId, members]) => {
              const real = vanId !== '__novan__'
              const groupTasks = members.reduce((a, d) => a + (d.queue || []).length, 0)
              const meet = members.find(d => d.cronMeetingTime)?.cronMeetingTime
              return (
              <div key={vanId}>
                <div className="flex items-center gap-2 mb-2">
                  <span className="inline-block w-3 h-3 rounded-sm"
                    style={{ background: real ? hashDaColor(vanId) : '#cbd5e1' }} />
                  <span className="text-sm font-semibold text-gray-700">
                    {real ? <>🚐 Van <span className="font-mono">{short(vanId)}</span></> : '🚐 No van assigned'}</span>
                  <span className="text-xs text-gray-400">{members.length} DA{members.length === 1 ? '' : 's'}</span>
                  {real && meet && <span className="text-xs text-gray-400">· meets {clock(meet)}</span>}
                  <span className="text-xs text-gray-400">· {groupTasks} task{groupTasks === 1 ? '' : 's'}</span>
                </div>
                <div className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-3">
                  {members.map((da) => (
                    <div key={da.daId} className="bg-white rounded-lg border border-gray-200 shadow-sm overflow-hidden">
                <div className="flex items-center gap-2 px-3 py-2 border-b border-gray-100"
                  style={{ borderLeft: `4px solid ${hashDaColor(da.daId)}` }}>
                  <span className="font-mono text-xs text-gray-700" title={`Delivery Associate id: ${da.daId}`}>
                    DA <b>{short(da.daId)}</b></span>
                  <span className={`text-[10px] px-1.5 py-0.5 rounded ${STATUS_STYLE[da.status] || 'bg-gray-100 text-gray-500'}`}>
                    {da.status}
                  </span>
                  <div className="flex-1" />
                  <span className={`text-xs ${slackColor(da.cronSlackMinutes)}`}
                    title={`van meeting at ${clock(da.cronMeetingTime)} · ${da.cronSlackMinutes ?? '—'} min away`}>
                    {da.cronSlackMinutes != null ? `cron ${da.cronSlackMinutes}m` : 'no cron'}
                  </span>
                  {da.status !== 'ABSENT' && da.status !== 'OFFLINE' && (
                    <button disabled={!!busy}
                      onClick={() => run('absent', async () => {
                          const r = await markAbsent(cityId, TODAY, da.daId)
                          await reconcileM4(cityCode, TODAY).catch(() => {})   // orphaned M4 pickups → "reassigning"
                          return r
                        },
                        (b, a) => { const d = (a.summary?.deferred ?? 0) - (b?.summary?.deferred ?? 0);
                          return `DA ${da.daId.slice(0, 8)} marked ABSENT — ${d > 0 ? d + ' queued task(s) deferred (DA_ABSENT), booking(s) now "reassigning"' : 'no queued tasks to defer'}. Use Retry deferred to reassign.` })}
                      className="text-[10px] px-1.5 py-0.5 rounded border border-red-200 text-red-500 hover:bg-red-50 disabled:opacity-50"
                      title="Action: force this DA absent (simulate a heartbeat lapse) — it stops taking pickups and its queued tasks defer as DA_ABSENT">⊘ mark absent</button>
                  )}
                </div>

                {/* Meta — max visibility: grid, territory size, the van it meets, cron clock, vertex */}
                <div className="px-3 py-1.5 grid grid-cols-2 gap-x-3 gap-y-0.5 text-[10px] text-gray-500 bg-gray-50/60 border-b border-gray-100">
                  <span title="city H3 grid">🗺 {(cityCode || '').toUpperCase()} grid</span>
                  <span title="hexes in this DA's territory">⬢ {da.hexes?.length ?? 0} hexes</span>
                  <span className={da.vanId ? '' : 'text-amber-600'} title={da.vanId ? `van ${da.vanId}` : 'no van — run Re-prepare'}>
                    🚐 {da.vanId ? `van ${short(da.vanId)}` : 'no van'}</span>
                  <span title="scheduled van-meeting time">⏱ cron {clock(da.cronMeetingTime)}</span>
                  <span title="cron meeting vertex (where DA meets the van)">
                    📍 vertex {coord(da.cronVertexLat)}, {coord(da.cronVertexLon)}</span>
                  <span className={da.distanceToCronKm != null ? '' : 'text-gray-300'}
                    title="straight-line distance from the DA to its cron vertex">
                    📏 {da.distanceToCronKm != null ? `${da.distanceToCronKm} km to vertex` : '—'}</span>
                  <span className="flex items-center gap-1" title={`last GPS ping ${ago(da.lastPingSecondsAgo)} ago`}>
                    <span className={`inline-block w-1.5 h-1.5 rounded-full ${pingDot(da.lastPingSecondsAgo, da.status)}`} />
                    📡 ping {ago(da.lastPingSecondsAgo)} ago</span>
                  {da.meetingTimes?.length > 1 && (
                    <span className="col-span-2" title="all of the day's van meetings for this DA">
                      🕐 meetings {da.meetingTimes.join(', ')}</span>
                  )}
                </div>

                {/* Workload summary — pickup/delivery split, in-progress, at-risk, cross-territory + load bar */}
                {(() => {
                  const q = summarizeQueue(da.queue)
                  return (
                    <div className="px-3 py-1.5 border-b border-gray-100">
                      <div className="flex items-center flex-wrap gap-x-2 gap-y-0.5 text-[10px]">
                        <span className="font-semibold text-gray-700" title="total tasks in queue">{q.total} task{q.total === 1 ? '' : 's'}</span>
                        <span className="text-blue-700" title="pickups">P {q.pickups}</span>
                        <span className="text-indigo-700" title="deliveries">D {q.deliveries}</span>
                        {q.enRoute > 0 && <span className="text-blue-600" title="currently en-route">▶ {q.enRoute} active</span>}
                        {q.atRisk > 0 && <span className="text-red-600 font-semibold" title="tasks that may miss the cron meeting">⚠ {q.atRisk} at-risk</span>}
                        {q.xt > 0 && <span className="text-purple-600" title="cross-territory tasks">XT {q.xt}</span>}
                        {da.completedToday > 0 && <span className="text-emerald-700" title="tasks completed today">✅ {da.completedToday} done</span>}
                        {da.codParcels > 0 && <span className="text-gray-600" title="COD parcels in this DA's tasks">💵 {da.codParcels} COD</span>}
                        {da.parcelsHandedOff > 0 && <span className="text-teal-700" title="parcels handed to the van at the cron meeting">🤝 {da.parcelsHandedOff} handed</span>}
                        <div className="flex-1" />
                        {q.nextEta != null && (
                          <span className="text-gray-500" title="soonest task ETA">next ~{clock(new Date(q.nextEta).toISOString())}</span>
                        )}
                      </div>
                      <div className="mt-1 h-1.5 w-full rounded-full bg-gray-100 overflow-hidden flex">
                        {q.total === 0 ? null : (
                          <>
                            <div className="bg-blue-500 h-full" style={{ width: `${(q.pickups / q.total) * 100}%` }} />
                            <div className="bg-indigo-500 h-full" style={{ width: `${(q.deliveries / q.total) * 100}%` }} />
                          </>
                        )}
                      </div>
                    </div>
                  )
                })()}

                <div className="px-3 py-2">
                  {da.queue.length === 0 ? (
                    <div className="text-xs text-gray-400">idle — empty queue</div>
                  ) : (
                    <ol className="flex flex-col gap-1">
                      {da.queue.map((t) => (
                        <li key={t.shipmentId} className="flex items-center gap-2 text-xs group">
                          <span className="w-4 text-gray-400">{t.position}</span>
                          <span className={`text-[9px] px-1 rounded font-bold ${
                            t.taskType === 'DELIVERY' ? 'bg-indigo-100 text-indigo-700' : 'bg-blue-100 text-blue-700'}`}
                            title={t.taskType}>{t.taskType === 'DELIVERY' ? 'D' : 'P'}</span>
                          <span className="font-mono text-gray-600" title={`shipment ${t.shipmentId}`}>{short(t.shipmentId)}</span>
                          <span className="text-gray-400" title={`hex ${t.tileId} · ${coord(t.taskLat)}, ${coord(t.taskLon)}`}>
                            hex {short(t.tileId)}</span>
                          <div className="flex-1" />
                          {t.expectedEta && <span className="text-[10px] text-gray-400" title="expected ETA">~{clock(t.expectedEta)}</span>}
                          {t.status === 'IN_PROGRESS' && <span className="text-[10px] text-blue-600">en-route</span>}
                          <span className={`text-[10px] ${t.cronSafe ? 'text-emerald-600' : 'text-gray-400'}`}
                            title="fits before the cron meeting">{t.cronSafe ? '✓ cron' : '—'}</span>
                          {t.crossTerritory && <span className="text-[10px] text-purple-600">XT</span>}
                          {t.status === 'QUEUED' && (
                            <button disabled={!!busy}
                              onClick={() => run('cancel', () => cancelTask(cityId, TODAY, t.shipmentId, t.taskType),
                                () => `Task ${String(t.shipmentId).slice(0, 8)} (${t.taskType}) cancelled — removed from the DA's queue.`)}
                              className="text-[10px] text-gray-300 hover:text-red-600 disabled:opacity-50"
                              title="Remove this queued task from the DA's queue (cancel it)">✕</button>
                          )}
                        </li>
                      ))}
                    </ol>
                  )}
                </div>
                    </div>
                  ))}
                </div>
              </div>
              )
            })}
          </div>
        )}
      </div>
    </div>
  )
}

function Stat({ label, value, cls = 'text-gray-800', sub }) {
  return (
    <div className="bg-gray-50 rounded p-2">
      <div className={`text-lg font-bold ${cls}`}>{value}</div>
      <div className="text-[10px] uppercase tracking-wide text-gray-400">{label}</div>
      {sub && <div className="text-[10px] text-gray-500">{sub}</div>}
    </div>
  )
}

function DefField({ label, value }) {
  return (
    <div>
      <div className="text-[11px] uppercase tracking-wide text-gray-400">{label}</div>
      <div className="text-gray-800">{value}</div>
    </div>
  )
}

// Detail panel for a clicked deferred parcel — why it couldn't be assigned + everything M5 knows about it.
function DeferredDetail({ d, onClose }) {
  return (
    <div className="mb-4 bg-white rounded-lg border border-red-200 shadow-sm">
      <div className="flex items-center justify-between px-4 py-2 border-b border-gray-100 bg-red-50 rounded-t-lg">
        <div>
          <span className="font-mono text-sm font-semibold text-gray-800">{d.ref || short(d.shipmentId)}</span>
          <span className="ml-2 text-[11px] text-gray-500">deferred · {d.taskType}</span>
        </div>
        <button onClick={onClose} className="text-gray-400 hover:text-gray-700 text-sm" title="Close">✕</button>
      </div>
      <div className="p-4 grid grid-cols-2 gap-x-6 gap-y-3">
        <DefField label="Defer reason" value={<span className={DEFER_STYLE[d.reason] || ''}>{d.reason}</span>} />
        <DefField label="Shipment state (M4)" value={d.m4State || '—'} />
        <DefField label="Hex" value={short(d.tileId)} />
        <DefField label="Location" value={`${coord(d.lat)}, ${coord(d.lon)}`} />
        <DefField label="Deferred at" value={clock(d.deferredAt)} />
        <DefField label="Retry after" value={clock(d.retryAfter)} />
        <DefField label="Retries" value={String(d.retryCount ?? 0)} />
        <DefField label="Status" value={d.status} />
      </div>
      <div className="px-4 pb-3 text-xs text-gray-500">
        {DEFER_EXPLAIN[d.reason] || 'Deferred — awaiting reassignment.'} Use <b>Retry deferred</b> to re-attempt now.
      </div>
    </div>
  )
}
