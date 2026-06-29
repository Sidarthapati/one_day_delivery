import { MapContainer, TileLayer, Polyline, Marker, Tooltip, Popup, useMap } from 'react-leaflet'
import L from 'leaflet'
import 'leaflet/dist/leaflet.css'
import { hashDaColor } from '../utils/daColors.js'
import { useEffect, useRef } from 'react'

// Van marker — colour by lateness (green on time, amber minor, red past threshold, blue = moving/no
// arrival yet). Pulses so a live van reads as "moving" vs the static numbered route stops.
function vanIcon(color, label) {
  return L.divIcon({
    className: '',
    html: `<div style="position:relative;display:flex;align-items:center;justify-content:center">
      <div style="position:absolute;width:30px;height:30px;border-radius:50%;background:${color};opacity:.30;
        animation:vanpulse 1.4s ease-out infinite"></div>
      <div style="background:${color};color:#fff;min-width:30px;height:18px;padding:0 5px;border-radius:9px;
        display:flex;align-items:center;justify-content:center;font:800 10px/1 sans-serif;
        border:2px solid #fff;box-shadow:0 0 5px rgba(0,0,0,.55);white-space:nowrap">🚐 ${label}</div>
    </div>
    <style>@keyframes vanpulse{0%{transform:scale(.6);opacity:.5}100%{transform:scale(1.8);opacity:0}}</style>`,
    iconSize: [40, 24], iconAnchor: [20, 12],
  })
}

// DA marker — colour matches the DA's territory; the badge is how many parcels it's currently carrying
// (collected pickups before the van, or remaining deliveries after). Pulses while handing off at the vertex.
function daIcon(color, badge, meeting) {
  return L.divIcon({
    className: '',
    html: `<div style="display:flex;flex-direction:column;align-items:center;${meeting ? 'animation:dapulse 1s ease-in-out infinite' : ''}">
      <div style="background:#fff;border:2px solid ${color};color:${color};width:22px;height:22px;border-radius:6px;
        display:flex;align-items:center;justify-content:center;font:700 12px/1 sans-serif;
        box-shadow:0 0 3px rgba(0,0,0,.35)">👤</div>
      ${badge > 0 ? `<div style="margin-top:-5px;background:${color};color:#fff;font:700 9px/1 sans-serif;
        padding:1px 4px;border-radius:7px;border:1px solid #fff;white-space:nowrap">📦 ${badge}</div>` : ''}
    </div>
    <style>@keyframes dapulse{0%,100%{transform:scale(1)}50%{transform:scale(1.3)}}</style>`,
    iconSize: [26, 32], iconAnchor: [13, 16],
  })
}

// True when two [lat,lon] points are within ~1.3 km (a van that has arrived sits exactly on the vertex).
function near(a, b) {
  return Math.hypot(a[0] - b[0], a[1] - b[1]) < 0.013
}

function lerp(a, b, t) { return [a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t] }

// Point at fraction t (0..1) along a multi-segment path, weighted by segment length.
function pathPoint(path, t) {
  const pts = path.filter(p => p && p[0] != null)
  if (pts.length === 0) return null
  if (pts.length === 1 || t <= 0) return pts[0]
  if (t >= 1) return pts[pts.length - 1]
  const segs = []; let total = 0
  for (let i = 0; i < pts.length - 1; i++) {
    segs.push(Math.hypot(pts[i + 1][0] - pts[i][0], pts[i + 1][1] - pts[i][1])); total += segs[i]
  }
  if (total === 0) return pts[0]
  let dist = t * total
  for (let i = 0; i < segs.length; i++) {
    if (dist <= segs[i]) return lerp(pts[i], pts[i + 1], segs[i] ? dist / segs[i] : 0)
    dist -= segs[i]
  }
  return pts[pts.length - 1]
}

// Cumulative fraction (0..1) at each point of a path — lets us tell which pickup/delivery stops are passed.
function cumFrac(pts) {
  const segs = []; let total = 0
  for (let i = 0; i < pts.length - 1; i++) { segs.push(Math.hypot(pts[i + 1][0] - pts[i][0], pts[i + 1][1] - pts[i][1])); total += segs[i] }
  const cum = [0]; let acc = 0
  for (let i = 0; i < segs.length; i++) { acc += segs[i]; cum.push(total ? acc / total : 1) }
  return cum
}

// A DA's live state at run-progress t (0..1): collect (0–0.4) → handoff dwell at the vertex (0.4–0.55) →
// deliver + return to territory (0.55–1). Returns position + which/how-many parcels are done.
const DWELL0 = 0.4, DWELL1 = 0.55
function daState(da, t) {
  const P = (da.pickups || []).length, D = (da.deliveries || []).length
  const out = [da.home, ...(da.pickups || []), da.vertex].filter(p => p && p[0] != null)
  const ret = [da.vertex, ...(da.deliveries || []), da.home].filter(p => p && p[0] != null)
  if (t <= 0) return { pos: da.home || da.vertex, phase: 'idle', P, D, collected: 0, delivered: 0, atVertex: false }
  if (t < DWELL0) {
    const f = t / DWELL0, cf = cumFrac(out)
    let collected = 0
    for (let i = 1; i <= P; i++) if (cf[i] <= f) collected++
    return { pos: pathPoint(out, f), phase: 'collecting', P, D, collected, delivered: 0, atVertex: false }
  }
  if (t < DWELL1) {
    return { pos: da.vertex, phase: 'handoff', P, D, collected: P, delivered: 0, atVertex: true }
  }
  const f = (t - DWELL1) / (1 - DWELL1), cf = cumFrac(ret)
  let delivered = 0
  for (let i = 1; i <= D; i++) if (cf[i] <= f) delivered++
  return { pos: pathPoint(ret, f), phase: f >= 1 ? 'done' : 'delivering', P, D, collected: P, delivered, atVertex: false }
}

// DAs all rendezvous at a hex vertex — if drawn at the exact point they stack on the van and each other.
// Fan each DA out onto a small ring around its vertex (≈150–200 m, well inside the handoff radius) so the
// van↔DA meeting is legible. DAs sharing a vertex get evenly-spaced angles; a lone DA sits just beside it.
function spreadVertices(das) {
  const groups = new Map()
  for (const d of das) {
    if (!d.vertex || d.vertex[0] == null) continue
    const key = `${d.vertex[0].toFixed(4)},${d.vertex[1].toFixed(4)}`
    if (!groups.has(key)) groups.set(key, [])
    groups.get(key).push(d.daId)
  }
  const R = 0.0018   // ~180 m in lat; the dashed connector then shows the small DA→van gap
  const off = new Map()
  for (const ids of groups.values()) {
    const n = ids.length
    ids.forEach((id, i) => {
      const ang = (2 * Math.PI * i) / Math.max(1, n) + 0.4   // +0.4 so a lone DA sits off-axis, not due-east
      off.set(id, [R * Math.cos(ang), R * Math.sin(ang)])
    })
  }
  return off
}

function nodeIcon(kind) {
  const hub = kind === 'HUB'
  const bg = hub ? '#111827' : '#2563eb'
  const txt = hub ? '🏢 HUB' : '✈ AIRPORT'
  return L.divIcon({
    className: '',
    html: `<div style="background:${bg};color:#fff;padding:4px 9px;border-radius:5px;
      font:800 12px/1 sans-serif;border:2px solid #fff;box-shadow:0 1px 5px rgba(0,0,0,.5);
      white-space:nowrap;letter-spacing:.3px">${txt}</div>`,
    iconSize: [hub ? 56 : 78, 26], iconAnchor: [hub ? 28 : 39, 13],
  })
}

function lateColor(minutesLate) {
  if (minutesLate == null) return '#2563eb'
  if (minutesLate >= 10) return '#dc2626'
  if (minutesLate >= 6) return '#d97706'
  return '#16a34a'
}

const clock = (iso) => { try { return new Date(iso).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }) } catch { return '—' } }
const sid = (id) => (id ? id.slice(0, 4) : '—')
// Compact parcel-id list for tooltips: first n, then "+K".
function idList(ids, n = 4) {
  if (!ids || ids.length === 0) return '—'
  const head = ids.slice(0, n).map(sid).join(', ')
  return ids.length > n ? `${head} +${ids.length - n}` : head
}

// Per-parcel M4 journey stage → short label + colour, shown next to each ref so the card reflects live
// progress (updates on poll / refresh as OTP actions + dispatch advance the shipment state).
const M4_BADGE = {
  BOOKED: ['booked', '#6b7280'],
  PICKUP_ASSIGNED: ['assigned', '#1d4ed8'],
  PICKED_UP: ['picked up', '#0d9488'],
  HANDED_TO_PICKUP_VAN: ['on van', '#0d9488'],
  AT_ORIGIN_HUB: ['at hub', '#7c3aed'],
  HANDED_TO_DROP_VAN: ['on van', '#6d28d9'],
  DROP_ASSIGNED: ['assigned', '#1d4ed8'],
  DROP_COLLECTED: ['out for delivery', '#0d9488'],
  DROPPED: ['delivered', '#059669'],
  CANCELLED: ['cancelled', '#dc2626'],
}
function stateBadge(state) {
  const [label, color] = M4_BADGE[state] || [state ? state.toLowerCase() : '—', '#9ca3af']
  return <span style={{ fontSize: 10.5, color, background: color + '1a', borderRadius: 4, padding: '0 5px', whiteSpace: 'nowrap' }}>{label}</span>
}

// Refs (the human-readable 1DD-… ids) + each parcel's live M4 stage, scrollable.
function RefList({ refs, empty }) {
  if (!refs || refs.length === 0) {
    return <div style={{ color: '#9ca3af', paddingLeft: 8 }}>{empty}</div>
  }
  return (
    <div style={{
      maxHeight: 120, overflowY: 'auto', fontSize: 12.5,
      paddingLeft: 8, margin: '1px 0 3px', borderLeft: '2px solid #e5e7eb',
    }}>
      {refs.map((r, i) => {
        const ref = typeof r === 'string' ? r : r.ref
        const state = typeof r === 'string' ? null : r.state
        return (
          <div key={ref + i} style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 6 }}>
            <span style={{ fontFamily: 'monospace' }}>{ref}</span>{stateBadge(state)}
          </div>
        )
      })}
    </div>
  )
}

function FitOnce({ routes, nodes }) {
  const map = useMap()
  useEffect(() => {
    const pts = []
    routes.forEach(r => r.geometry.forEach(p => pts.push(p)))
    nodes.forEach(n => pts.push([n.lat, n.lon]))
    if (pts.length >= 2) map.fitBounds(pts, { padding: [50, 50] })
  }, [routes.length, nodes.length, map])
  return null
}

// Captures the Leaflet map instance so the overlay buttons + marker clicks can drive zoom/pan.
function MapApi({ mapRef }) {
  const map = useMap()
  mapRef.current = map
  return null
}

export default function ExecutionMap({ center, routes = [], nodes = [], vans = [], das = [], daTs = {}, vanColor = hashDaColor, onRefresh }) {
  const mapRef = useRef(null)
  const flyTo = (pt, zoom = 14) => { if (pt && mapRef.current) mapRef.current.flyTo(pt, zoom, { duration: 0.8 }) }
  const fitAll = () => {
    const pts = []
    routes.forEach(r => r.geometry.forEach(p => pts.push(p)))
    nodes.forEach(n => pts.push([n.lat, n.lon]))
    if (pts.length >= 2 && mapRef.current) mapRef.current.fitBounds(pts, { padding: [50, 50] })
  }

  // Spread DAs that share a vertex onto a small ring so each stays visible beside the van.
  const off = spreadVertices(das)
  const adjDas = das.map(d => {
    const o = off.get(d.daId)
    return (o && d.vertex && d.vertex[0] != null) ? { ...d, vertex: [d.vertex[0] + o[0], d.vertex[1] + o[1]] } : d
  })
  // Each DA's live state (position + parcel progress) at its own van-driven progress.
  const st = new Map()
  for (const d of adjDas) st.set(d.daId, daState(d, daTs[d.daId] ?? 0))

  // DA↔van rendezvous right now: a live van alongside a DA's current position.
  const meetings = []
  const meetingDa = new Set()
  for (const v of vans) {
    if (v.lastLat == null) continue
    for (const d of adjDas) {
      const s = st.get(d.daId)
      if (!s?.pos) continue
      if (near([v.lastLat, v.lastLon], s.pos)) {
        meetings.push({ from: [v.lastLat, v.lastLon], to: s.pos, color: vanColor(d.daId) })
        meetingDa.add(d.daId)
      }
    }
  }

  return (
    <div style={{ position: 'relative', height: '100%', width: '100%' }}>
      <MapContainer center={center} zoom={11} style={{ height: '100%', width: '100%' }}>
        <TileLayer
          url="https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png"
          attribution='&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a>'
        />
        <FitOnce routes={routes} nodes={nodes} />
        <MapApi mapRef={mapRef} />

        {/* Static route polylines (the approved plan), faded under the live vans. */}
        {routes.map(r => (
          <Polyline key={r.vanId} positions={r.geometry}
            pathOptions={{ color: vanColor(r.vanId), weight: 4, opacity: 0.35, lineJoin: 'round' }} />
        ))}

        {/* One numbered dot per meeting vertex (the plan's stops). */}
        {routes.map(r => (r.markers || []).map(m => (
          <Marker key={`${r.vanId}-${m.stopId}`} position={[m.lat, m.lon]}
            icon={L.divIcon({
              className: '',
              html: `<div style="background:${vanColor(r.vanId)};color:#fff;width:18px;height:18px;border-radius:50%;
                display:flex;align-items:center;justify-content:center;font:700 10px/1 sans-serif;
                opacity:.85;border:2px solid #fff;box-shadow:0 0 3px rgba(0,0,0,.4)">${m.seq}</div>`,
              iconSize: [18, 18], iconAnchor: [9, 9],
            })}>
            <Tooltip>Stop {m.seq}</Tooltip>
          </Marker>
        )))}

        {/* DA↔van handoff connectors (active rendezvous). */}
        {meetings.map((m, i) => (
          <Polyline key={`mt-${i}`} positions={[m.from, m.to]}
            pathOptions={{ color: m.color, weight: 3, opacity: 0.9, dashArray: '4 4' }} />
        ))}

        {/* DAs: collect → meet van at vertex → deliver → back to territory. Hover shows live status. */}
        {adjDas.map(d => {
          const s = st.get(d.daId)
          if (!s?.pos) return null
          const meeting = meetingDa.has(d.daId)
          const swapped = ['handoff', 'delivering', 'done'].includes(s.phase)   // van handoff has happened
          const inHand = s.phase === 'collecting' ? s.collected
            : s.phase === 'handoff' ? s.P
            : s.phase === 'delivering' ? (s.D - s.delivered) : 0
          return (
            <Marker key={`da-${d.daId}`} position={s.pos}
              icon={daIcon(vanColor(d.daId), inHand, meeting)} zIndexOffset={800}
              eventHandlers={{ click: () => flyTo(s.pos, 14) }}>
              {/* Quick hover glance. */}
              <Tooltip>
                <div style={{ fontSize: 13, lineHeight: 1.45 }}>
                  <strong>DA {d.daId.slice(0, 8)}</strong> · {s.P} pickups / {s.D} drops<br />
                  <span style={{ color: '#9ca3af' }}>click to pin details + zoom</span>
                </div>
              </Tooltip>
              {/* Persistent card — click the DA to pin it; stays open and live-updates as parcels change. */}
              <Popup maxWidth={340} autoPan={false} keepInView>
                <div style={{ fontSize: 13.5, lineHeight: 1.55 }}>
                  <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 8 }}>
                    <span style={{ fontSize: 15, fontWeight: 700 }}>DA {d.daId.slice(0, 8)}</span>
                    {onRefresh && (
                      <button onClick={(e) => { e.stopPropagation(); onRefresh() }}
                        title="Refresh parcel states now (poll pauses during a run; DA-app OTP actions land here on refresh)"
                        style={{ fontSize: 11, border: '1px solid #cbd5e1', borderRadius: 5, padding: '1px 7px', background: '#fff', cursor: 'pointer', color: '#475569' }}>
                        ⟳ refresh
                      </button>
                    )}
                  </div>
                  <div>{s.P} pickups / {s.D} drops · {d.vanId ? `van ${d.vanId.slice(0, 8)}` : 'no van scheduled'}
                    {d.cronTime ? ` · cron ${clock(d.cronTime)}` : ''}</div>
                  <div style={{ margin: '4px 0' }}>
                    {s.phase === 'collecting' && <>📥 collecting pickups <b>{s.collected}/{s.P}</b> · carrying <b>{s.collected}</b> to van · {s.D} drops await van</>}
                    {s.phase === 'handoff' && <span style={{ color: '#0d9488' }}>● handoff: <b>{s.P}</b> pickups → van · <b>{s.D}</b> drops → DA</span>}
                    {s.phase === 'delivering' && <>📦 pickups handed ✓ · delivering <b>{s.delivered}/{s.D}</b> · <b>{s.D - s.delivered}</b> drops left · returning to territory</>}
                    {s.phase === 'done' && <span style={{ color: '#6b7280' }}>back in territory · {s.P} picked up, {s.D} delivered</span>}
                    {s.phase === 'idle' && <span style={{ color: '#6b7280' }}>{s.P} pickups + {s.D} drops queued · run the day to dispatch</span>}
                  </div>
                  <div style={{ color: '#1d4ed8', marginTop: 2 }}>↑ to van ({s.P}) {swapped ? '✓ handed' : ''}</div>
                  <RefList refs={d.pickupRefs} empty="no pickups" />
                  <div style={{ color: '#6d28d9', marginTop: 2 }}>↓ from van ({s.D}) {swapped ? '✓ received' : ''}</div>
                  <RefList refs={d.deliveryRefs} empty="no drops" />
                  <div style={{ color: '#9ca3af', marginTop: 3 }}>📌 pinned · click the map to close</div>
                </div>
              </Popup>
            </Marker>
          )
        })}

        {/* Live vans. */}
        {vans.map(v => {
          if (v.lastLat == null) return null
          const myDas = adjDas.filter(x => x.vanId === v.vanId)
          const met = myDas.filter(x => ['handoff', 'delivering', 'done'].includes(st.get(x.daId)?.phase))
          const recv = met.flatMap(x => x.pickupIds || [])     // van received from DAs (fly out)
          const gave = met.flatMap(x => x.deliveryIds || [])   // van handed to DAs (inbound)
          return (
            <Marker key={v.vanId} position={[v.lastLat, v.lastLon]}
              icon={vanIcon(lateColor(v.minutesLate), v.vanId.slice(0, 4))} zIndexOffset={1000}
              eventHandlers={{ click: () => flyTo([v.lastLat, v.lastLon], 14) }}>
              <Tooltip>
                <div style={{ fontSize: 12, lineHeight: 1.5 }}>
                  <strong>Van {v.vanId.slice(0, 8)}</strong> · meets {myDas.length} DA{myDas.length === 1 ? '' : 's'}<br />
                  {v.currentStopSeq != null ? `at stop ${v.currentStopSeq}` : 'en route'} ·{' '}
                  {v.minutesLate == null ? 'no arrival yet'
                    : v.minutesLate >= 10 ? `⚠ running late +${v.minutesLate}m`
                    : v.minutesLate > 0 ? `+${v.minutesLate}m` : 'on time'}
                  <br /><span style={{ color: '#1d4ed8' }}>↓ received ({recv.length}):</span> {idList(recv)}
                  <br /><span style={{ color: '#6d28d9' }}>↑ delivered ({gave.length}):</span> {idList(gave)}
                  {myDas.slice(0, 4).map(x => {
                    const done = ['handoff', 'delivering', 'done'].includes(st.get(x.daId)?.phase)
                    return (
                      <div key={x.daId} style={{ color: done ? '#0d9488' : '#9ca3af' }}>
                        {done ? '✓' : '·'} DA {x.daId.slice(0, 4)}: from {idList(x.pickupIds, 2)} / to {idList(x.deliveryIds, 2)}
                      </div>
                    )
                  })}
                  {myDas.length > 4 && <div style={{ color: '#9ca3af' }}>+{myDas.length - 4} more DAs</div>}
                  <span style={{ color: '#9ca3af' }}>click to zoom in</span>
                </div>
              </Tooltip>
            </Marker>
          )
        })}

        {nodes.map(n => (
          <Marker key={n.kind} position={[n.lat, n.lon]} icon={nodeIcon(n.kind)} zIndexOffset={1200}>
            <Tooltip>{n.name}</Tooltip>
          </Marker>
        ))}
      </MapContainer>

      {/* Zoom controls overlay: jump out to the whole city, or click any DA/van to zoom into a corner. */}
      <div style={{ position: 'absolute', top: 10, right: 10, zIndex: 1000 }}
        className="flex flex-col gap-1 items-end">
        <button onClick={fitAll}
          className="text-xs px-2 py-1 rounded bg-white/95 border border-gray-300 shadow hover:bg-gray-50">
          ⤢ Fit all
        </button>
        <span className="text-[10px] text-gray-600 bg-white/85 rounded px-1.5 py-0.5 shadow">
          click a 👤 DA / 🚐 van to zoom into the handoff
        </span>
      </div>
    </div>
  )
}
