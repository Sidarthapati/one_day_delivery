import { useEffect, useState } from 'react'

// Van-count (+ capacity/cycle) config and the "Generate Routes" action. Reads current fleet so the
// inputs reflect the stored city_fleet_config; PUTs on generate then drives the M6 plan.
export default function FleetControls({ fleet, onGenerate, loading, disabled }) {
  const [vans, setVans] = useState(6)
  const [capacity, setCapacity] = useState(120)
  const [cycleMax, setCycleMax] = useState(180)

  useEffect(() => {
    if (fleet) {
      setVans(fleet.vansAvailable)
      setCapacity(fleet.capacityPackets)
      setCycleMax(fleet.cycleTimeMaxMinutes)
    }
  }, [fleet])

  return (
    <div className="p-4 border-b border-gray-200">
      <div className="text-sm font-semibold text-gray-700 mb-3">2 · Generate Van Routes (M6)</div>

      <div className="flex items-center gap-3 mb-2">
        <label className="text-sm text-gray-600 flex-1">Vans available</label>
        <input type="number" min={1} value={vans}
          onChange={e => setVans(Math.max(1, Number(e.target.value)))}
          className="w-16 border border-gray-300 rounded px-2 py-1 text-sm text-center" />
      </div>
      <div className="flex items-center gap-3 mb-2">
        <label className="text-sm text-gray-600 flex-1">Capacity (packets)</label>
        <input type="number" min={1} value={capacity}
          onChange={e => setCapacity(Math.max(1, Number(e.target.value)))}
          className="w-16 border border-gray-300 rounded px-2 py-1 text-sm text-center" />
      </div>
      <div className="flex items-center gap-3 mb-3">
        <label className="text-sm text-gray-600 flex-1">Max cycle (min)</label>
        <input type="number" min={30} value={cycleMax}
          onChange={e => setCycleMax(Math.max(30, Number(e.target.value)))}
          className="w-16 border border-gray-300 rounded px-2 py-1 text-sm text-center" />
      </div>

      <button
        onClick={() => onGenerate({ vans_available: vans, capacity_packets: capacity, cycle_time_max_minutes: cycleMax })}
        disabled={loading || disabled}
        className="w-full bg-emerald-600 hover:bg-emerald-700 disabled:bg-gray-300 text-white text-sm font-medium py-2 px-4 rounded transition-colors"
      >
        {loading ? 'Solving routes…' : 'Generate Routes'}
      </button>
      {disabled && (
        <div className="text-xs text-gray-400 mt-2">Generate territories first.</div>
      )}
    </div>
  )
}
