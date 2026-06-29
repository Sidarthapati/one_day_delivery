// orders.js — M4 booking (B2C/B2B), Leaflet map picker, Razorpay checkout,
// recent bookings, customer cancel, pickup-OTP.

  // ════════════════════ ORDERS (M4) ════════════════════
  const ORDER_CITIES = {
    BLR:{ name:'Bengaluru', state:'KA', pin:'560001', addr:'1 MG Road',         lat:12.9716, lon:77.5946 },
    DEL:{ name:'Delhi',     state:'DL', pin:'110001', addr:'1 Connaught Place', lat:28.6139, lon:77.2090 },
    BOM:{ name:'Mumbai',    state:'MH', pin:'400001', addr:'1 Marine Drive',    lat:19.0760, lon:72.8777 },
    MAA:{ name:'Chennai',   state:'TN', pin:'600001', addr:'1 Mount Road',      lat:13.0827, lon:80.2707 },
    HYD:{ name:'Hyderabad', state:'TG', pin:'500001', addr:'1 Banjara Hills',   lat:17.3850, lon:78.4867 },
  };
  let recentBookings = [];

  // Location is map-first now: city + pincode are derived from each dropped pin,
  // so login just resets both forms' pickup/drop cards to empty.
  function resetLocationCards() {
    ['b2c', 'b2b'].forEach(form => ['origin', 'dest'].forEach(leg => clearPick(form, leg)));
    refreshQuote('b2c');   // back to the placeholder state
  }

  function setupOrderExperience(role) {
    resetLocationCards();
    document.getElementById('order-user-email').textContent = currentUser.email;
    document.getElementById('order-user-role').textContent = role;
    document.getElementById('order-user-id').textContent = currentUserId || '—';

    // Only customer accounts may book. ADMIN / STATION_MANAGER do NOT book — they get read-only
    // access to the orders database instead (admin = all cities; station manager = their city).
    // The B2B card is for B2B_USER; B2C_CUSTOMER → B2C; C2C_CUSTOMER → C2C (reuses the b2c card).
    const isAdmin = role === 'ADMIN';
    const isStationManager = role === 'STATION_MANAGER';
    const isOrdersViewer = isAdmin || isStationManager;
    const showB2b = role === 'B2B_USER';
    const showB2c = role === 'B2C_CUSTOMER' || role === 'C2C_CUSTOMER';
    const canBook = showB2b || showB2c;
    document.getElementById('b2c-card-title').textContent =
      role === 'B2C_CUSTOMER' ? 'Book B2C Shipment' : 'Book C2C Shipment';
    document.getElementById('card-b2c-booking').style.display = showB2c ? '' : 'none';
    document.getElementById('card-b2b-booking').style.display = showB2b ? '' : 'none';
    document.getElementById('card-recent-bookings').style.display = canBook ? '' : 'none';
    // Orders database: admin (all cities) or station manager (their city, custody-aware).
    document.getElementById('card-admin-orders').style.display = isOrdersViewer ? '' : 'none';
    document.getElementById('admin-orders-desc').textContent = isStationManager
      ? 'Read-only. You see every shipment whose pickup or delivery is in your city. “You act” marks the parcels currently in your custody.'
      : 'Read-only. As an admin you browse every shipment here — booking is reserved for customer accounts.';
    document.getElementById('order-no-booking').style.display = (canBook || isOrdersViewer) ? 'none' : '';

    // For a retail (B2C/C2C) booking the sender IS the logged-in account, so auto-fill the sender
    // name and phone from the stored account and lock them. (B2B's sender is the warehouse → editable.)
    if (showB2c) {
      applyAccountSender('o-b2c-sname', currentUser && currentUser.name);
      applyAccountSender('o-b2c-sphone', currentUser && currentUser.phone);
    }

    recentBookings = [];
    renderRecent();
    // Bookings are persisted server-side and keyed to the logged-in user, so load the full
    // history (not just this session's bookings) on login and after every refresh.
    if (canBook) loadMyBookings();
    if (isOrdersViewer) loadAdminOrders();
    // Both the booking forms and the orders database live on the Orders tab.
    switchTab('orders');
  }

  // Fills the given sender input from the logged-in account and locks it (read-only),
  // since the booker is the sender. No-op if the value is unknown (older token without it).
  function applyAccountSender(inputId, value) {
    const el = document.getElementById(inputId);
    if (!el) return;
    if (value) {
      el.value = value;
      el.readOnly = true;
      el.classList.add('locked-field');
      el.title = 'Auto-filled from your account';
    } else {
      el.readOnly = false;
      el.classList.remove('locked-field');
    }
  }

  function selectPay(btn) {
    document.querySelectorAll('#b2c-pay-seg button').forEach(b => b.classList.remove('active'));
    btn.classList.add('active');
    refreshQuote('b2c');   // COD vs PREPAID changes the price (COD surcharge)
  }

  // ── Live M2 price preview (read-only cost box) ──────────────────────────────
  // Mirrors what M4 sends to M2 at booking: chargeable weight = max(actual, L·W·H/5).
  let _quoteTimer = null;
  function quoteDirty(form) {
    clearTimeout(_quoteTimer);
    _quoteTimer = setTimeout(() => refreshQuote(form), 350);
  }

  function setCostBox(form, amount, note, state) {
    const box = document.getElementById(`o-${form}-cost-box`);
    if (!box) return;
    document.getElementById(`o-${form}-cost-amount`).textContent = amount;
    document.getElementById(`o-${form}-cost-note`).innerHTML = note;
    box.classList.toggle('warn', state === 'warn');
    box.classList.toggle('loading', state === 'loading');
  }

  async function refreshQuote(form) {
    if (form !== 'b2c') return;                  // live preview wired for B2C
    const o = picked.b2c.origin, d = picked.b2c.dest;
    if (!o || !d) { setCostBox('b2c', '—', 'Set pickup &amp; drop on the map to see the price.', ''); return; }
    const weight = +val('o-b2c-weight') || 0;
    if (weight <= 0) { setCostBox('b2c', '—', 'Enter a weight to see the price.', ''); return; }
    const l = +val('o-b2c-len') || 0, w = +val('o-b2c-wid') || 0, h = +val('o-b2c-hei') || 0;
    const chargeable = Math.max(weight, Math.floor(l * w * h / 5));
    const mode = document.querySelector('#b2c-pay-seg button.active').dataset.mode;
    const reqBody = {
      customer_type: 'B2C',
      delivery_type: o.city === d.city ? 'SAME_CITY' : 'INTERCITY',
      origin_city: o.city, dest_city: d.city,
      chargeable_weight_grams: chargeable,
      declared_value_paise: (+val('o-b2c-dval') || 0) * 100 || null,
      payment_mode: mode,
    };
    setCostBox('b2c', '…', 'Pricing…', 'loading');
    try {
      const { status, data } = await orderApi('POST', '/api/v1/pricing/quote', reqBody);
      if (status >= 200 && status < 300) {
        const b = data.breakdown || {};
        const parts = [`base ${inr(b.base_freight)}`];
        if (b.cod_charge) parts.push(`COD ${inr(b.cod_charge)}`);
        parts.push(`+ GST`);
        setCostBox('b2c', inr(data.total_price_paise),
          `Chargeable ${(chargeable / 1000).toFixed(2)} kg · ${parts.join(' · ')}`, '');
      } else if (status === 422) {
        setCostBox('b2c', '—', '⚠️ ' + esc(data && (data.detail || data.title) || 'No rate configured for this route.'), 'warn');
      } else {
        setCostBox('b2c', '—', 'Could not fetch price right now.', '');
      }
    } catch {
      setCostBox('b2c', '—', 'Could not reach pricing service.', '');
    }
  }

  // ── Published rate chart (read-only tariff browser) ─────────────────────────
  const CITY_NAMES = {
    DEL: 'Delhi', BBI: 'Bhubaneswar', CCU: 'Kolkata', BOM: 'Mumbai', HYD: 'Hyderabad',
    BLR: 'Bengaluru', IXC: 'Chandigarh', GAU: 'Guwahati', MAA: 'Chennai',
  };
  const CITY_ORDER = ['DEL', 'BBI', 'CCU', 'BOM', 'HYD', 'BLR', 'IXC', 'GAU', 'MAA'];

  async function openRateChart(customerType) {
    const type = customerType || 'B2C';
    document.getElementById('rate-chart-modal').style.display = 'flex';
    const bodyEl = document.getElementById('rate-chart-body');
    bodyEl.innerHTML = 'Loading…';
    try {
      const { status, data } = await orderApi('GET', '/api/v1/pricing/rate-card?customer_type=' + type);
      if (status >= 200 && status < 300) renderRateChart(data, type);
      else bodyEl.innerHTML = rateChartToggle(type) + `<p class="muted">No published tariff for ${type}.</p>`;
    } catch {
      bodyEl.innerHTML = '<p class="muted">Could not reach the pricing service.</p>';
    }
  }
  function closeRateChart() { document.getElementById('rate-chart-modal').style.display = 'none'; }

  // Customer-type switcher so any user (B2C / C2C / B2B) can view each published tariff.
  function rateChartToggle(active) {
    return '<div class="rate-toggle">' + ['B2C', 'C2C', 'B2B'].map(tp =>
      `<button class="${tp === active ? 'active' : ''}" onclick="openRateChart('${tp}')">${tp}</button>`).join('') + '</div>';
  }

  function renderRateChart(t, activeType) {
    document.getElementById('rate-chart-sub').textContent =
      `${t.customer_type} · ${t.code} ${t.version} · base price = first ${(t.slab_grams / 1000)} kg`;

    // Build the symmetric city-pair matrix from the flat rate list.
    const lookup = {}; const present = new Set();
    (t.rates || []).forEach(r => {
      (lookup[r.origin_city] = lookup[r.origin_city] || {})[r.dest_city] = r.base_price_paise;
      present.add(r.origin_city); present.add(r.dest_city);
    });
    const cities = [...present].sort((a, b) => {
      const ia = CITY_ORDER.indexOf(a), ib = CITY_ORDER.indexOf(b);
      return (ia < 0 ? 99 : ia) - (ib < 0 ? 99 : ib);
    });
    const cityLabel = c => `${CITY_NAMES[c] || c}<br><span class="muted">${c}</span>`;

    let head = '<tr><th>From \\ To</th>' + cities.map(c => `<th>${cityLabel(c)}</th>`).join('') + '</tr>';
    let rows = cities.map(o => {
      const tds = cities.map(d => {
        if (o === d) return '<td class="rm-diag">—</td>';
        const p = lookup[o] && lookup[o][d];
        return `<td>${p ? '₹' + (p / 100) : '<span class="muted">—</span>'}</td>`;
      }).join('');
      return `<tr><th>${cityLabel(o)}</th>${tds}</tr>`;
    }).join('');

    const r = p => '₹' + (p / 100).toLocaleString('en-IN');
    const discountNote = t.discount_bps > 0
      ? `<li><b>Account discount:</b> ${(t.discount_bps / 100)}% off the base freight, applied at booking (B2B negotiated rate).</li>`
      : '';
    const rules = `
      <ul class="rate-rules">
        <li><b>Base price</b> is the charge for the first <b>${t.slab_grams / 1000} kg</b> of a city pair (table above, in ₹).</li>
        <li><b>Volumetric weight</b> = L × W × H ÷ ${t.volumetric_divisor} (kg). Chargeable weight = <b>max(actual, volumetric)</b>.
            <span class="muted">e.g. a 40×30×20 cm box = 24,000 ÷ ${t.volumetric_divisor} = 4.8 kg.</span></li>
        <li><b>Additional weight:</b> each extra ${t.slab_grams / 1000} kg slab is charged at ${t.first_slab_pct - t.slab_decrement_pct}% of the base
            and decreases ${t.slab_decrement_pct}% per slab, down to a floor of <b>${t.slab_floor_pct}%</b>.</li>
        <li><b>COD charge:</b> max(${r(t.cod_min_paise)}, ${(t.cod_pct_bps / 100)}% of declared value). Prepaid has none.</li>
        <li><b>GST:</b> ${(t.gst_bps / 100)}% on (freight + COD).</li>
        <li><b>Same-city</b> base price: ${r(t.same_city_base_price_paise)} for the first slab.</li>
        ${discountNote}
      </ul>`;

    document.getElementById('rate-chart-body').innerHTML =
      rateChartToggle(activeType || t.customer_type) +
      `<table class="rate-matrix"><thead>${head}</thead><tbody>${rows}</tbody></table>${rules}`;
  }

  // Builds an address block from a confirmed map pin (city + pincode are derived
  // from the pin, never typed). p = picked[form][leg].
  function legAddress(p) {
    const c = ORDER_CITIES[p.city];
    return {
      house_floor: p.houseFloor || null,
      building_street: p.buildingStreet || null,
      area_locality: p.areaLocality || null,
      line1: p.line1 || p.buildingStreet || c.addr,
      city: c.name, pincode: p.pincode, state: c.state,
      latitude: p.lat, longitude: p.lon,
    };
  }

  // Booking is map-first: both legs must have a confirmed, serviceable pin.
  function ensureLocations(form) {
    if (picked[form].origin && picked[form].dest) return true;
    showResponse(`${form}-booking-response`,
      { body: { detail: 'Set both the pickup and drop locations on the map before booking.' } }, true);
    return false;
  }

  function buildB2cBody() {
    const o = picked.b2c.origin, d = picked.b2c.dest;
    const mode = document.querySelector('#b2c-pay-seg button.active').dataset.mode;
    const body = {
      sender_name: val('o-b2c-sname'), sender_phone: val('o-b2c-sphone'), sender_email: 'sender@example.com',
      origin_address: legAddress(o),
      origin_city: o.city, origin_pincode: o.pincode,
      receiver_name: val('o-b2c-rname'), receiver_phone: val('o-b2c-rphone'), receiver_email: 'receiver@example.com',
      dest_address: legAddress(d),
      dest_city: d.city, dest_pincode: d.pincode,
      weight_grams: +val('o-b2c-weight') || 1000,
      length_cm: +val('o-b2c-len') || 20, width_cm: +val('o-b2c-wid') || 15, height_cm: +val('o-b2c-hei') || 10,
      declared_value_paise: (+val('o-b2c-dval') || 0) * 100 || null,
      pickup_type: 'DA_PICKUP', drop_type: 'DA_DELIVERY', payment_mode: mode,
    };
    // PREPAID razorpay_* fields are injected by the payment flow (payThenBook) after
    // a real gateway order + signed payment; COD carries none.
    return body;
  }

  async function bookB2c() {
    if (!ensureLocations('b2c')) return;
    const body = buildB2cBody();
    // PREPAID goes through the payment gateway first; COD books straight away.
    if (body.payment_mode === 'PREPAID') payThenBook(body);
    else submitBooking(body);
  }

  async function submitBooking(body) {
    const btn = document.querySelector('[onclick="bookB2c()"]');
    setLoading(btn, true);
    try {
      const { status, data } = await orderApi('POST', '/api/v1/b2c/shipments', body);
      renderBookingResult('b2c', status, data);
    } catch (e) {
      showResponse('b2c-booking-response', { body: { detail: 'Network error — is the backend up?' } }, true);
    } finally { setLoading(btn, false); }
  }

  // ── Payment gateway (Razorpay-style; mock/test mode locally) ────────────────
  // 1) price + mint a gateway order, 2) open checkout, 3) on pay → signed payment,
  // 4) book with the payment proof (the backend verifies the HMAC signature before capture).
  let _checkoutCtx = null;

  async function payThenBook(body) {
    const btn = document.querySelector('[onclick="bookB2c()"]');
    setLoading(btn, true);
    try {
      const { status, data } = await orderApi('POST', '/api/v1/payments/order', body);
      if (status >= 400) { renderBookingResult('b2c', status, data); return; }
      openCheckout(data, body);   // data: { order_id, amount_paise, currency, key_id, mock }
    } catch (e) {
      showResponse('b2c-booking-response', { body: { detail: 'Could not start payment — is the backend up?' } }, true);
    } finally { setLoading(btn, false); }
  }

  function openCheckout(order, body) {
    // Live/test-API mode → Razorpay's hosted checkout (real card UI + real signature).
    if (!order.mock && window.Razorpay) {
      const rzp = new Razorpay({
        key: order.key_id,
        amount: order.amount_paise,
        currency: order.currency || 'INR',
        order_id: order.order_id,
        name: '1DD Logistics',
        description: 'Shipment booking',
        prefill: { name: val('o-b2c-sname'), contact: val('o-b2c-sphone') },
        theme: { color: '#1a1a2e' },
        handler: (res) => {
          body.razorpay_order_id = res.razorpay_order_id;
          body.razorpay_payment_id = res.razorpay_payment_id;
          body.razorpay_signature = res.razorpay_signature;
          submitBooking(body);   // backend verifies the real Razorpay signature → captures → books
        },
        modal: { ondismiss: () => showResponse('b2c-booking-response',
          { body: { detail: 'Payment cancelled — shipment not booked.' } }, true) },
      });
      rzp.on('payment.failed', (e) => showResponse('b2c-booking-response',
        { body: { detail: 'Payment failed: ' + (e.error && e.error.description || 'unknown') } }, true));
      rzp.open();
      return;
    }
    // Mock/test gateway → local test-mode modal.
    _checkoutCtx = { order, body };
    document.getElementById('pay-amount').textContent = inr(order.amount_paise);
    document.getElementById('pay-btn').textContent = 'Pay ' + inr(order.amount_paise);
    document.getElementById('pay-order-id').textContent = order.order_id;
    document.getElementById('pay-key').innerHTML = order.key_id +
      (order.mock ? ' <span class="pay-test-badge">TEST MODE</span>' : '');
    document.getElementById('pay-error').textContent = '';
    document.getElementById('pay-modal').style.display = 'flex';
  }

  function closeCheckout() {
    document.getElementById('pay-modal').style.display = 'none';
    _checkoutCtx = null;
  }

  async function doPay() {
    if (!_checkoutCtx) return;
    const payBtn = document.getElementById('pay-btn');
    setLoading(payBtn, true);
    document.getElementById('pay-error').textContent = '';
    try {
      const { status, data } = await orderApi('POST', '/api/v1/payments/mock/pay',
        { order_id: _checkoutCtx.order.order_id });
      if (status >= 400) { document.getElementById('pay-error').textContent = 'Payment failed. Try again.'; return; }
      const body = _checkoutCtx.body;
      body.razorpay_order_id = data.razorpay_order_id;
      body.razorpay_payment_id = data.razorpay_payment_id;
      body.razorpay_signature = data.razorpay_signature;
      closeCheckout();
      await submitBooking(body);   // backend verifies signature → captures → books
    } catch (e) {
      document.getElementById('pay-error').textContent = 'Payment network error.';
    } finally { setLoading(payBtn, false); }
  }

  function buildB2bBody(overLimit) {
    const o = picked.b2b.origin, d = picked.b2b.dest;
    return {
      b2b_account_id: overLimit ? 'b2b0dead-fade-0000-0000-000000000001' : val('o-b2b-acct'),
      purchase_order_ref: val('o-b2b-po') || null,
      sender_name: val('o-b2b-sname'), sender_phone: val('o-b2b-sphone'), sender_email: 'warehouse@acmecorp.example.com',
      origin_address: legAddress(o),
      origin_city: o.city, origin_pincode: o.pincode,
      receiver_name: val('o-b2b-rname'), receiver_phone: val('o-b2b-rphone'), receiver_email: 'accounts@client.example.com',
      dest_address: legAddress(d),
      dest_city: d.city, dest_pincode: d.pincode,
      weight_grams: +val('o-b2b-weight') || 2000,
      length_cm: +val('o-b2b-len') || 30, width_cm: +val('o-b2b-wid') || 25, height_cm: +val('o-b2b-hei') || 20,
      declared_value_paise: (+val('o-b2b-dval') || 0) * 100,
      pickup_type: 'DA_PICKUP', drop_type: 'DA_DELIVERY',
    };
  }

  async function bookB2b(overLimit) {
    if (!ensureLocations('b2b')) return;
    const btn = document.querySelector(overLimit ? '[onclick="bookB2b(true)"]' : '[onclick="bookB2b()"]');
    setLoading(btn, true);
    try {
      const { status, data } = await orderApi('POST', '/api/v1/b2b/shipments', buildB2bBody(overLimit));
      renderBookingResult('b2b', status, data);
    } catch (e) {
      showResponse('b2b-booking-response', { body: { detail: 'Network error — is the backend up?' } }, true);
    } finally { setLoading(btn, false); }
  }

  // ── Map picker (Leaflet + OpenStreetMap) ────────────────────────────────────
  // A pin's WGS84 lat/lon is exactly what M3 (grid) consumes. The serviceability VERDICT
  // comes from M3's real H3 grid via GET /api/grid/serviceable-at — Nominatim only supplies
  // the human address + pincode text. picked[form][leg] holds the confirmed pin until
  // booking, where legAddress() folds it into the request.
  const GRID_TO_DEMO = { bangalore:'BLR', delhi:'DEL', mumbai:'BOM', chennai:'MAA', hyderabad:'HYD' };
  const INDIA_VIEW = { center: [22.5, 79], zoom: 5 };
  const PIN_ICON = L.divIcon({ className: 'map-pin', html: '📍', iconSize: [28,28], iconAnchor: [14,28] });
  const picked = { b2c: {}, b2b: {} };
  let _map = null, _marker = null, _mapState = null, _mapPick = null, _searchTimer = null;

  // Ask M3 whether a raw point is serviceable (which city / H3 hex it lands in).
  async function m3ServiceableAt(lat, lon) {
    try {
      const r = await fetch(`/api/grid/serviceable-at?lat=${lat}&lon=${lon}`, { headers: { 'Accept': 'application/json' } });
      if (!r.ok) return null;
      return await r.json();   // { serviceable, cityCode, cityId, hexId, h3Index }
    } catch { return null; }
  }

  function clearPick(form, leg) {
    delete picked[form][leg];
    const body = document.getElementById(`o-${form}-${leg}-loc`);
    if (body) {
      body.closest('.loc-card')?.classList.remove('set');
      body.innerHTML = `<div class="loc-empty">No ${leg === 'origin' ? 'pickup' : 'drop'} point yet — tap “Set on map”.</div>`;
    }
  }

  // Renders the M3-derived city + pincode + coords (+ the resolving H3 hex) into the card.
  function renderLocCard(form, leg, p) {
    const body = document.getElementById(`o-${form}-${leg}-loc`);
    body.closest('.loc-card')?.classList.add('set');
    const c = ORDER_CITIES[p.city];
    body.innerHTML =
      `<div class="loc-city">${c.name} (${p.city}) · ${p.pincode}</div>` +
      `<div class="loc-coords">${p.lat}, ${p.lon}</div>` +
      (p.hex ? `<div class="loc-addr">M3 hex ${p.hex}</div>` : '') +
      (p.label ? `<div class="loc-addr">${esc(p.label)}</div>` : '');
    // Single funnel for both the map picker and the saved-address picker → keep the M2 cost box live.
    refreshQuote(form);
  }

  // opts (optional) lets other modules reuse the picker: { title, onConfirm(pick) }. When given,
  // confirm fires onConfirm instead of writing to the booking form's picked[form][leg].
  function openMapPicker(form, leg, opts) {
    _mapState = opts || { form, leg };
    _mapPick = null;
    const isLeg = _mapState.leg === 'origin' || _mapState.leg === 'dest';
    document.getElementById('map-title').textContent =
      (_mapState.title) ? _mapState.title
      : (_mapState.leg === 'origin' ? '📦 Pickup' : '🎯 Drop') + ' location';
    document.getElementById('map-search').value = '';
    document.getElementById('map-search-results').style.display = 'none';
    document.getElementById('map-confirm').disabled = true;
    setMapStatus('Search for an area, then click or drag the pin to the exact point.');
    document.getElementById('map-modal').style.display = 'flex';

    // Init after the container is visible so Leaflet measures it correctly.
    setTimeout(() => {
      if (!_map) {
        _map = L.map('map-canvas');
        L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png',
          { maxZoom: 19, attribution: '© OpenStreetMap contributors' }).addTo(_map);
        _map.on('click', e => placeMarker(e.latlng.lat, e.latlng.lng));
      }
      const prev = isLeg && _mapState.form ? picked[_mapState.form][_mapState.leg] : null;
      if (prev) { _map.setView([prev.lat, prev.lon], 15); placeMarker(prev.lat, prev.lon); }
      else      { _map.setView(INDIA_VIEW.center, INDIA_VIEW.zoom); if (_marker) { _map.removeLayer(_marker); _marker = null; } }
      _map.invalidateSize();
    }, 60);
  }

  function closeMapPicker() {
    document.getElementById('map-modal').style.display = 'none';
    // If the picker was opened from the address form, bring that form back (confirm or cancel).
    if (window._reopenAddrForm) {
      window._reopenAddrForm = false;
      const m = document.getElementById('addr-modal');
      if (m) m.style.display = 'flex';
    }
  }

  function setMapStatus(html, cls) {
    const el = document.getElementById('map-status');
    el.className = 'map-status' + (cls ? ' ' + cls : '');
    el.innerHTML = html;
  }

  function placeMarker(lat, lon) {
    if (!_marker) {
      _marker = L.marker([lat, lon], { draggable: true, icon: PIN_ICON }).addTo(_map);
      _marker.on('dragend', () => { const p = _marker.getLatLng(); reverseGeocode(p.lat, p.lng); });
    } else {
      _marker.setLatLng([lat, lon]);
    }
    reverseGeocode(lat, lon);
  }

  async function reverseGeocode(lat, lon) {
    setMapStatus('Checking serviceability with M3…');
    document.getElementById('map-confirm').disabled = true;

    // Address + pincode text from Nominatim; the serviceability verdict from M3 — in parallel.
    const [addr, m3] = await Promise.all([
      fetch(`https://nominatim.openstreetmap.org/reverse?format=jsonv2&addressdetails=1&lat=${lat}&lon=${lon}`,
        { headers: { 'Accept': 'application/json' } }).then(r => r.json()).catch(() => null),
      m3ServiceableAt(lat, lon),
    ]);

    let pincode = '', line1 = '', label = '';
    if (addr) {
      const a = addr.address || {};
      pincode = a.postcode ? a.postcode.replace(/\s/g, '').slice(0, 6) : '';
      line1 = [a.house_number, a.road || a.neighbourhood || a.suburb].filter(Boolean).join(' ') || (addr.name || '');
      label = addr.display_name || '';
    }

    // NOTE: the backend serializes JSON as snake_case, so read city_code / h3_index.
    const serviceable = !!(m3 && m3.serviceable);
    const demoCity = serviceable ? GRID_TO_DEMO[m3.city_code] : null;
    // Booking needs a 6-digit pincode; fall back to the city default if Nominatim had none.
    if (demoCity && pincode.length !== 6) pincode = ORDER_CITIES[demoCity].pin;

    _mapPick = {
      lat: +(+lat).toFixed(6), lon: +(+lon).toFixed(6),
      pincode, line1, label,
      city: demoCity,
      hex: serviceable ? m3.h3_index : null,
    };

    if (serviceable && demoCity) {
      setMapStatus(`✓ <b>Serviceable</b> — M3 grid: <b>${ORDER_CITIES[demoCity].name}</b>, hex <code>${m3.h3_index}</code>, pincode <b>${pincode}</b>` +
        (label ? `<br><span class="muted">${esc(label)}</span>` : ''), 'ok');
      document.getElementById('map-confirm').disabled = false;
    } else if (m3) {
      setMapStatus('✕ Outside M3\'s serviceable grid (Bengaluru, Delhi, Mumbai, Chennai, Hyderabad). Drag the pin inside a covered city.', 'err');
    } else {
      setMapStatus('Couldn\'t reach M3 serviceability — is the grid seeded? Check the backend.', 'warn');
    }
  }

  function confirmMapPicker() {
    if (!_mapPick || !_mapPick.city) return;
    if (_mapState && typeof _mapState.onConfirm === 'function') {
      _mapState.onConfirm(_mapPick);    // reused by the address book (cart.js)
      closeMapPicker();
      return;
    }
    const { form, leg } = _mapState;
    picked[form][leg] = _mapPick;       // the pin is now the source of truth for city + pincode
    renderLocCard(form, leg, _mapPick); // renderLocCard refreshes the M2 cost box
    closeMapPicker();
  }

  function mapSearchDebounced() { clearTimeout(_searchTimer); _searchTimer = setTimeout(mapSearch, 450); }

  async function mapSearch() {
    const q = document.getElementById('map-search').value.trim();
    const box = document.getElementById('map-search-results');
    if (q.length < 3) { box.style.display = 'none'; box.innerHTML = ''; return; }
    try {
      const r = await fetch(`https://nominatim.openstreetmap.org/search?format=jsonv2&countrycodes=in&limit=5&q=${encodeURIComponent(q)}`,
        { headers: { 'Accept': 'application/json' } });
      const list = await r.json();
      box.innerHTML = list.length
        ? list.map(x => `<div class="map-search-item" onclick="pickSearch(${x.lat},${x.lon})">${x.display_name}</div>`).join('')
        : '<div class="map-search-item muted">No matches</div>';
      box.style.display = '';
    } catch { box.style.display = 'none'; box.innerHTML = ''; }
  }

  function pickSearch(lat, lon) {
    const box = document.getElementById('map-search-results');
    box.style.display = 'none'; box.innerHTML = '';
    document.getElementById('map-search').value = '';
    _map.setView([lat, lon], 16);
    placeMarker(+lat, +lon);
  }

  function renderBookingResult(kind, status, data) {
    showResponse(kind + '-booking-response', (status >= 400 ? { body: data } : data), status >= 400);
    const sumEl = document.getElementById(kind + '-booking-summary');
    if (status >= 200 && status < 300 && data && data.shipment_ref) {
      recentBookings.unshift({
        ref: data.shipment_ref, state: data.state_label || data.state, rawState: data.state,
        // Use the server's real customer type (B2C/C2C/B2B). The form 'kind' is only a fallback —
        // it would mislabel a C2C booking (which reuses the b2c form) as B2C.
        type: (data.customer_type || kind).toUpperCase(),
        total: data.pricing ? data.pricing.total_price_paise : null,
        by: currentUser.email,
      });
      renderRecent();
      const eta = data.eta_promised ? new Date(data.eta_promised).toLocaleString('en-IN') : '—';
      sumEl.innerHTML = `<div class="summary-grid">
        <div class="summary-box"><div class="s-label">Reference</div><div class="s-value">${esc(data.shipment_ref)}</div></div>
        <div class="summary-box"><div class="s-label">State</div><div class="s-value">${esc(data.state_label || data.state)}</div></div>
        <div class="summary-box"><div class="s-label">Total</div><div class="s-value">${data.pricing ? inr(data.pricing.total_price_paise) : '—'}</div></div>
        <div class="summary-box"><div class="s-label">ETA</div><div class="s-value" style="font-size:.8rem">${eta}</div></div>
        <div class="summary-box"><div class="s-label">Booked by</div><div class="s-value" style="font-size:.8rem">${esc(currentUser.email)}</div></div>
      </div>`;
    } else {
      sumEl.innerHTML = '';
    }
  }

  // Loads the logged-in user's full booking history from the server (all sessions), so a refresh
  // re-populates the list instead of losing it. Falls back silently if the backend is unreachable.
  async function loadMyBookings() {
    const c = document.getElementById('recent-bookings');
    c.innerHTML = '<div class="empty-state">Loading your bookings…</div>';
    try {
      const { status, data } = await orderApi('GET', '/api/v1/shipments/mine?limit=100');
      if (status >= 200 && status < 300 && Array.isArray(data)) {
        recentBookings = data.map(s => ({
          ref: s.shipment_ref,
          state: s.state_label || s.state,
          rawState: s.state,
          type: (s.customer_type || '').toUpperCase(),
          total: s.total_price_paise != null ? s.total_price_paise : null,
          by: currentUser.email,
        }));
      }
    } catch (e) { /* leave whatever is in recentBookings */ }
    renderRecent();
  }

  let bookingFilter = 'active';   // default: hide cancelled clutter
  function setBookingFilter(v) { bookingFilter = v; renderRecent(); }

  function renderRecent() {
    const c = document.getElementById('recent-bookings');
    if (!recentBookings.length) { c.innerHTML = '<div class="empty-state">No bookings yet</div>'; return; }
    const states = [...new Set(recentBookings.map(b => b.state))].sort();
    const filtered = recentBookings.filter(b =>
      bookingFilter === 'all' ? true
        : bookingFilter === 'active' ? (b.rawState || b.state) !== 'CANCELLED'
        : b.state === bookingFilter);
    const filterCtl = `<div style="margin-bottom:.6rem;display:flex;align-items:center;gap:.5rem;flex-wrap:wrap">
      <label class="muted" style="font-size:.85em">Filter by state</label>
      <select onchange="setBookingFilter(this.value)" style="padding:.3rem .5rem;border:1px solid #cbd5e1;border-radius:.4rem;font-size:.85em">
        <option value="active"${bookingFilter === 'active' ? ' selected' : ''}>Active (hide cancelled)</option>
        <option value="all"${bookingFilter === 'all' ? ' selected' : ''}>All states (${recentBookings.length})</option>
        ${states.map(s => `<option value="${esc(s)}"${bookingFilter === s ? ' selected' : ''}>${esc(s)}</option>`).join('')}
      </select>
      <span class="muted" style="font-size:.85em">showing ${filtered.length} of ${recentBookings.length}</span>
    </div>`;
    if (!filtered.length) { c.innerHTML = filterCtl + '<div class="empty-state">No bookings in this filter</div>'; return; }
    c.innerHTML = filterCtl + `<table class="data-table">
      <thead><tr><th>Reference</th><th>Type</th><th>State</th><th>Total</th><th>Booked by</th><th></th></tr></thead>
      <tbody>${filtered.map(b => {
        const cancelled = (b.rawState || b.state) === 'CANCELLED';
        // Status shortcut for shipments still awaiting/at pickup (BOOKED / PICKUP_ASSIGNED).
        const pickupBtn = (b.state === 'BOOKED' || b.state === 'PICKUP_ASSIGNED')
          ? `<button class="btn btn-sm btn-ghost" onclick="startPickup('${esc(b.ref)}')">Track</button> `
          : '';
        const action = cancelled
          ? '<span class="muted">cancelled</span>'
          : `${pickupBtn}<button class="btn btn-sm btn-danger" onclick="cancelMyBooking('${esc(b.ref)}','${esc(b.type)}',this)">Cancel</button>`;
        const safeId = b.ref.replace(/[^A-Za-z0-9]/g, '_');
        return `<tr>
        <td><strong><a href="#" onclick="toggleBookingDetail('${esc(b.ref)}','${safeId}');return false"
              style="color:#1d4ed8;text-decoration:none" title="View the DB record">▸ ${esc(b.ref)}</a></strong></td>
        <td><span class="badge badge-blue">${esc(b.type)}</span></td>
        <td>${esc(b.state)}${b.da ? `<br><span class="muted" style="font-size:.85em">🚚 DA ${esc(b.da)}${b.otp ? ' · OTP ' + esc(b.otp) : ''}</span>` : ''}</td>
        <td>${b.total != null ? inr(b.total) : '—'}</td>
        <td class="muted">${esc(b.by)}</td>
        <td>${action}</td>
      </tr>
      <tr class="booking-detail-row" id="bk-detail-${safeId}" style="display:none">
        <td colspan="6" style="background:#f8fafc;padding:0"></td>
      </tr>`; }).join('')}</tbody></table>`;
  }

  // Click a booking reference → fetch + reveal its full DB record (GET /api/v1/shipments/mine/{ref}).
  async function toggleBookingDetail(ref, safeId) {
    const row = document.getElementById('bk-detail-' + safeId);
    if (!row) return;
    const cell = row.firstElementChild;
    if (row.style.display !== 'none') { row.style.display = 'none'; return; }   // collapse on second click
    row.style.display = '';
    cell.innerHTML = '<div class="muted" style="padding:.75rem 1rem">Loading record…</div>';
    try {
      const { status, data } = await orderApi('GET', `/api/v1/shipments/mine/${encodeURIComponent(ref)}`);
      if (status < 200 || status >= 300 || !data) {
        cell.innerHTML = `<div class="response error" style="margin:.5rem 1rem">Could not load ${esc(ref)} (HTTP ${status}).</div>`;
        return;
      }
      cell.innerHTML = renderBookingDetail(data);
    } catch (e) {
      cell.innerHTML = `<div class="response error" style="margin:.5rem 1rem">${esc(String(e))}</div>`;
    }
  }

  function renderBookingDetail(d) {
    const v = x => (x == null || x === '') ? '<span class="muted">—</span>' : esc(String(x));
    const money = x => x == null ? '<span class="muted">—</span>' : inr(x);
    const kg = g => g == null ? '<span class="muted">—</span>' : (g / 1000).toFixed(2) + ' kg';
    const dt = s => s ? new Date(s).toLocaleString('en-IN') : '<span class="muted">—</span>';
    const geo = (la, lo) => (la != null && lo != null) ? `${(+la).toFixed(5)}, ${(+lo).toFixed(5)}` : '<span class="muted">—</span>';
    const cell = (label, val) =>
      `<div style="display:flex;flex-direction:column;gap:.1rem">
         <span class="muted" style="font-size:.68rem;text-transform:uppercase;letter-spacing:.03em">${label}</span>
         <span style="font-size:.85rem">${val}</span>
       </div>`;
    const section = (title, body) =>
      `<div style="margin-bottom:.7rem">
         <div style="font-weight:600;font-size:.76rem;color:#334155;margin-bottom:.35rem;border-bottom:1px solid #e2e8f0;padding-bottom:.18rem">${title}</div>
         <div style="display:grid;grid-template-columns:repeat(auto-fit,minmax(150px,1fr));gap:.45rem .9rem">${body}</div>
       </div>`;
    return `<div style="padding:.85rem 1.1rem;border-left:3px solid #1d4ed8">
      <div style="font-size:.68rem;color:#64748b;margin-bottom:.55rem">📄 shipments record · <code>GET /api/v1/shipments/mine/${esc(d.shipment_ref)}</code></div>
      ${section('Shipment', [
        cell('Reference', `<strong>${v(d.shipment_ref)}</strong>`),
        cell('Customer type', v(d.customer_type)),
        cell('State', `${v(d.state)} <span class="muted">(${v(d.state_label)})</span>`),
        cell('Delivery type', v(d.delivery_type)),
        cell('Pickup / Drop', `${v(d.pickup_type)} / ${v(d.drop_type)}`),
        cell('Payment mode', v(d.payment_mode)),
      ].join(''))}
      ${section('Sender · origin', [
        cell('Name', v(d.sender_name)), cell('Phone', v(d.sender_phone)), cell('Email', v(d.sender_email)),
        cell('Address', v(d.origin_line1)), cell('City / pincode', `${v(d.origin_city)} · ${v(d.origin_pincode)}`),
        cell('Lat, Lon', geo(d.origin_lat, d.origin_lon)), cell('Origin tile', v(d.origin_tile_id)),
      ].join(''))}
      ${section('Receiver · destination', [
        cell('Name', v(d.receiver_name)), cell('Phone', v(d.receiver_phone)), cell('Email', v(d.receiver_email)),
        cell('Address', v(d.dest_line1)), cell('City / pincode', `${v(d.dest_city)} · ${v(d.dest_pincode)}`),
        cell('Lat, Lon', geo(d.dest_lat, d.dest_lon)), cell('Dest tile', v(d.dest_tile_id)),
      ].join(''))}
      ${section('Parcel & pricing', [
        cell('Actual weight', kg(d.weight_grams)), cell('Volumetric', kg(d.volumetric_weight_grams)),
        cell('Chargeable', kg(d.chargeable_weight_grams)), cell('Declared value', money(d.declared_value_paise)),
        cell('Quoted', money(d.quoted_price_paise)), cell('Tax (GST)', money(d.tax_paise)),
        cell('Total', `<strong>${money(d.total_price_paise)}</strong>`),
      ].join(''))}
      ${section('Lifecycle', [
        cell('Created', dt(d.created_at)), cell('Cancelled', dt(d.cancelled_at)),
      ].join(''))}
    </div>`;
  }

  // Customer cancels one of their own (session) bookings. Lane comes from the booking type
  // (B2C/C2C → b2c endpoint, B2B → b2b endpoint).
  async function cancelMyBooking(ref, type, btn) {
    if (!confirm(`Cancel shipment ${ref}? PREPAID payments are refunded; B2B credit is reversed.`)) return;
    const lane = type === 'B2B' ? 'b2b' : 'b2c';
    setLoading(btn, true);
    try {
      const { status, data } = await orderApi('DELETE', `/api/v1/${lane}/shipments/${encodeURIComponent(ref)}`);
      if (status >= 200 && status < 300) {
        const row = recentBookings.find(b => b.ref === ref);
        if (row) { row.state = 'Cancelled'; row.rawState = 'CANCELLED'; }
        renderRecent();
      } else {
        alert('Cancel failed: ' + (data && (data.detail || data.title) || ('HTTP ' + status)));
        setLoading(btn, false);
      }
    } catch (e) {
      alert('Network error cancelling ' + ref);
      setLoading(btn, false);
    }
  }

  // The customer never assigns a DA — M5 does that automatically off the CREATED event. This only POLLS
  // the pickup status and reveals which DA M5 picked + the one-time pickup code (OTP). Safe to click
  // repeatedly: the backend caches the OTP so the code stays stable.
  async function refreshPickupStatus() {
    const ref = val('otp-ref');
    if (!ref) { alert('Enter a shipment ref (book one first, or click "Track" on a booking)'); return; }
    const btn = document.querySelector('[onclick="refreshPickupStatus()"]');
    setLoading(btn, true);
    try {
      const { status, data } = await orderApi('POST', `/api/demo/da/refresh-status?ref=${encodeURIComponent(ref)}`, null);
      if (status !== 200 || !data) { showResponse('otp-response', { body: data }, true); return; }
      const row = recentBookings.find(b => b.ref === ref);
      // DA_ASSIGNED is the only pre-pickup "a DA is coming, here's your OTP" reveal. A PICKED_UP (or
      // later) shipment also returns assigned=true + a cached OTP, so we must key off the stage — not
      // assigned/otp — otherwise a picked-up parcel falls back into the "DA is coming" branch and the
      // row freezes at PICKUP_ASSIGNED.
      if (data.stage === 'DA_ASSIGNED') {
        const lines = [
          `🚚 ${data.stage_label}: DA ${data.da_short} is coming for your ${data.leg}.`,
          `🔑 Your pickup OTP: ${data.otp} — give it to the DA when they arrive.`,
        ];
        if (data.van_short) lines.push(`🚐 Van ${data.van_short}${data.meeting_time ? ' · meets hub cron ' + data.meeting_time : ''}`);
        showResponse('otp-response', lines.join('\n'), false);
        if (row) { row.state = data.stage_label; row.rawState = 'PICKUP_ASSIGNED'; row.da = data.da_short; row.otp = data.otp; row.van = data.van_short; renderRecent(); }
      } else if (data.stage === 'FINDING_DA') {
        showResponse('otp-response', '🔎 ' + (data.message || 'Finding a delivery associate…'), false);
      } else {
        // already past pickup — show the journey stage
        showResponse('otp-response', '📦 ' + (data.stage_label || data.state), false);
        if (row) { row.state = data.stage_label || data.state; row.rawState = data.state; renderRecent(); }
      }
    } catch (e) {
      showResponse('otp-response', { body: { detail: 'Network error — is the backend up?' } }, true);
    } finally { setLoading(btn, false); }
  }

  // Recent-bookings row shortcut: load the ref into the card and check its status in one click.
  async function startPickup(ref) {
    document.getElementById('otp-ref').value = ref;
    document.getElementById('card-pickup-otp').scrollIntoView({ behavior: 'smooth', block: 'center' });
    await refreshPickupStatus();
  }

  async function otpVerify() {
    const ref = val('otp-ref'), otp = val('otp-code');
    if (!ref || !otp) { alert('Enter shipment ref and OTP'); return; }
    const btn = document.querySelector('[onclick="otpVerify()"]');
    setLoading(btn, true);
    try {
      const { status, data } = await orderApi('POST', `/internal/v1/shipments/${encodeURIComponent(ref)}/pickup-otp/verify`, { otp });
      if (status === 204) {
        showResponse('otp-response', '✅ OTP verified — shipment transitioned PICKUP_ASSIGNED → PICKED_UP', false);
        const row = recentBookings.find(b => b.ref === ref);
        if (row) { row.state = 'PICKED_UP'; row.rawState = 'PICKED_UP'; renderRecent(); }
      } else showResponse('otp-response', { body: data }, true);
    } catch (e) {
      showResponse('otp-response', { body: { detail: 'Network error — is the backend up?' } }, true);
    } finally { setLoading(btn, false); }
  }

  async function otpResend() {
    const ref = val('otp-ref');
    if (!ref) { alert('Enter shipment ref'); return; }
    const btn = document.querySelector('[onclick="otpResend()"]');
    setLoading(btn, true);
    try {
      const { status, data } = await orderApi('POST', `/internal/v1/shipments/${encodeURIComponent(ref)}/pickup-otp/resend`, null);
      if (status === 200 && data && data.otp) {
        document.getElementById('otp-code').value = data.otp;
        showResponse('otp-response', '📨 New OTP generated and pre-filled: ' + data.otp, false);
      } else showResponse('otp-response', { body: data }, true);
    } catch (e) {
      showResponse('otp-response', { body: { detail: 'Network error — is the backend up?' } }, true);
    } finally { setLoading(btn, false); }
  }
