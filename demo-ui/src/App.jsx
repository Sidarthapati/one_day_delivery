import { useState, useMemo } from 'react'
import { useQuery } from '@tanstack/react-query'
import HexMap from './components/HexMap.jsx'
import Legend from './components/Legend.jsx'
import DaControls from './components/DaControls.jsx'
import HexPanel from './components/HexPanel.jsx'
import ProposalPanel from './components/ProposalPanel.jsx'
import { fetchTiles, fetchAssignments, replan } from './api/gridApi.js'

const CITIES = {
  delhi:     { label: 'Delhi',     center: [28.6139, 77.2090] },
  mumbai:    { label: 'Mumbai',    center: [19.0760, 72.8777] },
  bangalore: { label: 'Bangalore', center: [12.9716, 77.5946] },
  hyderabad: { label: 'Hyderabad', center: [17.3850, 78.4867] },
  chennai:   { label: 'Chennai',   center: [13.0827, 80.2707] },
}

const _d = new Date()
const TODAY = `${_d.getFullYear()}-${String(_d.getMonth() + 1).padStart(2, '0')}-${String(_d.getDate()).padStart(2, '0')}`

export default function App() {
  const [cityCode, setCityCode] = useState('delhi')
  const [mode, setMode] = useState('demand')
  const [selectedHexId, setSelectedHexId] = useState(null)
  const [proposal, setProposal] = useState(null)
  const [generating, setGenerating] = useState(false)
  const [stale, setStale] = useState(false)
  const [refreshKey, setRefreshKey] = useState(0)

  const city = CITIES[cityCode]

  const { data: tiles = [], isLoading: tilesLoading } = useQuery({
    queryKey: ['tiles', cityCode, TODAY, refreshKey],
    queryFn: () => fetchTiles(cityCode, TODAY),
    retry: 1,
  })

  const { data: assignments = [] } = useQuery({
    queryKey: ['assignments', cityCode, TODAY, proposal?.id],
    queryFn: () => fetchAssignments(cityCode, TODAY),
    enabled: mode === 'assignment',
    retry: 1,
  })

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

  function handleCityChange(newCity) {
    setCityCode(newCity)
    setSelectedHexId(null)
    setProposal(null)
    setStale(false)
    setRefreshKey(k => k + 1)
  }

  async function handleGenerate(daCount) {
    setGenerating(true)
    setStale(false)
    try {
      const result = await replan(cityCode, daCount, TODAY)
      setProposal(result)
      setMode('assignment')
      setSelectedHexId(null)
    } catch (e) {
      alert('Failed to generate plan: ' + e.message)
    } finally {
      setGenerating(false)
    }
  }

  function handleStale() {
    setStale(true)
    setRefreshKey(k => k + 1)
  }

  if (tilesLoading) {
    return (
      <div className="flex h-full items-center justify-center text-gray-500">
        Loading {city.label} grid…
      </div>
    )
  }

  const activeHexCount = tiles.filter(t => t.active).length

  return (
    <div className="flex flex-col h-full">
      {/* Toolbar */}
      <div className="flex items-center gap-4 px-4 py-2 bg-white border-b border-gray-200 shadow-sm z-10">
        <div className="font-bold text-gray-800">OneDay Delivery — H3 Grid</div>

        {/* City selector */}
        <select
          value={cityCode}
          onChange={e => handleCityChange(e.target.value)}
          className="text-sm border border-gray-300 rounded px-2 py-1 text-gray-700 bg-white"
        >
          {Object.entries(CITIES).map(([code, c]) => (
            <option key={code} value={code}>{c.label}</option>
          ))}
        </select>

        <div className="text-sm text-gray-500">{activeHexCount} active hexes · {TODAY}</div>
        <div className="flex-1" />
        <button
          onClick={() => setMode(m => m === 'demand' ? 'assignment' : 'demand')}
          className="text-sm px-3 py-1 border border-gray-300 rounded hover:bg-gray-50 transition-colors"
        >
          View: {mode === 'demand' ? 'Demand heatmap' : 'DA territories'}
        </button>
      </div>

      {/* Stale banner */}
      {stale && (
        <div className="bg-yellow-50 border-b border-yellow-200 px-4 py-1.5 text-sm text-yellow-800">
          Plan is stale — click <strong>Generate Plan</strong> to see updated territories.
        </div>
      )}

      {/* Main layout */}
      <div className="flex flex-1 overflow-hidden">
        {/* Map */}
        <div className="flex-1 relative">
          {tiles.length > 0 && (
            <>
              <HexMap
                tiles={tiles}
                assignmentMap={assignmentMap}
                mode={mode}
                onHexClick={id => setSelectedHexId(id === selectedHexId ? null : id)}
                selectedHexId={selectedHexId}
                center={city.center}
              />
              <Legend mode={mode} daIds={uniqueDaIds} />
            </>
          )}
          {tiles.length === 0 && !tilesLoading && (
            <div className="flex h-full items-center justify-center text-gray-400 text-sm">
              No hexes found. Run POST /api/grid/admin/init?cityCode={cityCode} first.
            </div>
          )}
        </div>

        {/* Sidebar */}
        <div className="w-72 bg-white border-l border-gray-200 flex flex-col overflow-y-auto">
          <DaControls onGenerate={handleGenerate} loading={generating} />

          {selectedHexId && (
            <HexPanel
              hexId={selectedHexId}
              cityCode={cityCode}
              date={TODAY}
              onClose={() => setSelectedHexId(null)}
              onStale={handleStale}
            />
          )}

          {proposal && !selectedHexId && (
            <ProposalPanel proposal={proposal} date={TODAY} />
          )}

          {!proposal && !selectedHexId && (
            <div className="p-4 text-sm text-gray-400">
              Set the number of DAs and click <strong className="text-gray-500">Generate Plan</strong> to assign territories.
              <br /><br />
              Click any hex on the map to view or edit its demand.
            </div>
          )}
        </div>
      </div>
    </div>
  )
}
