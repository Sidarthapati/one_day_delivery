// M6 plan summary + clickable van list + per-van stop timeline. `routes` carries per-van
// geometry/distance/loops; selecting a van isolates it on the map (and the map zooms to it).
export default function RoutesPanel({ plan, routes, selectedVan, onSelectVan, vanColor }) {
  if (!plan) return null

  const flagColor = plan.provisioningFlag === 'UNDER_PROVISIONED' ? 'text-red-600' : 'text-emerald-600'
  const sel = routes.find(r => r.vanId === selectedVan)

  return (
    <div className="p-4 text-sm">
      <div className="font-semibold text-gray-700 mb-2">Route plan · {plan.status}</div>

      <div className="grid grid-cols-2 gap-y-1 mb-3 text-gray-600">
        <div>Vans used</div><div className="text-right font-medium text-gray-800">{plan.vansUsed}</div>
        <div>Recommended</div><div className="text-right font-medium text-gray-800">{plan.recommendedVanCount}</div>
        <div>Provisioning</div><div className={`text-right font-medium ${flagColor}`}>{plan.provisioningFlag}</div>
        <div>Loops / day</div><div className="text-right font-medium text-gray-800">{plan.nLoops}</div>
        <div>Cycle (min)</div><div className="text-right font-medium text-gray-800">{plan.realisedCycleMinutes}</div>
      </div>

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
              className={`w-full flex items-center gap-2 text-xs px-2 py-1.5 rounded border transition-colors ${
                on ? 'border-gray-800 bg-gray-50' : 'border-gray-200 hover:bg-gray-50'}`}>
              <span className="inline-block w-3.5 h-3.5 rounded-full flex-shrink-0 border border-white shadow"
                style={{ background: vanColor(r.vanId) }} />
              <span className="font-semibold text-gray-700">Van {i + 1}</span>
              <span className="ml-auto text-gray-500">
                {r.stops.length} stops{r.distanceKm != null ? ` · ${r.distanceKm.toFixed(0)} km` : ''}
                {r.loops ? ` · ${r.loops} loop${r.loops > 1 ? 's' : ''}` : ''}
              </span>
            </button>
          )
        })}
      </div>

      {sel && (
        <>
          <div className="text-xs font-semibold text-gray-500 uppercase mb-1">
            Van {routes.indexOf(sel) + 1} · stop timeline
          </div>
          <div className="space-y-1 max-h-72 overflow-y-auto">
            {sel.stops.map((s, i) => (
              <div key={s.stopId} className="flex items-center gap-2 text-xs text-gray-600 border-b border-gray-100 pb-1">
                <span className="inline-flex items-center justify-center w-5 h-5 rounded-full text-white font-bold flex-shrink-0"
                  style={{ background: vanColor(sel.vanId), fontSize: 10 }}>{i + 1}</span>
                <span className="font-mono">{s.plannedArrival}</span>
                <span className="ml-auto text-gray-500">▼{s.deliverQty} ▲{s.collectQty}</span>
                <span className="text-gray-400">load {s.loadAfter}</span>
              </div>
            ))}
          </div>
        </>
      )}
      {!sel && <div className="text-xs text-gray-400">Select a van above to see its stop-by-stop timeline.</div>}
    </div>
  )
}
