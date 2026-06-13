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
import { hashDaColor } from './utils/daColors.js'
import { CITIES, PLAN_DATE } from './cities.js'
import {
  fetchTiles, fetchAssignments, seedDemand, demandCount, replan, approveProposal, activateAssignments,
} from './api/gridApi.js'
import {
  getFleet, putFleet, getNodes, getPlan, m6Replan, m6Approve, getAllStops, osrmRoute,
} from './api/routingApi.js'

// Build per-van route geometry. Every loop visits the SAME vertices in the SAME order (only the
// times differ), so we road-snap ONE representative loop (hub → vertices → hub) via OSRM and report
// per-loop vs whole-day distance. Returns one entry per van:
//   { vanId, geometry, markers[{seq,lat,lon,visits[]}], stopsByLoop[][], loopIndices[],
//     loopCount, vertexCount, perLoopDistanceKm, totalDistanceKm }
async function buildRoutes(stops, nodes) {
  const hub = nodes.find(n => n.kind === 'HUB')
  const hubLL = hub ? [hub.lat, hub.lon] : null
  const byVan = new Map()
  for (const s of stops) {
    if (!byVan.has(s.vanId)) byVan.set(s.vanId, [])
    byVan.get(s.vanId).push(s)
  }

  const result = []
  for (const [vanId, vanStops] of byVan) {
    vanStops.sort((a, b) => a.loopIndex - b.loopIndex || a.stopSeq - b.stopSeq)
    const loopIndices = [...new Set(vanStops.map(s => s.loopIndex))].sort((a, b) => a - b)
    const loopCount = loopIndices.length

    // Representative loop = the first loop, ordered by stop sequence (one entry per vertex).
    const repStops = vanStops.filter(s => s.loopIndex === loopIndices[0]).sort((a, b) => a.stopSeq - b.stopSeq)
    const vertexCount = repStops.length

    // All visit times for each vertex, grouped by its sequence position (same across loops).
    const visitsBySeq = {}
    for (const s of vanStops) {
      (visitsBySeq[s.stopSeq] ||= []).push({
        loop: s.loopIndex, arr: s.plannedArrival, dep: s.plannedDeparture,
        deliver: s.deliverQty, collect: s.collectQty, load: s.loadAfter,
      })
    }
    const markers = repStops.map(s => ({
      stopId: s.stopId, seq: s.stopSeq, lat: s.lat, lon: s.lon, visits: visitsBySeq[s.stopSeq] || [],
    }))

    // Stops grouped per loop (each already ordered) for the timeline.
    const stopsByLoop = loopIndices.map(li =>
      vanStops.filter(s => s.loopIndex === li).sort((a, b) => a.stopSeq - b.stopSeq))

    // One-loop road geometry: hub → vertices → hub.
    const waypoints = []
    if (hubLL) waypoints.push(hubLL)
    repStops.forEach(s => waypoints.push([s.lat, s.lon]))
    if (hubLL) waypoints.push(hubLL)
    const r = await osrmRoute(waypoints)
    const perLoopDistanceKm = r?.distanceKm ?? null

    result.push({
      vanId,
      geometry: r?.geometry || waypoints,
      markers,
      stopsByLoop,
      loopIndices,
      loopCount,
      vertexCount,
      perLoopDistanceKm,
      totalDistanceKm: perLoopDistanceKm != null ? perLoopDistanceKm * loopCount : null,
    })
  }
  return result
}

export default function App() {
  const [cityCode, setCityCode] = useState('delhi')
  const [mode, setMode] = useState('demand') // demand | territories | routes
  const [selectedHexId, setSelectedHexId] = useState(null)
  const [proposal, setProposal] = useState(null)
  const [plan, setPlan] = useState(null)
  const [stops, setStops] = useState([])
  const [routes, setRoutes] = useState([])
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
        if (!cancelled) { setPlan(savedPlan); setRoutes(built) }
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

  function handleCityChange(newCity) {
    setCityCode(newCity)
    setSelectedHexId(null); setProposal(null); setPlan(null)
    setStops([]); setRoutes([]); setSelectedVan(null)
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
      await activateAssignments(cityCode, PLAN_DATE)
      // The flow already approved + activated it; reflect that so the panel shows LIVE, not a
      // (redundant, re-approve → error) Approve button.
      setProposal({ ...prop, status: 'APPROVED' })
      setPlan(null); setStops([]); setRoutes([]); setSelectedVan(null)
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
        setPlan(p); setStops([]); setRoutes([]); setMode('routes')
        alert(`No feasible routes: ${p.notes || 'under-provisioned'}`)
        return
      }
      await m6Approve(p.planId)
      const stopsData = await getAllStops(cityId, PLAN_DATE)
      const nodesData = nodes.length ? nodes : await getNodes(cityId)
      const built = await buildRoutes(stopsData, nodesData)
      setPlan(p); setStops(stopsData); setRoutes(built)
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
      {/* Toolbar */}
      <div className="flex items-center gap-4 px-4 py-2 bg-white border-b border-gray-200 shadow-sm z-10">
        <div className="font-bold text-gray-800">OneDay — M6 Route Planning</div>
        <select value={cityCode} onChange={e => handleCityChange(e.target.value)}
          className="text-sm border border-gray-300 rounded px-2 py-1 text-gray-700 bg-white">
          {Object.entries(CITIES).map(([code, c]) => <option key={code} value={code}>{c.label}</option>)}
        </select>
        <div className="text-sm text-gray-500">{activeHexCount} active hexes · {PLAN_DATE}</div>
        <div className="flex-1" />
        <div className="flex gap-1">
          {MODES.map(m => (
            <button key={m.k} onClick={() => setMode(m.k)}
              className={`text-sm px-3 py-1 border rounded transition-colors ${
                mode === m.k ? 'bg-blue-600 text-white border-blue-600' : 'border-gray-300 hover:bg-gray-50'}`}>
              {m.label}
            </button>
          ))}
        </div>
      </div>

      {/* Main */}
      <div className="flex flex-1 overflow-hidden">
        <div className="flex-1 relative">
          {tiles.length > 0 && (
            <>
              <HexMap
                tiles={tiles} assignmentMap={assignmentMap} mode={mode}
                onHexClick={id => setSelectedHexId(id === selectedHexId ? null : id)}
                selectedHexId={selectedHexId} center={city.center}
                routes={routes} nodes={nodes} selectedVan={selectedVan} vanColor={hashDaColor}
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
              selectedLoop={selectedLoop} onSelectLoop={setSelectedLoop} vanColor={hashDaColor} />
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
    </div>
  )
}
