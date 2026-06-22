// M5 dispatch demo endpoints (/api/demo/dispatch/*). Same-origin via the Vite proxy; responses
// deep-converted to camelCase (shared helper with gridApi).
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

const qs = (cityId, date) => `cityId=${cityId}&date=${date}`

export const loadShift = (cityId, date) =>
  req(`/api/demo/dispatch/load-shift?${qs(cityId, date)}`, { method: 'POST' })

export const assignPickups = (cityId, date, count) =>
  req(`/api/demo/dispatch/assign?${qs(cityId, date)}&count=${count}`, { method: 'POST' })

export const assignDeliveries = (cityId, date, count) =>
  req(`/api/demo/dispatch/assign-deliveries?${qs(cityId, date)}&count=${count}`, { method: 'POST' })

export const workNext = (cityId, date) =>
  req(`/api/demo/dispatch/work-next?${qs(cityId, date)}`, { method: 'POST' })

export const cancelTask = (cityId, date, shipmentId, taskType) =>
  req(`/api/demo/dispatch/cancel-task?${qs(cityId, date)}&shipmentId=${shipmentId}&taskType=${taskType}`,
    { method: 'POST' })

export const markAbsent = (cityId, date, daId) =>
  req(`/api/demo/dispatch/mark-absent?${qs(cityId, date)}&daId=${daId}`, { method: 'POST' })

export const endShift = (cityId, date) =>
  req(`/api/demo/dispatch/end-shift?${qs(cityId, date)}`, { method: 'POST' })

export const retryDeferred = (cityId, date) =>
  req(`/api/demo/dispatch/retry-deferred?${qs(cityId, date)}`, { method: 'POST' })

export const getDispatchState = (cityId, date) =>
  req(`/api/demo/dispatch/state?${qs(cityId, date)}`)

export const resetDispatch = (cityId, date) =>
  req(`/api/demo/dispatch/reset?${qs(cityId, date)}`, { method: 'POST' })
