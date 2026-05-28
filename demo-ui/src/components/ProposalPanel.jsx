import { useState } from 'react'
import { approveProposal } from '../api/gridApi.js'
import { hashDaColor } from '../utils/daColors.js'

const STATUS_LABEL = {
  PROPOSED: { text: 'PROPOSED', cls: 'text-yellow-600 bg-yellow-50' },
  APPROVED: { text: 'APPROVED', cls: 'text-green-700 bg-green-50' },
  REJECTED: { text: 'REJECTED', cls: 'text-red-600 bg-red-50' },
  SUPERSEDED: { text: 'SUPERSEDED', cls: 'text-gray-500 bg-gray-50' },
}

export default function ProposalPanel({ proposal, date }) {
  const [status, setStatus] = useState(proposal.status)
  const [approving, setApproving] = useState(false)

  async function handleApprove() {
    setApproving(true)
    try {
      await approveProposal(proposal.id)
      setStatus('APPROVED')
    } catch (e) {
      alert('Approve failed: ' + e.message)
    } finally {
      setApproving(false)
    }
  }

  const lbl = STATUS_LABEL[status] || STATUS_LABEL.PROPOSED

  return (
    <div className="p-4 border-t border-gray-200">
      <div className="font-semibold text-gray-800 mb-3">
        Plan — {date}
      </div>

      <div className="space-y-2 text-sm mb-4">
        <div className="flex justify-between items-center">
          <span className="text-gray-500">Status</span>
          <span className={`text-xs font-semibold px-2 py-0.5 rounded ${lbl.cls}`}>{lbl.text}</span>
        </div>
        <div className="flex justify-between">
          <span className="text-gray-500">Solver</span>
          <span>{proposal.solverType ?? 'BFS'}</span>
        </div>
        {proposal.optimalityGapPct != null && (
          <div className="flex justify-between">
            <span className="text-gray-500">Gap</span>
            <span>{proposal.optimalityGapPct.toFixed(1)} %</span>
          </div>
        )}
        <div className="flex justify-between">
          <span className="text-gray-500">DAs</span>
          <span>{proposal.totalDas ?? '—'}</span>
        </div>
        <div className="flex justify-between">
          <span className="text-gray-500">Coverage</span>
          <span>{proposal.coveragePct != null ? proposal.coveragePct.toFixed(0) + ' %' : '—'}</span>
        </div>
        <div className="flex justify-between">
          <span className="text-gray-500">Understaffed hexes</span>
          <span className={(proposal.understaffedHexIds?.length ?? 0) > 0 ? 'text-red-600 font-medium' : ''}>
            {proposal.understaffedHexIds?.length ?? 0}
          </span>
        </div>
      </div>

      {status === 'PROPOSED' && (
        <button
          onClick={handleApprove}
          disabled={approving}
          className="w-full bg-indigo-600 hover:bg-indigo-700 disabled:bg-indigo-300 text-white text-sm font-medium py-2 rounded transition-colors"
        >
          {approving ? 'Approving…' : 'Approve Plan'}
        </button>
      )}
      {status === 'APPROVED' && (
        <div className="text-center text-green-700 text-sm font-medium py-2">
          Plan is now LIVE
        </div>
      )}

      {proposal.regions?.length > 0 && <DaBreakdown regions={proposal.regions} />}
    </div>
  )
}

function DaBreakdown({ regions }) {
  const totalDemand = regions.reduce((s, r) => s + (r.estimatedDemandMin ?? 0), 0)
  const target = totalDemand / regions.length

  const sorted = [...regions].sort((a, b) => (b.estimatedDemandMin ?? 0) - (a.estimatedDemandMin ?? 0))

  return (
    <div className="mt-4 border-t border-gray-200 pt-3">
      <div className="text-xs font-semibold text-gray-500 uppercase tracking-wide mb-2 px-1">
        DA Breakdown · target {Math.round(target)} min
      </div>
      <div className="space-y-1">
        {sorted.map(r => {
          const load = r.estimatedDemandMin ?? 0
          const pct = target > 0 ? ((load - target) / target) * 100 : 0
          const over = pct > 40
          const under = pct < -40
          const pctStr = (pct >= 0 ? '+' : '') + pct.toFixed(0) + '%'

          return (
            <div key={r.daId} className="flex items-center gap-2 px-1 py-1 rounded hover:bg-gray-50 text-xs">
              <span
                className="w-2.5 h-2.5 rounded-full flex-shrink-0"
                style={{ backgroundColor: hashDaColor(r.daId) }}
              />
              <span className="font-mono text-gray-500 w-16 truncate">{r.daId?.slice(0, 8)}</span>
              <span className="text-gray-400 w-12 text-right">{r.hexIds?.length ?? 0} hexes</span>
              <span className="text-gray-700 w-16 text-right font-medium">{Math.round(load)} min</span>
              <span className={`w-12 text-right font-semibold ${over ? 'text-red-500' : under ? 'text-blue-400' : 'text-green-600'}`}>
                {pctStr}
              </span>
            </div>
          )
        })}
      </div>
    </div>
  )
}
