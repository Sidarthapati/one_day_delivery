// M6 plan summary + clickable van list + per-loop stop timeline. Each van repeats one loop several
// times a day; the map shows that single loop (one marker per vertex), and here you pick which loop
// to read the schedule for. Distances are reported per-loop and whole-day (per-loop × loops).
export default function RoutesPanel({ plan, routes, selectedVan, onSelectVan, selectedLoop, onSelectLoop, vanColor }) {
  if (!plan) return null

  const flagColor = plan.provisioningFlag === 'UNDER_PROVISIONED' ? 'text-red-600' : 'text-emerald-600'
  // The backend sets `notes` only when the recommended count is a structural fallback (= vertex count,
  // not a real fleet size) — show "—" + the reason instead of a misleading number.
  const recoUnavailable = !!plan.notes
  const sel = routes.find(r => r.vanId === selectedVan && r.stopsByLoop)
  const loopIdx = sel ? Math.min(selectedLoop, sel.stopsByLoop.length - 1) : 0
  const timeline = sel ? sel.stopsByLoop[loopIdx] || [] : []

  // Each van now runs its own cadence, so loops/day and cycle vary across the fleet — show the range.
  const range = (vals, fallback) => {
    const a = vals.filter(v => v != null)
    if (!a.length) return fallback
    const lo = Math.min(...a), hi = Math.max(...a)
    return lo === hi ? `${lo}` : `${lo}–${hi}`
  }

  return (
    <div className="p-4 text-sm">
      <div className="font-semibold text-gray-700 mb-2">Route plan · {plan.status}</div>

      <div className="grid grid-cols-2 gap-y-1 mb-3 text-gray-600">
        <div>Vans used</div><div className="text-right font-medium text-gray-800">{plan.vansUsed}</div>
        <div>Recommended vans</div>
        <div className="text-right font-medium text-gray-800">{recoUnavailable ? '—' : plan.recommendedVanCount}</div>
        <div>Provisioning</div><div className={`text-right font-medium ${flagColor}`}>{plan.provisioningFlag}</div>
        <div>Loops / day</div><div className="text-right font-medium text-gray-800">{range(routes.map(r => r.loopCount), plan.nLoops)}</div>
        <div>Cycle / loop (min)</div><div className="text-right font-medium text-gray-800">{range(routes.map(r => r.loopMinutes), plan.realisedCycleMinutes)}</div>
      </div>

      {plan.notes && (
        <div className="mb-3 text-xs rounded border border-amber-300 bg-amber-50 text-amber-800 px-2 py-1.5">
          <span className="font-semibold">⚠ Fleet recommendation unavailable.</span> {plan.notes}
        </div>
      )}

      <div className="text-xs font-semibold text-gray-500 uppercase mb-1">Vans — click to isolate</div>
      <button onClick={() => onSelectVan(null)}
        className={`w-full text-left text-xs px-2 py-1.5 rounded border mb-1 ${
          !selectedVan ? 'bg-gray-800 text-white border-gray-800' : 'border-gray-200 hover:bg-gray-50'}`}>
        Show all vans
      </button>
      <div className="space-y-1 mb-3">
        {routes.map((r, i) => {
          const on = selectedVan === r.vanId
          return (
            <button key={r.vanId} onClick={() => onSelectVan(on ? null : r.vanId)}
              className={`w-full text-left text-xs px-2 py-1.5 rounded border transition-colors ${
                on ? 'border-gray-800 bg-gray-50' : 'border-gray-200 hover:bg-gray-50'}`}>
              <div className="flex items-center gap-2">
                <span className="inline-block w-3.5 h-3.5 rounded-full flex-shrink-0 border border-white shadow"
                  style={{ background: vanColor(r.vanId) }} />
                <span className="font-semibold text-gray-700">Van {i + 1}</span>
                <span className="ml-auto text-gray-500">{r.vertexCount} stops · {r.loopCount} loops</span>
              </div>
              <div className="text-gray-400 pl-5 mt-0.5">
                {r.loopMinutes != null && `${r.loopMinutes} min/loop · `}
                {r.perLoopDistanceKm != null
                  ? `${r.perLoopDistanceKm.toFixed(0)} km/loop · ${r.totalDistanceKm.toFixed(0)} km/day`
                  : 'distance n/a'}
              </div>
            </button>
          )
        })}
      </div>

      {sel && (
        <>
          <div className="text-xs font-semibold text-gray-500 uppercase mb-1">
            Van {routes.indexOf(sel) + 1} · loop schedule
          </div>
          <div className="flex flex-wrap gap-1 mb-2">
            {sel.loopIndices.map((li, i) => (
              <button key={li} onClick={() => onSelectLoop(i)}
                className={`text-xs px-2 py-1 rounded border ${
                  i === loopIdx ? 'bg-gray-800 text-white border-gray-800' : 'border-gray-200 text-gray-600 hover:bg-gray-50'}`}>
                Loop {i + 1}
              </button>
            ))}
          </div>
          <div className="space-y-1 max-h-72 overflow-y-auto">
            {timeline.map(s => (
              <div key={s.stopId} className="flex items-center gap-2 text-xs text-gray-600 border-b border-gray-100 pb-1">
                <span className="inline-flex items-center justify-center w-5 h-5 rounded-full text-white font-bold flex-shrink-0"
                  style={{ background: vanColor(sel.vanId), fontSize: 10 }}>{s.stopSeq}</span>
                <span className="font-mono">{s.plannedArrival}–{s.plannedDeparture}</span>
                <span className="ml-auto text-gray-500">▼{s.deliverQty} ▲{s.collectQty}</span>
                <span className="text-gray-400">load {s.loadAfter}</span>
              </div>
            ))}
          </div>
        </>
      )}
      {!sel && <div className="text-xs text-gray-400">Select a van above to see its loop schedule, one loop at a time.</div>}
    </div>
  )
}
