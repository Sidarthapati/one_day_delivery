import { useState } from 'react'

export default function DaControls({ onGenerate, loading }) {
  const [daCount, setDaCount] = useState(10)

  return (
    <div className="p-4 border-b border-gray-200">
      <div className="text-sm font-semibold text-gray-700 mb-3">Generate Assignment Plan</div>
      <div className="flex items-center gap-3 mb-3">
        <label className="text-sm text-gray-600 whitespace-nowrap">DAs for today:</label>
        <input
          type="number"
          min={1}
          value={daCount}
          onChange={e => setDaCount(Math.max(1, Number(e.target.value)))}
          className="w-16 border border-gray-300 rounded px-2 py-1 text-sm text-center"
        />
      </div>
      <button
        onClick={() => onGenerate(daCount)}
        disabled={loading}
        className="w-full bg-blue-600 hover:bg-blue-700 disabled:bg-blue-300 text-white text-sm font-medium py-2 px-4 rounded transition-colors"
      >
        {loading ? 'Generating…' : 'Generate Plan'}
      </button>
    </div>
  )
}
