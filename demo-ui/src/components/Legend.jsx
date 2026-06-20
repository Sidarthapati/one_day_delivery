const DA_COLORS = [
  '#e6194b', '#3cb44b', '#4363d8', '#f58231', '#911eb4',
  '#42d4f4', '#f032e6', '#bfef45', '#fabed4', '#469990',
  '#dcbeff', '#9A6324',
]

export default function Legend({ mode, daIds }) {
  return (
    <div className="absolute bottom-6 left-4 z-[1000] bg-white rounded-lg shadow-md p-3 text-xs">
      {mode === 'demand' ? (
        <>
          <div className="font-semibold mb-1 text-gray-700">Demand</div>
          <div className="flex items-center gap-1">
            <div className="w-20 h-3 rounded" style={{
              background: 'linear-gradient(to right, #22c55e, #ef4444)'
            }} />
          </div>
          <div className="flex justify-between mt-0.5 text-gray-500">
            <span>Low</span><span>High</span>
          </div>
          <div className="mt-1 flex items-center gap-1 text-gray-500">
            <div className="w-3 h-3 rounded" style={{ background: '#9ca3af' }} />
            <span>Inactive</span>
          </div>
        </>
      ) : (
        <>
          <div className="font-semibold mb-1 text-gray-700">DA Territory</div>
          {daIds.slice(0, 8).map((id, i) => (
            <div key={id} className="flex items-center gap-1 mb-0.5">
              <div className="w-3 h-3 rounded" style={{ background: DA_COLORS[i % DA_COLORS.length] }} />
              <span className="text-gray-600">DA-{i + 1}</span>
            </div>
          ))}
          {daIds.length > 8 && (
            <div className="text-gray-400">+{daIds.length - 8} more</div>
          )}
          <div className="flex items-center gap-1 mt-1 text-gray-500">
            <div className="w-3 h-3 rounded" style={{ background: '#e5e7eb' }} />
            <span>Unassigned</span>
          </div>
        </>
      )}
    </div>
  )
}
