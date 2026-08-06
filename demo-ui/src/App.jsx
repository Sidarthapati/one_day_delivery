import { useState, useMemo, useEffect } from 'react'
import { useQuery } from '@tanstack/react-query'
import HexMap from './components/HexMap.jsx'
import Legend from './components/Legend.jsx'
import DaControls from './components/DaControls.jsx'
import SeedControls from './components/SeedControls.jsx'
import FleetControls from './components/FleetControls.jsx'
import HexPanel from './components/HexPanel.jsx'
import ProposalPanel from './components/ProposalPanel.jsx'
import RoutesPanel from './components/RoutesPanel.jsx'
import ExecutionView from './components/ExecutionView.jsx'
import DispatchView from './components/DispatchView.jsx'
import DaAppView from './components/DaAppView.jsx'
import BookingConsole from './components/BookingConsole.jsx'
import { hashDaColor } from './utils/daColors.js'
import { buildRoutes } from './utils/buildRoutes.js'
import { CITIES, PLAN_DATE } from './cities.js'
import {
  fetchTiles, fetchAssignments, seedDemand, demandCount, replan, approveProposal,
} from './api/gridApi.js'
import {
  getFleet, putFleet, getNodes, getPlan, m6Replan, m6Approve, getAllStops, getDeferredVertices,
} from './api/routingApi.js'

// Planning plans for tomorrow (PLAN_DATE); Execution + Dispatch operate on TODAY. Surfacing both in
// the toolbar keeps the "I planned 20 DAs but Dispatch shows a different roster" confusion from biting.
const _now = new Date()
const TODAY = `${_now.getFullYear()}-${String(_now.getMonth() + 1).padStart(2, '0')}-${String(_now.getDate()).padStart(2, '0')}`

export default function App() {
  const [world, setWorld] = useState('logistics') // booking (M1·M2·M4) | logistics (M3·M5·M6)
  const [cityCode, setCityCode] = useState('delhi')
  const [view, setView] = useState('planning') // planning | execution
  const [mode, setMode] = useState('demand') // demand | territories | routes
  const [selectedHexId, setSelectedHexId] = useState(null)
  const [proposal, setProposal] = useState(null)
  const [plan, setPlan] = useState(null)
  const [stops, setStops] = useState([])
  const [routes, setRoutes] = useState([])
  const [deferredVertices, setDeferredVertices] = useState([])
  // Vertex overlays for routes mode: deferred shown by default (the whole point — see what's left out);
  // covered off by default since the numbered van markers already mark them.
  const [showVertices, setShowVertices] = useState({ covered: false, deferred: true })
  const [selectedVan, setSelectedVan] = useState(null)
  const [selectedLoop, setSelectedLoop] = useState(0)

  // Selecting a van resets the loop view to the first loop.
  function selectVan(vanId) { setSelectedVan(vanId); setSelectedLoop(0) }
  const [genT, setGenT] = useState(false)
  const [genR, setGenR] = useState(false)
  const [seeding, setSeeding] = useState(false)
  const [lastSeed, setLastSeed] = useState(null)
  const [refreshKey, setRefreshKey] = useState(0)

  const city = CITIES[cityCode]
  const cityId = city.id

  const { data: tiles = [], isLoading: tilesLoading } = useQuery({
    queryKey: ['tiles', cityCode, refreshKey],
    queryFn: () => fetchTiles(cityCode, PLAN_DATE),
    retry: 1,
  })

  const { data: assignments = [] } = useQuery({
    queryKey: ['assignments', cityCode, refreshKey],
    queryFn: () => fetchAssignments(cityCode, PLAN_DATE),
    enabled: mode !== 'demand',
    retry: 1,
  })

  const { data: fleet } = useQuery({
    queryKey: ['fleet', cityId],
    queryFn: () => getFleet(cityId),
    retry: 1,
  })

  // Hub + airport coords — loaded for every city so the markers show in all map modes.
  const { data: nodes = [] } = useQuery({
    queryKey: ['nodes', cityId],
    queryFn: () => getNodes(cityId),
    retry: 1,
  })

  // Restore a previously-saved route plan from the DB on load/city-change (404 → no plan yet).
  const { data: savedPlan = null } = useQuery({
    queryKey: ['savedPlan', cityId, refreshKey],
    queryFn: async () => { try { return await getPlan(cityId, PLAN_DATE) } catch { return null } },
    retry: 0,
  })

  // When a saved plan + nodes are available, rebuild the route geometry so a refresh shows the routes.
  useEffect(() => {
    if (!savedPlan || !nodes.length || plan) return
    let cancelled = false
    ;(async () => {
      try {
        const stopsData = await getAllStops(cityId, PLAN_DATE)
        const built = await buildRoutes(stopsData, nodes)
        const deferred = await getDeferredVertices(cityId, PLAN_DATE).catch(() => [])
        if (!cancelled) { setPlan(savedPlan); setStops(stopsData); setRoutes(built); setDeferredVertices(deferred) }
      } catch { /* no stops / plan gone — ignore */ }
    })()
    return () => { cancelled = true }
  }, [savedPlan, nodes, cityId, plan])

  const assignmentMap = useMemo(() => {
    const m = {}
    if (proposal?.regions?.length) {
      proposal.regions.forEach(r => r.hexIds?.forEach(hexId => { m[hexId] = r.daId }))
    } else {
      assignments.forEach(a => { m[a.hexId] = a.daId })
    }
    return m
  }, [proposal, assignments])

  const uniqueDaIds = useMemo(() => {
    if (proposal?.regions?.length) return [...new Set(proposal.regions.map(r => r.daId))]
    return [...new Set(assignments.map(a => a.daId))]
  }, [proposal, assignments])

  const vans = useMemo(() => [...new Set(stops.map(s => s.vanId))], [stops])
  const hasTerritories = (proposal?.regions?.length || assignments.length) > 0

  // Covered meeting vertices = the served stop coordinates (one per vertex, deduped). Derived from
  // routes (set in both the generate and the on-load restore paths) so the overlay survives a refresh.
  const coveredVertices = useMemo(() => {
    const seen = new Set(); const out = []
    for (const r of routes) for (const m of (r.markers || [])) {
      if (m.lat == null) continue
      const key = `${m.lat},${m.lon}`
      if (!seen.has(key)) { seen.add(key); out.push({ lat: m.lat, lon: m.lon }) }
    }
    return out
  }, [routes])
  const toggleVertices = kind => setShowVertices(v => ({ ...v, [kind]: !v[kind] }))

  function handleCityChange(newCity) {
    setCityCode(newCity)
    setSelectedHexId(null); setProposal(null); setPlan(null)
    setStops([]); setRoutes([]); setDeferredVertices([]); setSelectedVan(null)
    setMode('demand')
    setRefreshKey(k => k + 1)
  }

  async function handleSeedDemand(opts) {
    setSeeding(true)
    try {
      const res = await seedDemand(cityCode, PLAN_DATE, opts)
      setLastSeed(res.seedUsed)
      setMode('demand')
      setRefreshKey(k => k + 1)
    } catch (e) {
      alert('Seed demand failed: ' + e.message)
    } finally {
      setSeeding(false)
    }
  }

  async function handleGenerateTerritories(daCount) {
    setGenT(true)
    try {
      // Reuse the existing demand snapshot — seeding is a separate, explicit step now. Hard-fail
      // if the city/date has no demand rather than replanning over an empty surface.
      const { count } = await demandCount(cityCode, PLAN_DATE)
      if (!count) {
        alert('No demand seeded for this city/date yet — click "Seed demand" first.')
        return
      }
      const prop = await replan(cityCode, daCount, PLAN_DATE)
      await approveProposal(prop.id)
      // On this branch ACTIVE is collapsed into APPROVED — M6 territory read + the grid map both read
      // APPROVED, so there is no separate activate step (calling it would move rows OUT of APPROVED).
      setProposal({ ...prop, status: 'APPROVED' })
      setPlan(null); setStops([]); setRoutes([]); setDeferredVertices([]); setSelectedVan(null)
      setSelectedHexId(null)
      setMode('territories')
      setRefreshKey(k => k + 1)
    } catch (e) {
      alert('Generate territories failed: ' + e.message)
    } finally {
      setGenT(false)
    }
  }

  async function handleGenerateRoutes(fleetPatch) {
    setGenR(true)
    try {
      await putFleet(cityId, fleetPatch)
      const p = await m6Replan(cityId, PLAN_DATE)
      if (!p.vansUsed) {
        setPlan(p); setStops([]); setRoutes([]); setDeferredVertices([]); setMode('routes')
        alert(`No feasible routes: ${p.notes || 'under-provisioned'}`)
        return
      }
      await m6Approve(p.planId)
      const stopsData = await getAllStops(cityId, PLAN_DATE)
      const nodesData = nodes.length ? nodes : await getNodes(cityId)
      const built = await buildRoutes(stopsData, nodesData)
      const deferred = await getDeferredVertices(cityId, PLAN_DATE).catch(() => [])
      setPlan(p); setStops(stopsData); setRoutes(built); setDeferredVertices(deferred)
      setSelectedVan(null); setSelectedHexId(null)
      setMode('routes')
    } catch (e) {
      alert('Generate routes failed: ' + e.message)
    } finally {
      setGenR(false)
    }
  }

  const MODES = [
    { k: 'demand', label: 'Demand heatmap' },
    { k: 'territories', label: 'DA territories' },
    { k: 'routes', label: 'Van routes' },
  ]

  if (tilesLoading) {
    return <div className="flex h-full items-center justify-center text-gray-500">Loading {city.label} grid…</div>
  }

  const activeHexCount = tiles.filter(t => t.active).length

  return (
    <div className="flex flex-col h-full">
      {/* Top-level world switch — one dashboard across M1–M6 */}
      <div className="flex items-center gap-3 px-4 py-1.5 bg-gray-900 text-white z-20">
        <div className="font-bold tracking-tight">1DD · OneDay</div>
        <div className="flex gap-1 ml-2">
          {[['booking', 'Booking & Ops', 'M1·M2·M4'], ['logistics', 'Logistics', 'M3·M5·M6']].map(([k, label, sub]) => (
            <button key={k} onClick={() => setWorld(k)}
              className={`text-sm px-3 py-1 rounded transition-colors ${
                world === k ? 'bg-white text-gray-900' : 'bg-gray-700 hover:bg-gray-600 text-gray-100'}`}>
              {label} <span className="opacity-60 text-xs">{sub}</span>
            </button>
          ))}
        </div>
      </div>

      {world === 'booking' && <BookingConsole />}

      {world === 'logistics' && (<>
      {/* Toolbar */}
      <div className="flex items-center gap-4 px-4 py-2 bg-white border-b border-gray-200 shadow-sm z-10">
        <div className="font-bold text-gray-800">OneDay — {view === 'dispatch' ? 'M5 Dispatch (ops control tower)' : view === 'da' ? '📱 DA Phone (field view)' : `M6 ${view === 'execution' ? 'Execution' : 'Route Planning'}`}</div>
        <select value={cityCode} onChange={e => handleCityChange(e.target.value)}
          className="text-sm border border-gray-300 rounded px-2 py-1 text-gray-700 bg-white">
          {Object.entries(CITIES).map(([code, c]) => <option key={code} value={code}>{c.label}</option>)}
        </select>
        <div className="flex gap-1 ml-1">
          {[['planning', 'Planning'], ['execution', 'Execution'], ['dispatch', 'Dispatch'], ['da', '📱 DA Phone']].map(([k, label]) => (
            <button key={k} onClick={() => setView(k)}
              className={`text-sm px-3 py-1 border rounded transition-colors ${
                view === k ? 'bg-gray-800 text-white border-gray-800' : 'border-gray-300 hover:bg-gray-50'}`}>
              {label}
            </button>
          ))}
        </div>
        {view === 'planning' && <div className="text-sm text-gray-500">{activeHexCount} active hexes · {PLAN_DATE}</div>}
        {/* Date scope: planning is for tomorrow, execution/dispatch run today — make the split explicit. */}
        {view === 'planning' ? (
          <span className="text-xs px-2 py-0.5 rounded bg-amber-50 text-amber-700 border border-amber-200"
            title={`Planning produces TOMORROW's plan (${PLAN_DATE}). Execution & Dispatch run on TODAY (${TODAY}).`}>
            📅 planning for tomorrow · {PLAN_DATE}
          </span>
        ) : (
          <span className="text-xs px-2 py-0.5 rounded bg-emerald-50 text-emerald-700 border border-emerald-200"
            title={`Execution & Dispatch operate on TODAY (${TODAY}) — today's approved roster, not the plan you build in Planning (${PLAN_DATE}, for tomorrow).`}>
            📅 operating today · {TODAY}
          </span>
        )}
        <div className="flex-1" />
        {view === 'planning' && (
          <div className="flex gap-1">
            {MODES.map(m => (
              <button key={m.k} onClick={() => setMode(m.k)}
                className={`text-sm px-3 py-1 border rounded transition-colors ${
                  mode === m.k ? 'bg-blue-600 text-white border-blue-600' : 'border-gray-300 hover:bg-gray-50'}`}>
                {m.label}
              </button>
            ))}
          </div>
        )}
      </div>

      {/* Main */}
      {view === 'execution' && (
        <ExecutionView cityCode={cityCode} cityId={cityId} center={city.center} nodes={nodes} onCityChange={handleCityChange} />
      )}
      {view === 'dispatch' && <DispatchView cityId={cityId} cityCode={cityCode} />}
      {view === 'da' && <DaAppView cityId={cityId} cityCode={cityCode} />}
      {view === 'planning' && (
      <div className="flex flex-1 overflow-hidden">
        <div className="flex-1 relative">
          {tiles.length > 0 && (
            <>
              <HexMap
                tiles={tiles} assignmentMap={assignmentMap} mode={mode}
                onHexClick={id => setSelectedHexId(id === selectedHexId ? null : id)}
                selectedHexId={selectedHexId} center={city.center}
                routes={routes} nodes={nodes} selectedVan={selectedVan} vanColor={hashDaColor}
                coveredVertices={coveredVertices} deferredVertices={deferredVertices}
                showVertices={showVertices}
              />
              {mode !== 'routes' && <Legend mode={mode} daIds={uniqueDaIds} />}
            </>
          )}
        </div>

        {/* Sidebar */}
        <div className="w-80 bg-white border-l border-gray-200 flex flex-col overflow-y-auto">
          <SeedControls onSeed={handleSeedDemand} loading={seeding} lastSeed={lastSeed} />
          <DaControls onGenerate={handleGenerateTerritories} loading={genT} />
          <FleetControls fleet={fleet} onGenerate={handleGenerateRoutes}
            loading={genR} disabled={!hasTerritories} />

          {selectedHexId && (
            <HexPanel hexId={selectedHexId} cityCode={cityCode} date={PLAN_DATE}
              onClose={() => setSelectedHexId(null)} onStale={() => setRefreshKey(k => k + 1)} />
          )}

          {!selectedHexId && mode === 'routes' && plan && (
            <RoutesPanel plan={plan} routes={routes}
              selectedVan={selectedVan} onSelectVan={selectVan}
              selectedLoop={selectedLoop} onSelectLoop={setSelectedLoop} vanColor={hashDaColor}
              coveredCount={coveredVertices.length} deferredCount={deferredVertices.length}
              showVertices={showVertices} onToggleVertices={toggleVertices} />
          )}

          {!selectedHexId && mode !== 'routes' && proposal && (
            <ProposalPanel proposal={proposal} date={PLAN_DATE} />
          )}

          {!selectedHexId && !proposal && mode !== 'routes' && (
            <div className="p-4 text-sm text-gray-400">
              <strong className="text-gray-600">1 · Seed demand</strong> once (pick a seed to make it
              reproducible). Then <strong className="text-gray-600">2 · Generate territories</strong> runs
              the M3 nightly replan over that demand, approves + activates it. Then
              <strong className="text-gray-600"> 3 · Generate routes</strong> sets the fleet and solves the
              M6 van plan. Territory/route runs reuse the seeded demand — re-seed only when you want a new
              demand surface.
              <br /><br />Click any hex to inspect or edit its demand.
            </div>
          )}
        </div>
      </div>
      )}
      </>)}
    </div>
  )
}
