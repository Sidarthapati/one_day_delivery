import { useState, useEffect } from 'react'
import { fetchTileDetail, updateTileDemand, setTileActive } from '../api/gridApi.js'
import { hashDaColor } from '../utils/daColors.js'

export default function HexPanel({ hexId, cityCode, date, onClose, onStale }) {
  const [detail, setDetail] = useState(null)
  const [loading, setLoading] = useState(true)
  const [demandInput, setDemandInput] = useState('')
  const [saving, setSaving] = useState(false)

  useEffect(() => {
    setLoading(true)
    fetchTileDetail(hexId, date)
      .then(d => {
        setDetail(d)
        setDemandInput(String(Math.round(d.demandScoreMinutes)))
      })
      .finally(() => setLoading(false))
  }, [hexId, date])

  async function handleApply() {
    const mins = parseFloat(demandInput)
    if (isNaN(mins) || mins < 0) return
    setSaving(true)
    try {
      await updateTileDemand(hexId, mins)
      onStale()
      onClose()
    } finally {
      setSaving(false)
    }
  }

  async function handleToggle() {
    if (!detail) return
    setSaving(true)
    try {
      await setTileActive(cityCode, hexId, !detail.active)
      onStale()
      onClose()
    } finally {
      setSaving(false)
    }
  }

  if (loading) {
    return <div className="p-4 text-sm text-gray-500">Loading hex details…</div>
  }
  if (!detail) return null

  return (
    <div className="p-4 border-t border-gray-200">
      <div className="flex justify-between items-center mb-3">
        <div className="font-semibold text-gray-800 font-mono text-sm">
          {detail.h3Index?.slice(0, 15)}
        </div>
        <button onClick={onClose} className="text-gray-400 hover:text-gray-600 text-lg leading-none">&times;</button>
      </div>

      <div className="space-y-2 text-sm mb-4">
        <div className="flex justify-between">
          <span className="text-gray-500">Status</span>
          <div className="flex items-center gap-2">
            <span className={`font-medium ${detail.active ? 'text-green-600' : 'text-gray-400'}`}>
              {detail.active ? '● ACTIVE' : '○ INACTIVE'}
            </span>
            <button
              onClick={handleToggle}
              disabled={saving}
              className="text-xs px-2 py-0.5 border border-gray-300 rounded hover:bg-gray-50 disabled:opacity-50"
            >
              Toggle
            </button>
          </div>
        </div>

        <div className="flex justify-between items-center">
          <span className="text-gray-500">Assigned DA</span>
          {detail.assignedDaId ? (
            <div className="flex items-center gap-1.5">
              <span
                className="inline-block w-3 h-3 rounded-sm flex-shrink-0"
                style={{ backgroundColor: hashDaColor(detail.assignedDaId) }}
              />
              <span className="font-mono text-xs text-gray-700">{detail.assignedDaId.slice(0, 8)}</span>
            </div>
          ) : (
            <span className="text-xs text-gray-400 italic">unassigned</span>
          )}
        </div>

        <hr className="border-gray-100" />

        <div className="flex justify-between items-center">
          <span className="text-gray-500">Demand score</span>
          <div className="flex items-center gap-1">
            <input
              type="number"
              value={demandInput}
              onChange={e => setDemandInput(e.target.value)}
              className="w-20 border border-gray-300 rounded px-1 py-0.5 text-right text-sm"
            />
            <span className="text-gray-400">min</span>
          </div>
        </div>

        <div className="flex justify-between">
          <span className="text-gray-500">Orders</span>
          <span>{detail.demandScoreOrders.toFixed(1)}</span>
        </div>
        <div className="flex justify-between">
          <span className="text-gray-500">Service time</span>
          <span>{detail.serviceTimeMin.toFixed(1)} min</span>
        </div>
        <div className="flex justify-between">
          <span className="text-gray-500">Inter-stop</span>
          <span>{detail.interStopTravelMin.toFixed(1)} min</span>
        </div>
        <div className="flex justify-between">
          <span className="text-gray-500">Bootstrapped</span>
          <span>{detail.bootstrapped ? 'yes' : 'no'}</span>
        </div>
      </div>

      <div className="flex gap-2">
        <button
          onClick={handleApply}
          disabled={saving}
          className="flex-1 bg-green-600 hover:bg-green-700 disabled:bg-green-300 text-white text-sm py-1.5 rounded transition-colors"
        >
          {saving ? 'Saving…' : 'Apply'}
        </button>
        <button
          onClick={onClose}
          className="flex-1 border border-gray-300 text-gray-600 text-sm py-1.5 rounded hover:bg-gray-50 transition-colors"
        >
          Close
        </button>
      </div>
    </div>
  )
}
