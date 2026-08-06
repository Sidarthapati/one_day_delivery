// hub.js — Steps 2–5: receive the parcel at the origin hub (the moment M9's FlightAssignmentPort
// actually runs), add it to the bag it landed in (resolveOutbound only opens/finds the bag and
// assigns a stand — it does not add the parcel), list bags, and seal one (the real BAG_SEALED
// trigger M9 books off). All request bodies and response fields are snake_case (backend Jackson
// config) — confirmed live, not just assumed.

document.addEventListener('DOMContentLoaded', () => {
  if (!requireSession()) return;
  paintNav();
  fillCitySelect(document.getElementById('hub-city'));

  const savedOrigin = sessionStorage.getItem('m9demo_originHub');
  if (savedOrigin) document.getElementById('hub-city').value = savedOrigin;
  const savedRef = sessionStorage.getItem('m9demo_shipmentRef');
  if (savedRef) document.getElementById('shipment-ref').value = savedRef;
  const savedBagId = sessionStorage.getItem('m9demo_bagId');
  if (savedBagId) document.getElementById('bag-id').value = savedBagId;
  document.getElementById('bags-date').value = isoDate(new Date());

  document.getElementById('receive-btn').addEventListener('click', receive);
  document.getElementById('add-btn').addEventListener('click', addToBag);
  document.getElementById('refresh-bags-btn').addEventListener('click', loadBags);
});

function currentHubId() {
  return cityByCode(val('hub-city')).hubId;
}

async function receive() {
  const btn = document.getElementById('receive-btn');
  const shipmentRef = val('shipment-ref');
  if (!shipmentRef) { showResponse('receive-response', 'Enter a shipment reference (from the booking step).', true); return; }

  setLoading(btn, true);
  try {
    const data = await api('POST', `/hub/${currentHubId()}/receive`, { shipment_ref: shipmentRef });
    showResponse('receive-response', data, false);
    if (data.bag_id) {
      document.getElementById('bag-id').value = data.bag_id;
      sessionStorage.setItem('m9demo_bagId', data.bag_id);
    }
    if (data.flight_no) sessionStorage.setItem('m9demo_flightNo', data.flight_no);
    if (data.flight_date) sessionStorage.setItem('m9demo_flightDate', data.flight_date);
  } catch (e) {
    showResponse('receive-response', fmtError(e), true);
  } finally {
    setLoading(btn, false);
  }
}

async function addToBag() {
  const btn = document.getElementById('add-btn');
  const bagId = val('bag-id');
  const shipmentRef = val('shipment-ref');
  if (!bagId) { showResponse('add-response', 'Bag ID is required (receive the parcel first).', true); return; }

  setLoading(btn, true);
  try {
    const data = await api('POST', `/hub/${currentHubId()}/bags/${bagId}/add`, { shipment_ref: shipmentRef });
    showResponse('add-response', data, false);
    loadBags();
  } catch (e) {
    showResponse('add-response', fmtError(e), true);
  } finally {
    setLoading(btn, false);
  }
}

async function loadBags() {
  const container = document.getElementById('bags-container');
  container.innerHTML = '<div class="empty-state"><span class="spinner"></span></div>';
  try {
    const bags = await api('GET', `/hub/${currentHubId()}/bags?date=${val('bags-date')}`);
    if (!bags.length) { container.innerHTML = '<div class="empty-state">No bags yet today — receive a parcel first</div>'; return; }
    container.innerHTML = `
      <table class="data-table">
        <thead><tr><th>Flight</th><th>Dest</th><th>Status</th><th>Parcels</th><th>Weight</th><th></th></tr></thead>
        <tbody>
          ${bags.map(b => `
            <tr>
              <td>${esc(b.flight_no)} <span style="color:#999">(${esc(b.flight_date)})</span></td>
              <td>${esc(b.dest_hub)}</td>
              <td><span class="badge ${b.status === 'OPEN' ? 'badge-orange' : b.status === 'SEALED' ? 'badge-blue' : 'badge-gray'}">${esc(b.status)}</span></td>
              <td>${b.parcel_count}</td>
              <td>${(b.weight_grams / 1000).toFixed(1)} kg</td>
              <td>${b.status === 'OPEN'
                ? `<button class="btn btn-sm btn-success" onclick="sealBag('${b.bag_id}', this)">Seal</button>`
                : '—'}</td>
            </tr>`).join('')}
        </tbody>
      </table>`;
  } catch (e) {
    container.innerHTML = `<div class="empty-state" style="color:#c1121f">${esc(fmtError(e))}</div>`;
  }
}

async function sealBag(bagId, btn) {
  setLoading(btn, true);
  try {
    const data = await api('POST', `/hub/${currentHubId()}/bags/${bagId}/seal`);
    showResponse('seal-response', data, false);
    loadBags();
  } catch (e) {
    showResponse('seal-response', fmtError(e), true);
    setLoading(btn, false);
  }
}
