// admin.js — read-only orders database (admin all-cities / station-manager city-scoped),
// custody badges, per-column filters + Booked sort, and the admin privileged cancel.

  // Last fetched rows + current filter/sort state. Filtering & sorting are client-side over the
  // already-loaded page (the table is small; server still applies station-manager city scoping).
  let _adminOrders = [];
  let _adminFilters = { customer_type: '', state: '', custody_city: '', payment_mode: '' };
  let _adminSortDir = 'desc';   // created_at direction

  const _ADMIN_TERMINAL = ['CANCELLED', 'DELIVERED', 'DROPPED', 'HUB_COLLECTED', 'RTO_COMPLETED'];

  // Display value used for both the cell and the column filter (null payment → "—").
  function adminFieldVal(r, field) {
    return field === 'payment_mode' ? (r.payment_mode || '—') : (r[field] ?? '');
  }

  async function loadAdminOrders() {
    const container = document.getElementById('admin-orders-container');
    const countEl = document.getElementById('admin-orders-count');
    container.innerHTML = '<div class="empty-state">Loading…</div>';
    countEl.textContent = '';
    try {
      const data = await api('GET', '/api/v1/admin/shipments?size=200');
      _adminOrders = data.shipments || [];
      renderAdminOrders();
    } catch (e) {
      container.innerHTML = `<div class="empty-state">Failed to load: ${esc(fmtError(e))}</div>`;
    }
  }

  function setAdminFilter(field, value) { _adminFilters[field] = value; renderAdminOrders(); }
  function toggleAdminSort() { _adminSortDir = _adminSortDir === 'desc' ? 'asc' : 'desc'; renderAdminOrders(); }
  function clearAdminFilters() {
    _adminFilters = { customer_type: '', state: '', custody_city: '', payment_mode: '' };
    renderAdminOrders();
  }

  // A compact <select> for one column; options are the distinct values present (selection persists
  // across re-renders because it is rebuilt from _adminFilters each time).
  function adminFilterSelect(field) {
    const cur = _adminFilters[field];
    const vals = [...new Set(_adminOrders.map(r => adminFieldVal(r, field)))]
      .filter(v => v !== '').sort();
    const opts = ['<option value="">All</option>'].concat(
      vals.map(v => `<option value="${esc(v)}" ${v === cur ? 'selected' : ''}>${esc(v)}</option>`));
    return `<select class="col-filter" onchange="setAdminFilter('${field}', this.value)">${opts.join('')}</select>`;
  }

  function renderAdminOrders() {
    const container = document.getElementById('admin-orders-container');
    const countEl = document.getElementById('admin-orders-count');
    const clearBtn = document.getElementById('admin-orders-clear');

    if (!_adminOrders.length) {
      container.innerHTML = '<div class="empty-state">No orders found.</div>';
      countEl.textContent = '0 orders';
      if (clearBtn) clearBtn.style.display = 'none';
      return;
    }

    // Apply column filters, then sort by Booked (created_at).
    let rows = _adminOrders.filter(r =>
      Object.entries(_adminFilters).every(([f, v]) => !v || adminFieldVal(r, f) === v));
    rows = rows.slice().sort((a, b) => {
      const da = new Date(a.created_at), db = new Date(b.created_at);
      return _adminSortDir === 'desc' ? db - da : da - db;
    });

    const active = Object.values(_adminFilters).some(Boolean);
    countEl.textContent = active
      ? `${rows.length} of ${_adminOrders.length} orders`
      : `${_adminOrders.length} order${_adminOrders.length === 1 ? '' : 's'} total`;
    if (clearBtn) clearBtn.style.display = active ? '' : 'none';

    // Only ADMIN may cancel (privileged, any lane); station managers see the table read-only.
    const canCancel = currentUser && currentUser.role === 'ADMIN';
    const sortArrow = _adminSortDir === 'desc' ? '▼' : '▲';

    const body = rows.length ? rows.map(r => {
      const custody = r.can_act
        ? `<span class="badge badge-green">${esc(r.custody_city)} · you act</span>`
        : `<span class="badge badge-gray">${esc(r.custody_city)}</span>`;
      const actionCell = !canCancel ? '' : `<td>${
        _ADMIN_TERMINAL.includes(r.state)
          ? '<span class="muted">—</span>'
          : `<button class="btn btn-sm btn-danger" onclick="cancelAdminOrder('${esc(r.shipment_ref)}',this)">Cancel</button>`
      }</td>`;
      return `
        <tr>
          <td><code>${esc(r.shipment_ref)}</code></td>
          <td>${esc(r.customer_type)}</td>
          <td><span class="role-pill">${esc(r.state)}</span></td>
          <td>${esc(r.origin_city)} → ${esc(r.dest_city)}</td>
          <td>${custody}</td>
          <td>${esc(r.sender_name)} → ${esc(r.receiver_name)}</td>
          <td>${esc(r.payment_mode || '—')}</td>
          <td style="text-align:right">${inr(r.total_price_paise)}</td>
          <td>${esc(fmtTs(r.created_at))}</td>
          ${actionCell}
        </tr>`; }).join('')
      : `<tr><td colspan="${canCancel ? 10 : 9}"><div class="empty-state">No orders match the filters.</div></td></tr>`;

    container.innerHTML = `
        <table class="data-table">
          <thead>
            <tr>
              <th>Ref</th><th>Type</th><th>State</th><th>Route</th><th>Custody</th>
              <th>Sender → Receiver</th><th>Payment</th><th style="text-align:right">Total</th>
              <th class="sortable" onclick="toggleAdminSort()" title="Sort by booked time">Booked ${sortArrow}</th>
              ${canCancel ? '<th></th>' : ''}
            </tr>
            <tr class="filter-row">
              <th></th>
              <th>${adminFilterSelect('customer_type')}</th>
              <th>${adminFilterSelect('state')}</th>
              <th></th>
              <th>${adminFilterSelect('custody_city')}</th>
              <th></th>
              <th>${adminFilterSelect('payment_mode')}</th>
              <th></th><th></th>${canCancel ? '<th></th>' : ''}
            </tr>
          </thead>
          <tbody>${body}</tbody>
        </table>`;
  }

  // Admin cancels any shipment from the orders database (privileged: either lane, no B2B
  // ownership check). Refunds PREPAID / reverses B2B credit server-side; reloads on success.
  async function cancelAdminOrder(ref, btn) {
    const reason = prompt(`Cancel ${ref}? Optional reason:`, 'ops cancellation');
    if (reason === null) return;
    setLoading(btn, true);
    const qs = reason.trim() ? '?reason=' + encodeURIComponent(reason.trim()) : '';
    try {
      const { status, data } = await orderApi('DELETE', `/api/v1/admin/shipments/${encodeURIComponent(ref)}${qs}`);
      if (status >= 200 && status < 300) {
        loadAdminOrders();
      } else {
        alert('Cancel failed: ' + (data && (data.detail || data.title) || ('HTTP ' + status)));
        setLoading(btn, false);
      }
    } catch (e) {
      alert('Network error cancelling ' + ref);
      setLoading(btn, false);
    }
  }
