import { useState } from 'react'

// Explicit demand-seeding step. Writes one h3_hex_demand_snapshot row per hex (~3.5k for Delhi)
// with order-engaged minutes drawn in [min, max]. The seed makes the surface reproducible so
// territory/route runs can be compared against the SAME demand; 🎲 rolls a fresh one.
export default function SeedControls({ onSeed, loading, lastSeed }) {
  const [minMinutes, setMinMinutes] = useState(4)
  const [maxMinutes, setMaxMinutes] = useState(10)
  const [seed, setSeed] = useState('')

  const randomize = () => setSeed(String(Math.floor(Math.random() * 1e9)))

  return (
    <div className="p-4 border-b border-gray-200">
      <div className="text-sm font-semibold text-gray-700 mb-3">Seed demand</div>

      <div className="flex items-center gap-2 mb-2">
        <label className="text-sm text-gray-600 w-24">Min minutes</label>
        <input type="number" min={0} value={minMinutes}
          onChange={e => setMinMinutes(Math.max(0, Number(e.target.value)))}
          className="w-20 border border-gray-300 rounded px-2 py-1 text-sm text-center" />
      </div>
      <div className="flex items-center gap-2 mb-2">
        <label className="text-sm text-gray-600 w-24">Max minutes</label>
        <input type="number" min={0} value={maxMinutes}
          onChange={e => setMaxMinutes(Math.max(0, Number(e.target.value)))}
          className="w-20 border border-gray-300 rounded px-2 py-1 text-sm text-center" />
      </div>
      <div className="flex items-center gap-2 mb-3">
        <label className="text-sm text-gray-600 w-24">Seed</label>
        <input type="number" value={seed} placeholder="random"
          onChange={e => setSeed(e.target.value)}
          className="w-20 border border-gray-300 rounded px-2 py-1 text-sm text-center" />
        <button onClick={randomize} title="Random seed"
          className="border border-gray-300 rounded px-2 py-1 text-sm hover:bg-gray-100">🎲</button>
      </div>

      <button
        onClick={() => onSeed({ minMinutes, maxMinutes, seed: seed === '' ? undefined : Number(seed) })}
        disabled={loading || maxMinutes < minMinutes}
        className="w-full bg-emerald-600 hover:bg-emerald-700 disabled:bg-emerald-300 text-white text-sm font-medium py-2 px-4 rounded transition-colors"
      >
        {loading ? 'Seeding…' : 'Seed demand'}
      </button>

      {lastSeed != null && (
        <div className="text-xs text-gray-500 mt-2">Seeded with seed <span className="font-mono">{lastSeed}</span></div>
      )}
    </div>
  )
}
