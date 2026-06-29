// DA-app endpoints: the enriched per-DA task list (/api/demo/da/{daId}/tasks) and the real pickup-OTP
// verify (/internal/v1/shipments/{ref}/pickup-otp/verify). Same-origin via the Vite proxy; responses
// deep-converted to camelCase (shared helper with gridApi).
import { deepCamel } from './gridApi.js'

async function req(path, opts = {}) {
  const res = await fetch(path, { headers: { 'Content-Type': 'application/json' }, ...opts })
  if (!res.ok) {
    let detail = ''
    try { detail = (await res.json()).detail || '' } catch { /* ignore */ }
    throw new Error(detail || `${opts.method || 'GET'} ${path} → ${res.status}`)
  }
  if (res.status === 204) return null
  return deepCamel(await res.json())
}

export const getDaTasks = (daId, cityId, date) =>
  req(`/api/demo/da/${daId}/tasks?cityId=${cityId}&date=${date}`)

// DA enters the customer's OTP to confirm the handover → shipment PICKUP_ASSIGNED → PICKED_UP.
export const verifyPickupOtp = (ref, otp) =>
  req(`/internal/v1/shipments/${encodeURIComponent(ref)}/pickup-otp/verify`,
    { method: 'POST', body: JSON.stringify({ otp }) })

// Regenerate an expired/lost pickup OTP. Invalidates the old one, resets the 10-min TTL, max 3 times
// (429 after). Returns { otp } — sent to the sender; also surfaced here for the demo. 409 if the
// shipment is not in PICKUP_ASSIGNED.
export const resendPickupOtp = (ref) =>
  req(`/internal/v1/shipments/${encodeURIComponent(ref)}/pickup-otp/resend`, { method: 'POST' })

// DA enters the recipient's OTP at the door to confirm delivery → shipment DROP_COLLECTED → DROPPED.
export const verifyDeliveryOtp = (ref, otp) =>
  req(`/internal/v1/shipments/${encodeURIComponent(ref)}/delivery-otp/verify`,
    { method: 'POST', body: JSON.stringify({ otp }) })

// Regenerate an expired/lost delivery OTP (409 if the shipment is not in DROP_COLLECTED).
export const resendDeliveryOtp = (ref) =>
  req(`/internal/v1/shipments/${encodeURIComponent(ref)}/delivery-otp/resend`, { method: 'POST' })
