// tracking.js — Step 8: poll the customer "mine" endpoint and render a moving plane marker
// while the parcel's flight is airborne. Position is recomputed server-side on every request
// (nothing is stored) so this is always exactly up to date, never stale.

let map, marker, pollTimer;
const PLANE_ICON = L.divIcon({ html: '✈️', className: 'plane-icon', iconSize: [24, 24] });

document.addEventListener('DOMContentLoaded', () => {
  if (!requireSession()) return;
  paintNav();

  const savedRef = sessionStorage.getItem('m9demo_shipmentRef');
  if (savedRef) document.getElementById('track-ref').value = savedRef;

  map = L.map('map').setView([22.0, 78.5], 5);   // roughly centred on India
  L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
    attribution: '&copy; OpenStreetMap contributors',
    maxZoom: 10,
  }).addTo(map);

  document.getElementById('track-btn').addEventListener('click', startTracking);
  document.getElementById('stop-btn').addEventListener('click', stopTracking);
});

function startTracking() {
  const ref = val('track-ref');
  if (!ref) { showResponse('track-response', 'Enter a shipment reference.', true); return; }
  document.getElementById('track-btn').style.display = 'none';
  document.getElementById('stop-btn').style.display = '';
  poll(ref);
  pollTimer = setInterval(() => poll(ref), 4000);
}

function stopTracking() {
  clearInterval(pollTimer);
  document.getElementById('track-btn').style.display = '';
  document.getElementById('stop-btn').style.display = 'none';
}

async function poll(ref) {
  try {
    const data = await api('GET', `/api/v1/shipments/mine/${ref}`);
    document.getElementById('track-response').className = 'response';

    const stateBadge = document.getElementById('state-badge');
    stateBadge.style.display = '';
    stateBadge.textContent = data.state_label || data.state;

    const posStatusEl = document.getElementById('position-status');
    if (data.current_lat != null && data.current_lon != null) {
      posStatusEl.textContent = `In transit — last updated ${fmtTs(data.position_as_of)}`;
      const latLng = [data.current_lat, data.current_lon];
      if (!marker) {
        marker = L.marker(latLng, { icon: PLANE_ICON }).addTo(map);
      } else {
        marker.setLatLng(latLng);
      }
      map.panTo(latLng);
    } else {
      posStatusEl.textContent = 'Not airborne right now (not yet departed, or already landed).';
      if (marker) { map.removeLayer(marker); marker = null; }
    }
  } catch (e) {
    showResponse('track-response', fmtError(e), true);
  }
}
