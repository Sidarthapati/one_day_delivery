// airline.js — Steps 6–7: browse the freight consolidator's dated schedule for a lane + each
// flight's status, then look up the AWB M9 booked for a sealed bag and record the two ground-crew
// confirmations. The schedule is now a concrete per-date calendar (not a recurring weekly pattern),
// so the endpoint requires a date. All response fields are snake_case (backend Jackson config) —
// confirmed live.

document.addEventListener('DOMContentLoaded', () => {
  if (!requireSession()) return;
  paintNav();
  fillCitySelect(document.getElementById('lane-origin'));
  fillCitySelect(document.getElementById('lane-dest'));

  const origin = sessionStorage.getItem('m9demo_originHub');
  const dest = sessionStorage.getItem('m9demo_destHub');
  if (origin) document.getElementById('lane-origin').value = origin;
  if (dest) document.getElementById('lane-dest').value = dest;
  document.getElementById('lane-date').value = isoDate(new Date());

  const savedBagId = sessionStorage.getItem('m9demo_bagId');
  if (savedBagId) document.getElementById('awb-bag-id').value = savedBagId;

  document.getElementById('load-schedule-btn').addEventListener('click', loadSchedule);
  document.getElementById('lookup-awb-btn').addEventListener('click', lookupAwb);
});

async function loadSchedule() {
  const container = document.getElementById('schedule-container');
  const origin = val('lane-origin');
  const dest = val('lane-dest');
  const date = val('lane-date');
  container.innerHTML = '<div class="empty-state"><span class="spinner"></span></div>';
  try {
    const flights = await api('GET', `/airline/lanes/${origin}/${dest}/schedule?date=${date}`);
    if (!flights.length) { container.innerHTML = '<div class="empty-state">No flights on this lane for that date</div>'; return; }

    // Ask each flight's status in parallel via the dedicated status endpoint — the same call the
    // real poll job makes (distinct from the status already on the schedule row above).
    const statuses = await Promise.all(flights.map(f =>
      api('GET', `/airline/flights/${f.flight_no}/${date}/status`).catch(() => null)));

    container.innerHTML = `
      <table class="data-table">
        <thead><tr><th>Flight</th><th>Carrier</th><th>Departs</th><th>Arrives</th><th>Status</th></tr></thead>
        <tbody>
          ${flights.map((f, i) => {
            const s = statuses[i];
            const badgeClass = s?.status === 'CANCELLED' ? 'badge-red' : s?.status === 'DELAYED' ? 'badge-orange' : 'badge-green';
            return `
              <tr>
                <td>${esc(f.flight_no)}</td>
                <td>${esc(f.carrier)}</td>
                <td>${fmtTs(f.departure_at)}</td>
                <td>${fmtTs(f.arrival_at)}</td>
                <td>${s ? `<span class="badge ${badgeClass}">${esc(s.status)}</span>` : '—'}</td>
              </tr>`;
          }).join('')}
        </tbody>
      </table>`;
  } catch (e) {
    container.innerHTML = `<div class="empty-state" style="color:#c1121f">${esc(fmtError(e))}</div>`;
  }
}

async function lookupAwb() {
  const btn = document.getElementById('lookup-awb-btn');
  const bagId = val('awb-bag-id');
  if (!bagId) { showResponse('awb-response', 'Enter a bag ID.', true); return; }

  setLoading(btn, true);
  try {
    const awb = await api('GET', `/airline/awb/by-bag/${bagId}`);
    renderAwb(awb);
    document.getElementById('awb-response').className = 'response';
  } catch (e) {
    document.getElementById('awb-container').innerHTML = '';
    showResponse('awb-response', e.status === 404 ? 'No booking yet — wait a moment and try again.' : fmtError(e), true);
  } finally {
    setLoading(btn, false);
  }
}

function renderAwb(awb) {
  document.getElementById('awb-container').innerHTML = `
    <table class="data-table">
      <tr><th>AWB No</th><td>${esc(awb.awb_no)}</td></tr>
      <tr><th>Flight</th><td>${esc(awb.flight_no)} (${esc(awb.flight_date)})</td></tr>
      <tr><th>Lane</th><td>${esc(awb.origin_hub)} → ${esc(awb.dest_hub)}</td></tr>
      <tr><th>Weight / parcels</th><td>${(awb.total_weight_grams / 1000).toFixed(1)} kg · ${awb.parcel_count}</td></tr>
      <tr><th>Cost</th><td>${inr(awb.cost_paise)}</td></tr>
      <tr><th>Provider ref</th><td>${esc(awb.provider_ref)}</td></tr>
      <tr><th>Status</th><td><span class="badge ${awb.status === 'BOOKED' ? 'badge-blue' : 'badge-gray'}">${esc(awb.status)}</span></td></tr>
      <tr><th>Handed over</th><td>${fmtTs(awb.handed_over_at)}</td></tr>
      <tr><th>Loaded</th><td>${fmtTs(awb.loaded_at)}</td></tr>
    </table>
    <div class="form-row" style="margin-top:1rem">
      <button class="btn btn-primary btn-sm" onclick="groundAction('${awb.id}', 'handed-over')">Mark handed over</button>
      <button class="btn btn-success btn-sm" onclick="groundAction('${awb.id}', 'loaded')">Mark loaded</button>
    </div>`;
}

async function groundAction(awbId, action) {
  try {
    const awb = await api('POST', `/airline/awb/${awbId}/${action}`);
    renderAwb(awb);
  } catch (e) {
    showResponse('awb-response', fmtError(e), true);
  }
}
