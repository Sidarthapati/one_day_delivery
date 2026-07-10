import { useEffect, useRef, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { CITIES } from '../../cities.js'
import { runDay, stopRun, getRunStatus, getJourneys, getRunEvents } from '../../api/journeyApi.js'

// The §0 pipeline, left→right. `key` matches the stage token DemoJourneyService.JourneyRecord emits;
// any unmapped stage falls to column 0. Tailwind JIT needs literal class strings, so tones are a map.
const STAGES = [
  { key: 'BOOKED',         label: 'Booked',       icon: '📦', tone: 'slate'  },
  { key: 'DA_ASSIGNED',    label: 'DA assigned',  icon: '🧭', tone: 'indigo' },
  { key: 'EN_ROUTE',       label: 'DA en route',  icon: '🛵', tone: 'indigo' },
  { key: 'PICKED_UP',      label: 'Picked up',    icon: '✅', tone: 'indigo' },
  { key: 'IN_COLLECT_VAN', label: 'Collect van',  icon: '🚐', tone: 'amber'  },
  { key: 'AT_ORIGIN_HUB',  label: 'Origin hub',   icon: '🏭', tone: 'violet' },
  { key: 'IN_FLIGHT',      label: 'In flight',    icon: '✈️', tone: 'sky'    },
  { key: 'AT_DEST_HUB',    label: 'Dest hub',     icon: '🏬', tone: 'violet' },
  { key: 'DELIVERED',      label: 'Delivered',    icon: '🎉', tone: 'green'  },
]
const STAGE_INDEX = Object.fromEntries(STAGES.map((s, i) => [s.key, i]))

const TONE = {
  slate:  { chip: 'bg-slate-100 border-slate-300 text-slate-700',    head: 'text-slate-600',  bar: 'bg-slate-400'   },
  indigo: { chip: 'bg-indigo-100 border-indigo-300 text-indigo-700', head: 'text-indigo-600', bar: 'bg-indigo-500' },
  amber:  { chip: 'bg-amber-100 border-amber-300 text-amber-800',    head: 'text-amber-600',  bar: 'bg-amber-500'  },
  violet: { chip: 'bg-violet-100 border-violet-300 text-violet-700', head: 'text-violet-600', bar: 'bg-violet-500' },
  sky:    { chip: 'bg-sky-100 border-sky-300 text-sky-700',          head: 'text-sky-600',    bar: 'bg-sky-500'    },
  green:  { chip: 'bg-emerald-100 border-emerald-300 text-emerald-700', head: 'text-emerald-600', bar: 'bg-emerald-500' },
}

// Feed line colours by DemoLog kind.
const KIND_TONE = {
  INFO: 'text-gray-500', BOOK: 'text-slate-700', ASSIGN: 'text-indigo-600',
  PICKUP: 'text-indigo-700', HANDOFF: 'text-indigo-700', BIND: 'text-amber-700',
  RETURN: 'text-amber-700', HUB: 'text-violet-700', FLIGHT: 'text-sky-700',
  SCAN: 'text-amber-700', DELIVER: 'text-emerald-700', WARN: 'text-orange-600', ERROR: 'text-red-600',
}

const TERMINAL = ['IDLE', 'DONE', 'STOPPED', 'ERROR']
const shortRef = (r) => (r || '').replace(/^0+/, '') || r

export default function JourneyView() {
  const [originCity, setOriginCity] = useState('delhi')
  const [destCity, setDestCity] = useState('mumbai')
  const [count, setCount] = useState(5)
  const [speed, setSpeed] = useState(60)
  const [starting, setStarting] = useState(false)
  const [events, setEvents] = useState([])
  const lastSeq = useRef(0)
  const feedRef = useRef(null)

  const { data: status } = useQuery({
    queryKey: ['journey-status'],
    queryFn: getRunStatus,
    refetchInterval: 2000,
    retry: 0,
  })

  const phase = status?.phase || 'IDLE'
  const active = !TERMINAL.includes(phase)
  // Keep polling parcels/events while a run is active, or after it finished so the final board stays live.
  const polling = active || (status?.booked ?? 0) > 0

  const { data: journeys = [] } = useQuery({
    queryKey: ['journey-parcels'],
    queryFn: getJourneys,
    refetchInterval: polling ? 1000 : false,
    enabled: polling,
    retry: 0,
  })

  // Event feed: accumulate incrementally with ?after=<lastSeq>.
  useEffect(() => {
    if (!polling) return
    let cancelled = false
    const tick = async () => {
      try {
        const batch = await getRunEvents(lastSeq.current)
        if (cancelled || !batch?.length) return
        lastSeq.current = Math.max(lastSeq.current, ...batch.map((e) => e.seq))
        setEvents((prev) => [...prev, ...batch].slice(-400))
      } catch { /* transient — next tick retries */ }
    }
    tick()
    const id = setInterval(tick, 1000)
    return () => { cancelled = true; clearInterval(id) }
  }, [polling])

  // Auto-scroll the feed to the newest line.
  useEffect(() => {
    if (feedRef.current) feedRef.current.scrollTop = feedRef.current.scrollHeight
  }, [events])

  async function handleRun() {
    if (originCity === destCity) { alert('Pick two different cities for the demo city-pair.'); return }
    setStarting(true)
    setEvents([])
    lastSeq.current = 0
    try {
      await runDay({ originCity, destCity, count: Number(count), speed: Number(speed) })
    } catch (e) {
      alert('Run failed to start: ' + e.message)
    } finally {
      setStarting(false)
    }
  }

  async function handleStop() {
    try { await stopRun() } catch (e) { alert('Stop failed: ' + e.message) }
  }

  // Bucket parcels into their current stage column.
  const columns = STAGES.map(() => [])
  for (const j of journeys) {
    const idx = STAGE_INDEX[j.stage] ?? 0
    columns[idx].push(j)
  }
  const maxIdx = journeys.reduce((m, j) => Math.max(m, STAGE_INDEX[j.stage] ?? 0), 0)

  return (
    <div className="flex flex-1 overflow-hidden">
      {/* Main column: controls + status + pipeline */}
      <div className="flex-1 flex flex-col overflow-hidden">
        {/* Control bar */}
        <div className="flex flex-wrap items-end gap-3 px-4 py-3 bg-white border-b border-gray-200">
          <Field label="Origin">
            <CitySelect value={originCity} onChange={setOriginCity} exclude={destCity} />
          </Field>
          <div className="pb-1.5 text-gray-400">→</div>
          <Field label="Destination">
            <CitySelect value={destCity} onChange={setDestCity} exclude={originCity} />
          </Field>
          <Field label="Parcels">
            <input type="number" min="1" max="40" value={count}
              onChange={(e) => setCount(e.target.value)}
              className="w-20 text-sm border border-gray-300 rounded px-2 py-1" />
          </Field>
          <Field label="Speed ×">
            <input type="number" min="1" max="240" value={speed}
              onChange={(e) => setSpeed(e.target.value)}
              className="w-20 text-sm border border-gray-300 rounded px-2 py-1" />
          </Field>
          <button onClick={handleRun} disabled={active || starting}
            className="text-sm px-4 py-1.5 rounded font-medium bg-blue-600 text-white hover:bg-blue-700 disabled:opacity-40">
            {starting ? 'Starting…' : '▶ Run day'}
          </button>
          <button onClick={handleStop} disabled={!active}
            className="text-sm px-4 py-1.5 rounded font-medium border border-gray-300 hover:bg-gray-50 disabled:opacity-40">
            ■ Stop
          </button>
          <div className="flex-1" />
          <StatusBadge status={status} />
        </div>

        {/* Rollup counters */}
        <div className="flex gap-4 px-4 py-2 bg-gray-50 border-b border-gray-200 text-sm">
          <Counter label="Booked" value={status?.booked ?? 0} tone="text-slate-700" />
          <Counter label="Assigned" value={status?.assigned ?? 0} tone="text-indigo-700" />
          <Counter label="Delivered" value={status?.delivered ?? 0} tone="text-emerald-700" />
          {status?.date && <div className="ml-auto self-center text-gray-400">day {status.date}</div>}
        </div>

        {/* Pipeline strip — kanban columns, tokens flow left→right */}
        <div className="flex-1 overflow-auto p-3">
          {journeys.length === 0 ? (
            <div className="h-full flex items-center justify-center text-gray-400 text-sm">
              {active ? 'Booking parcels…' : 'Choose a city-pair and hit “Run day” to walk the whole M4→M5→M6→M7→M6 journey.'}
            </div>
          ) : (
            <div className="flex gap-2 min-w-max h-full">
              {STAGES.map((s, i) => {
                const t = TONE[s.tone]
                const reached = i <= maxIdx
                return (
                  <div key={s.key} className="flex flex-col w-40 shrink-0">
                    <div className={`flex items-center gap-1.5 px-2 py-1.5 text-xs font-semibold ${t.head}`}>
                      <span>{s.icon}</span><span className="truncate">{s.label}</span>
                      <span className="ml-auto text-gray-400">{columns[i].length || ''}</span>
                    </div>
                    <div className={`h-0.5 rounded ${reached ? t.bar : 'bg-gray-200'}`} />
                    <div className="flex-1 mt-1.5 space-y-1.5 overflow-y-auto">
                      {columns[i].map((j) => <Token key={j.shipmentRef} j={j} tone={t} />)}
                    </div>
                  </div>
                )
              })}
            </div>
          )}
        </div>
      </div>

      {/* Live event feed */}
      <div className="w-96 bg-white border-l border-gray-200 flex flex-col">
        <div className="px-3 py-2 border-b border-gray-200 text-sm font-semibold text-gray-700">
          RabbitMQ feed <span className="text-gray-400 font-normal">· real bus events</span>
        </div>
        <div ref={feedRef} className="flex-1 overflow-y-auto px-3 py-2 font-mono text-[11px] leading-relaxed">
          {events.length === 0 && <div className="text-gray-400">No events yet.</div>}
          {events.map((e) => (
            <div key={e.seq} className="flex gap-2">
              <span className="text-gray-300 shrink-0">{fmtTime(e.at)}</span>
              <span className={`font-semibold shrink-0 ${KIND_TONE[e.kind] || 'text-gray-600'}`}>{e.kind}</span>
              <span className="text-gray-700 break-words">{e.message}</span>
            </div>
          ))}
        </div>
      </div>
    </div>
  )
}

function Token({ j, tone }) {
  return (
    <div className={`rounded border px-2 py-1 text-[11px] ${tone.chip}`} title={j.shipmentRef}>
      <div className="font-semibold">{shortRef(j.shipmentRef)}</div>
      <div className="flex flex-wrap gap-1 mt-0.5 text-[10px] opacity-80">
        {j.daId && <span>DA·{String(j.daId).slice(0, 4)}</span>}
        {j.vanId && <span>🚐{String(j.vanId).slice(0, 4)}</span>}
        {j.flightNo && <span>✈{j.flightNo}</span>}
        {j.standNo && <span>#{j.standNo}</span>}
      </div>
    </div>
  )
}

function StatusBadge({ status }) {
  const phase = status?.phase || 'IDLE'
  const tone = phase === 'ERROR' ? 'bg-red-100 text-red-700'
    : phase === 'DONE' ? 'bg-emerald-100 text-emerald-700'
    : TERMINAL.includes(phase) ? 'bg-gray-100 text-gray-600'
    : 'bg-blue-100 text-blue-700 animate-pulse'
  return (
    <div className="flex items-center gap-2">
      <span className={`text-xs font-semibold px-2.5 py-1 rounded-full ${tone}`}>{phase}</span>
      {status?.error && <span className="text-xs text-red-600 max-w-xs truncate" title={status.error}>{status.error}</span>}
    </div>
  )
}

function Counter({ label, value, tone }) {
  return (
    <div className="flex items-baseline gap-1.5">
      <span className={`text-lg font-bold ${tone}`}>{value}</span>
      <span className="text-gray-500">{label}</span>
    </div>
  )
}

function CitySelect({ value, onChange, exclude }) {
  return (
    <select value={value} onChange={(e) => onChange(e.target.value)}
      className="text-sm border border-gray-300 rounded px-2 py-1 bg-white">
      {Object.entries(CITIES).map(([code, c]) => (
        <option key={code} value={code} disabled={code === exclude}>{c.label}</option>
      ))}
    </select>
  )
}

function Field({ label, children }) {
  return (
    <label className="flex flex-col gap-0.5">
      <span className="text-[11px] uppercase tracking-wide text-gray-400">{label}</span>
      {children}
    </label>
  )
}

function fmtTime(iso) {
  try {
    const d = new Date(iso)
    return d.toLocaleTimeString('en-IN', { hour12: false, hour: '2-digit', minute: '2-digit', second: '2-digit' })
  } catch { return '' }
}
