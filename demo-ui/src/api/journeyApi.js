// M7 execution-demo "journey" endpoints — drive a compressed virtual day end to end over the real
// M4→M5→M6→M7→M6 pipeline. Same-origin via the Vite proxy (/api → backend). Responses deep-converted
// to camelCase (shared helper with gridApi), so Java records read the same on both sides.
import { deepCamel } from './gridApi.js'

async function req(path, opts = {}) {
  const res = await fetch(path, { headers: { 'Content-Type': 'application/json' }, ...opts })
  if (!res.ok) {
    let detail = ''
    try { detail = (await res.json()).detail || '' } catch { /* ignore */ }
    throw new Error(`${opts.method || 'GET'} ${path} → ${res.status}${detail ? ` (${detail})` : ''}`)
  }
  if (res.status === 204) return null
  return deepCamel(await res.json())
}

const BASE = '/api/demo/journey'

// Start one compressed virtual-day run for a city-pair. speed is the compression factor (60 ≈ 1min/sec).
export const runDay = ({ originCity, destCity, count, speed }) =>
  req(`${BASE}/run-day`, {
    method: 'POST',
    // Backend Jackson is globally SNAKE_CASE — send snake_case keys or they bind to null.
    body: JSON.stringify({ origin_city: originCity, dest_city: destCity, count, speed }),
  })

export const stopRun = () => req(`${BASE}/stop`, { method: 'POST' })

// Run-level rollup: { phase, originCity, destCity, date, booked, assigned, delivered, lastSeq, error }.
export const getRunStatus = () => req(`${BASE}/run-status`)

// Per-parcel tokens: [{ shipmentRef, shipmentId, stage, originCity, destCity, daId, vanId, flightNo, standNo }].
export const getJourneys = () => req(`${BASE}/journeys`)

// Raw event feed since seq `after`: [{ seq, at, kind, message }].
export const getRunEvents = (after = 0) => req(`${BASE}/run-events?after=${after}`)
