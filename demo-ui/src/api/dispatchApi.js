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


export const cancelTask = (cityId, date, shipmentId, taskType) =>
  req(`/api/demo/dispatch/cancel-task?${qs(cityId, date)}&shipmentId=${shipmentId}&taskType=${taskType}`,
    { method: 'POST' })

export const markAbsent = (cityId, date, daId) =>
  req(`/api/demo/dispatch/mark-absent?${qs(cityId, date)}&daId=${daId}`, { method: 'POST' })

// After marking a DA absent: flip any now-orphaned M4 pickups (PICKUP_ASSIGNED with no live DA) →
// PICKUP_FAILED ("reassigning"), so the customer's booking list stays honest.
export const reconcileM4 = (city, date) =>
  req(`/api/demo/da/reconcile-m4?city=${encodeURIComponent(city)}&date=${date}`, { method: 'POST' })

export const endShift = (cityId, date) =>
  req(`/api/demo/dispatch/end-shift?${qs(cityId, date)}`, { method: 'POST' })

export const retryDeferred = (cityId, date) =>
  req(`/api/demo/dispatch/retry-deferred?${qs(cityId, date)}`, { method: 'POST' })

export const getDispatchState = (cityId, date) =>
  req(`/api/demo/dispatch/state?${qs(cityId, date)}`)

export const resetDispatch = (cityId, date) =>
  req(`/api/demo/dispatch/reset?${qs(cityId, date)}`, { method: 'POST' })

// Simulate every DA's door OTP handshake → assigned pickups become PICKED_UP + ready-for-van, so the
// run carries only OTP-verified parcels. Synthetic (no-booking) pickups are skipped.
export const autoVerifyPickups = (cityId, date) =>
  req(`/api/demo/da/auto-verify?${qs(cityId, date)}`, { method: 'POST' })

// Last-mile drops: fast-forward booked DA_DELIVERY shipments destined for `city` to out-for-delivery
// (mints each delivery OTP) and publish real ParcelSortedForDelivery so M6 binds them to drop loops.
export const dispatchDrops = (cityId, city, date) =>
  req(`/api/demo/da/drops/dispatch?cityId=${cityId}&city=${encodeURIComponent(city)}&date=${date}`,
    { method: 'POST' })

// Simulate every recipient's door OTP → DROP_COLLECTED parcels become DROPPED (Delivered).
export const autoVerifyDeliveries = (city, date) =>
  req(`/api/demo/da/drops/auto-verify?city=${encodeURIComponent(city)}&date=${date}`, { method: 'POST' })

// Close the first mile: PICKED_UP shipments (origin = city) → HANDED_TO_PICKUP_VAN → AT_ORIGIN_HUB
// (the van carried them to the origin hub). Run after "Run the day".
export const pickupsToHub = (city, date) =>
  req(`/api/demo/da/pickups/to-hub?city=${encodeURIComponent(city)}&date=${date}`, { method: 'POST' })

// End of Run-the-day: van met the DA, parcel handed to the pickup van (PICKED_UP → HANDED_TO_PICKUP_VAN).
export const pickupsToVan = (city, date) =>
  req(`/api/demo/da/pickups/to-van?city=${encodeURIComponent(city)}&date=${date}`, { method: 'POST' })

// Live RabbitMQ tap: real PUBLISH/CONSUME observations newer than `after`, plus the current head seq
// (fast-forward the cursor to head at run start so the feed shows only this run's bus traffic).
export const getAmqpTap = (after = 0) =>
  req(`/api/demo/amqp-tap?after=${after}`)

// Batch DA GPS heartbeats — recorded through the real M5 telemetry path (updateGps → da_status), so the
// DAs' stored positions + last_heartbeat track the on-screen animation. pings: [{da_id, lat, lon}].
export const pingDaGps = (pings) =>
  req('/api/demo/dispatch/gps', { method: 'POST', body: JSON.stringify(pings) })

// Demo reset: wipe every shipment booked by `email` (default the demo customer) + all child rows
// (otps, payments, history, M5 dispatch tasks) so the demo starts from a clean slate.
export const clearBookings = (email = 'b2c@demo.in') =>
  req(`/api/demo/da/bookings/clear?email=${encodeURIComponent(email)}`, { method: 'POST' })

// Spread seed: book `count` real shipments whose spread end lands in a DIFFERENT DA territory each, so
// the demo involves many DAs. kind=PICKUP (spread origins, M5 assigns across DAs) or DROP (spread dests).
export const seedSpread = (cityId, city, kind, count, date) =>
  req(`/api/demo/da/seed/spread?cityId=${cityId}&city=${encodeURIComponent(city)}&kind=${kind}&count=${count}&date=${date}`,
    { method: 'POST' })
