// cart.js — M4 saved address book, shipment cart (add/checkout), and Excel bulk upload.
// Reuses globals from core.js (orderApi, val, esc, inr, currentUser, token, showResponse,
// setLoading) and orders.js (picked, legAddress, ensureLocations, ORDER_CITIES, openMapPicker).

  const CUSTOMER_ROLES = ['C2C_CUSTOMER', 'B2C_CUSTOMER', 'B2B_USER'];

  // Called from auth.js on login to wire the Cart tab + bulk sections for customer roles.
  function setupCartExperience(role) {
    const isCustomer = CUSTOMER_ROLES.includes(role);
    document.getElementById('cart-tab-btn').style.display = isCustomer ? '' : 'none';
    document.getElementById('cart-b2b-acct-field').style.display = role === 'B2B_USER' ? '' : 'none';
    document.getElementById('cart-response').className = 'response';
    // Bulk (one pickup → many destinations) is for business senders only — never C2C.
    const b2cBulk = document.getElementById('b2c-bulk');
    if (b2cBulk) b2cBulk.style.display = role === 'B2C_CUSTOMER' ? '' : 'none';
    const b2bBulk = document.getElementById('b2b-bulk');
    if (b2bBulk) b2bBulk.style.display = role === 'B2B_USER' ? '' : 'none';
    if (isCustomer) { loadCart(); }
  }

  // ════════════════════ SAVED ADDRESS BOOK ════════════════════
  let _addrPick = null;     // confirmed map pin for the address form
  let _addrLabel = 'HOME';
  let _addrMode = 'save';   // 'save' (Add Address tab) | 'booking' (Set on map → details, optional save)
  let _bookingTarget = null;// { form, leg } when _addrMode === 'booking'

  async function loadAddresses() {
    const c = document.getElementById('addresses-list');
    if (!c) return;   // the dedicated address card was removed; saving still works via booking
    try {
      const { status, data } = await orderApi('GET', '/api/v1/addresses');
      if (status >= 200 && status < 300 && Array.isArray(data)) renderAddresses(data);
      else c.innerHTML = '<div class="empty-state">Could not load addresses</div>';
    } catch { c.innerHTML = '<div class="empty-state">Could not load addresses</div>'; }
  }

  const LABEL_ICON = { HOME: '🏠', OFFICE: '💼', OTHER: '📍' };

  function renderAddresses(list) {
    const c = document.getElementById('addresses-list');
    if (!list.length) { c.innerHTML = '<div class="empty-state">No saved addresses yet</div>'; return; }
    c.innerHTML = list.map(a => {
      const title = (a.save_as && a.save_as.trim()) ? a.save_as : (LABEL_ICON[a.label] || '') + ' ' + a.label;
      const line = [a.house_floor, a.building_street, a.area_locality || a.line1].filter(Boolean).join(', ');
      return `<div class="loc-card set" style="margin-bottom:.5rem">
        <div class="loc-card-head">
          <span class="loc-kind">${LABEL_ICON[a.label] || '📍'} ${esc(title)} <span class="badge badge-blue">${esc(a.label)}</span></span>
          <button class="btn btn-sm btn-danger" onclick="deleteAddress('${a.id}')">Delete</button>
        </div>
        <div class="loc-card-body">
          <div class="loc-addr">${esc(line)}</div>
          <div class="loc-city">${esc(a.city)} · ${esc(a.pincode)}</div>
          ${a.contact_name ? `<div class="loc-coords">${esc(a.contact_name)} · ${esc(a.contact_phone || '')}</div>` : ''}
        </div>
      </div>`;
    }).join('');
  }

  function resetAddrFields() {
    ['addr-house', 'addr-building', 'addr-saveas', 'addr-cname', 'addr-cphone', 'addr-instr'].forEach(id => document.getElementById(id).value = '');
    document.querySelectorAll('#addr-label-seg button').forEach((b, i) => b.classList.toggle('active', i === 0));
    _addrLabel = 'HOME';
    document.getElementById('addr-loc-body').innerHTML = '<div class="loc-empty">No location yet — tap “Set on map”.</div>';
    document.getElementById('addr-loc-card').classList.remove('set');
    document.getElementById('addr-save-btn').disabled = true;
    document.getElementById('addr-form-status').className = 'map-status';
  }

  // "Add Address" tab → pure save mode (always saves to the book).
  function openAddressForm() {
    _addrMode = 'save'; _bookingTarget = null; _addrPick = null;
    resetAddrFields();
    document.getElementById('addr-modal-title').textContent = 'Add address';
    document.getElementById('addr-save-toggle-row').style.display = 'none';
    document.getElementById('addr-save-fields').style.display = '';
    document.getElementById('addr-save-btn').textContent = 'Save Address';
    document.getElementById('addr-form-status').textContent = 'Pick a serviceable location, choose a label, then save.';
    document.getElementById('addr-modal').style.display = 'flex';
  }
  function closeAddressForm() { document.getElementById('addr-modal').style.display = 'none'; }

  function selectAddrLabel(btn) {
    document.querySelectorAll('#addr-label-seg button').forEach(b => b.classList.remove('active'));
    btn.classList.add('active');
    _addrLabel = btn.dataset.label;
  }

  // Booking mode: label + save-as only matter if the user ticks "save this address".
  function toggleSaveFields() {
    document.getElementById('addr-save-fields').style.display =
      document.getElementById('addr-save-toggle').checked ? '' : 'none';
  }

  // Booking "Set on map": pick on the map, THEN capture flat/building details (with optional save).
  function setOnMap(form, leg) {
    openMapPicker(form, leg, {
      title: (leg === 'origin' ? '📦 Pickup' : '🎯 Drop') + ' location',
      onConfirm: (pick) => openBookingDetails(form, leg, pick),
    });
  }

  function openBookingDetails(form, leg, pick) {
    _addrMode = 'booking'; _bookingTarget = { form, leg };
    resetAddrFields();
    fillAddrLocCard(pick);
    if (pick.line1) document.getElementById('addr-building').value = pick.line1;
    document.getElementById('addr-modal-title').textContent = (leg === 'origin' ? 'Pickup' : 'Drop') + ' address details';
    document.getElementById('addr-save-toggle').checked = false;
    document.getElementById('addr-save-toggle-row').style.display = '';
    document.getElementById('addr-save-fields').style.display = 'none';
    document.getElementById('addr-save-btn').textContent = 'Use this location';
    document.getElementById('addr-save-btn').disabled = false;     // location already chosen on the map
    document.getElementById('addr-form-status').textContent = 'Add flat / building details (optional), then use this location.';
    document.getElementById('addr-modal').style.display = 'flex';
  }

  function fillAddrLocCard(pick) {
    _addrPick = pick;
    const c = ORDER_CITIES[pick.city];
    document.getElementById('addr-loc-card').classList.add('set');
    document.getElementById('addr-loc-body').innerHTML =
      `<div class="loc-city">${c.name} · ${pick.pincode}</div>` +
      `<div class="loc-coords">${pick.lat}, ${pick.lon}</div>` +
      (pick.label ? `<div class="loc-addr">${esc(pick.label)}</div>` : '');
    document.getElementById('addr-save-btn').disabled = false;
  }

  // The Location card's "Set on map" inside the modal (re-pick) — used in both modes.
  function pickAddressOnMap() {
    window._reopenAddrForm = true;     // hide the form while the full-screen map is open
    document.getElementById('addr-modal').style.display = 'none';
    openMapPicker(null, null, { title: '📍 Address location', onConfirm: (pick) => fillAddrLocCard(pick) });
  }

  function addressBodyFromForm() {
    const c = ORDER_CITIES[_addrPick.city];
    return {
      label: _addrLabel,
      save_as: val('addr-saveas') || null,
      contact_name: val('addr-cname') || null,
      contact_phone: val('addr-cphone') || null,
      house_floor: val('addr-house') || null,
      building_street: val('addr-building') || null,
      area_locality: _addrPick.label || null,
      line1: _addrPick.line1 || val('addr-building') || c.addr,
      city: c.name, pincode: _addrPick.pincode, state: c.state,
      latitude: _addrPick.lat, longitude: _addrPick.lon,
      delivery_instructions: val('addr-instr') || null,
    };
  }

  // Primary modal action — saves (Add Address tab) or applies the location to the booking leg
  // (Set on map), optionally saving it to the book along the way.
  async function confirmAddress() {
    if (!_addrPick) return;
    const btn = document.getElementById('addr-save-btn');

    if (_addrMode === 'save') {
      setLoading(btn, true);
      try {
        const { status, data } = await orderApi('POST', '/api/v1/addresses', addressBodyFromForm());
        if (status >= 200 && status < 300) { closeAddressForm(); loadAddresses(); }
        else addrFormError((data && (data.detail || data.title)) || ('HTTP ' + status));
      } finally { setLoading(btn, false); }
      return;
    }

    // Booking mode: fold the flat/building details into the pin, optionally save, then apply.
    const { form, leg } = _bookingTarget;
    const pick = Object.assign({}, _addrPick, {
      houseFloor: val('addr-house') || null,
      buildingStreet: val('addr-building') || _addrPick.line1 || null,
      areaLocality: _addrPick.label || null,
    });
    const detail = [pick.houseFloor, pick.buildingStreet].filter(Boolean).join(', ');
    if (detail) pick.label = detail + (pick.label ? ' · ' + pick.label : '');

    if (document.getElementById('addr-save-toggle').checked) {
      try { await orderApi('POST', '/api/v1/addresses', addressBodyFromForm()); loadAddresses(); }
      catch { /* saving is best-effort — never block the booking on it */ }
    }
    picked[form][leg] = pick;
    renderLocCard(form, leg, pick);
    closeAddressForm();
  }

  function addrFormError(msg) {
    const s = document.getElementById('addr-form-status');
    s.className = 'map-status err';
    s.textContent = msg;
  }

  async function deleteAddress(id) {
    if (!confirm('Delete this saved address?')) return;
    await orderApi('DELETE', '/api/v1/addresses/' + encodeURIComponent(id));
    loadAddresses();
  }

  // ── Use a saved address as a booking pickup/drop ────────────────────────────
  let _savedAddresses = [];
  let _savedPickTarget = null;     // { form, leg } on the booking form awaiting a saved address

  async function openSavedPicker(form, leg) {
    _savedPickTarget = { form, leg };
    const c = document.getElementById('saved-pick-list');
    c.innerHTML = '<div class="empty-state">Loading…</div>';
    document.getElementById('saved-pick-modal').style.display = 'flex';
    let list = [];
    try {
      const { status, data } = await orderApi('GET', '/api/v1/addresses');
      if (status >= 200 && status < 300 && Array.isArray(data)) list = data;
    } catch { /* show empty below */ }
    _savedAddresses = list;
    if (!list.length) {
      c.innerHTML = '<div class="empty-state">No saved addresses yet — save one from the Book Shipment flow (“Set on map” → tick “Save this address”).</div>';
      return;
    }
    c.innerHTML = list.map((a, i) => {
      const title = (a.save_as && a.save_as.trim()) ? a.save_as : (LABEL_ICON[a.label] || '') + ' ' + a.label;
      const line = [a.house_floor, a.building_street, a.area_locality || a.line1].filter(Boolean).join(', ');
      return `<div class="loc-card" style="margin-bottom:.5rem;cursor:pointer" onclick="applySavedAddress(${i})">
        <div class="loc-card-head">
          <span class="loc-kind">${LABEL_ICON[a.label] || '📍'} ${esc(title)} <span class="badge badge-blue">${esc(a.label)}</span></span>
          <button class="btn btn-sm btn-danger" onclick="deleteSavedFromPicker('${a.id}', event)">Delete</button>
        </div>
        <div class="loc-card-body"><div class="loc-addr">${esc(line)}</div><div class="loc-city">${esc(a.city)} · ${esc(a.pincode)}</div></div>
      </div>`;
    }).join('');
  }
  function closeSavedPicker() { document.getElementById('saved-pick-modal').style.display = 'none'; }

  // Delete from inside the picker without selecting the row (stopPropagation), then refresh the list.
  async function deleteSavedFromPicker(id, ev) {
    if (ev) ev.stopPropagation();
    if (!confirm('Delete this saved address?')) return;
    await orderApi('DELETE', '/api/v1/addresses/' + encodeURIComponent(id));
    if (_savedPickTarget) openSavedPicker(_savedPickTarget.form, _savedPickTarget.leg);
  }

  // Re-validate the saved address against M3 (it may have gone out of grid) and, if serviceable,
  // fold it into the booking form's picked[form][leg] exactly like a fresh map pin.
  async function applySavedAddress(i) {
    const a = _savedAddresses[i];
    if (!a || !_savedPickTarget) return;
    const { form, leg } = _savedPickTarget;
    closeSavedPicker();
    if (a.latitude == null || a.longitude == null) {
      showResponse(form + '-booking-response', { body: { detail: 'That saved address has no map location — edit it to set one.' } }, true);
      return;
    }
    const m3 = await m3ServiceableAt(a.latitude, a.longitude);
    const serviceable = !!(m3 && m3.serviceable);
    const demo = serviceable ? GRID_TO_DEMO[m3.city_code] : null;
    if (!serviceable || !demo) {
      showResponse(form + '-booking-response',
        { body: { detail: `Saved address “${a.save_as || a.label}” is outside the serviceable grid.` } }, true);
      return;
    }
    const pincode = (a.pincode && /^\d{6}$/.test(a.pincode)) ? a.pincode : ORDER_CITIES[demo].pin;
    const pick = {
      lat: +(+a.latitude).toFixed(6), lon: +(+a.longitude).toFixed(6),
      pincode, city: demo,
      line1: a.building_street || a.line1 || '',
      label: [a.house_floor, a.building_street, a.area_locality].filter(Boolean).join(', '),
      hex: m3.h3_index,
    };
    picked[form][leg] = pick;
    renderLocCard(form, leg, pick);
  }

  // ════════════════════ CART ════════════════════
  function buildCartItemBody(form) {
    const o = picked[form].origin, d = picked[form].dest;
    const p = 'o-' + form;
    return {
      sender_name: val(p + '-sname'), sender_phone: val(p + '-sphone'),
      origin_address: legAddress(o), origin_city: o.city, origin_pincode: o.pincode,
      receiver_name: val(p + '-rname'), receiver_phone: val(p + '-rphone'),
      dest_address: legAddress(d), dest_city: d.city, dest_pincode: d.pincode,
      weight_grams: +val(p + '-weight') || 1000,
      length_cm: +val(p + '-len') || 20, width_cm: +val(p + '-wid') || 15, height_cm: +val(p + '-hei') || 10,
      declared_value_paise: (+val(p + '-dval') || 0) * 100 || null,
      pickup_type: 'DA_PICKUP', drop_type: 'DA_DELIVERY',
    };
  }

  async function addCurrentToCart(form) {
    if (!ensureLocations(form)) return;
    const respId = form + '-booking-response';
    try {
      const { status, data } = await orderApi('POST', '/api/v1/cart/items', buildCartItemBody(form));
      if (status >= 200 && status < 300) {
        showResponse(respId, '🛒 Added to cart — open the “Cart & Bulk” tab to review and check out.', false);
        loadCart();
      } else {
        showResponse(respId, { body: data }, true);
      }
    } catch {
      showResponse(respId, { body: { detail: 'Network error adding to cart.' } }, true);
    }
  }

  async function loadCart() {
    const c = document.getElementById('cart-list');
    try {
      const { status, data } = await orderApi('GET', '/api/v1/cart');
      if (status >= 200 && status < 300) renderCart(data);
      else c.innerHTML = '<div class="empty-state">Could not load cart</div>';
    } catch { c.innerHTML = '<div class="empty-state">Could not load cart</div>'; }
  }

  function renderCart(cart) {
    const c = document.getElementById('cart-list');
    const items = (cart && cart.items) || [];
    document.getElementById('cart-summary').textContent =
      items.length ? `${items.length} item(s) · total ${inr(cart.cart_total_paise)}` : '—';
    document.getElementById('cart-checkout-row').style.display = items.length ? 'flex' : 'none';
    if (!items.length) { c.innerHTML = '<div class="empty-state">Cart is empty</div>'; return; }
    c.innerHTML = `<table class="data-table">
      <thead><tr><th>Route</th><th>Receiver</th><th>Weight</th><th>Price</th><th></th></tr></thead>
      <tbody>${items.map(i => `<tr>
        <td><strong>${esc(i.origin_city)}</strong> → <strong>${esc(i.dest_city)}</strong>
            <div class="muted" style="font-size:.75rem">${esc(i.origin_pincode)} → ${esc(i.dest_pincode)}${i.source === 'EXCEL' ? ' · 📑 excel' : ''}</div></td>
        <td>${esc(i.receiver_name)}<div class="muted" style="font-size:.75rem">${esc(i.receiver_phone)}</div></td>
        <td>${i.weight_grams} g</td>
        <td>${inr(i.quoted_total_paise)}</td>
        <td><button class="btn btn-sm btn-danger" onclick="removeCartItem('${i.id}')">Remove</button></td>
      </tr>`).join('')}</tbody></table>`;
  }

  async function removeCartItem(id) {
    await orderApi('DELETE', '/api/v1/cart/items/' + encodeURIComponent(id));
    loadCart();
  }

  async function checkoutCart() {
    const btn = document.querySelector('[onclick="checkoutCart()"]');
    setLoading(btn, true);
    try {
      if (currentUser.role === 'B2B_USER') {
        const { status, data } = await orderApi('POST', '/api/v1/cart/checkout', { b2b_account_id: val('cart-b2b-acct') });
        handleCheckoutResult(status, data);
      } else {
        const { status, data } = await orderApi('POST', '/api/v1/cart/payment-order', {});
        if (status >= 400) { showResponse('cart-response', { body: data }, true); return; }
        await payCartThenCheckout(data);   // { order_id, amount_paise, key_id, mock }
      }
    } catch {
      showResponse('cart-response', { body: { detail: 'Network error during checkout.' } }, true);
    } finally { setLoading(btn, false); }
  }

  async function payCartThenCheckout(order) {
    const refsThenCheckout = async (refs) => {
      const { status, data } = await orderApi('POST', '/api/v1/cart/checkout', refs);
      handleCheckoutResult(status, data);
    };
    // Live/test-API mode → Razorpay hosted checkout.
    if (!order.mock && window.Razorpay) {
      const rzp = new Razorpay({
        key: order.key_id, amount: order.amount_paise, currency: order.currency || 'INR',
        order_id: order.order_id, name: '1DD Logistics', description: 'Cart checkout',
        theme: { color: '#1a1a2e' },
        handler: (res) => refsThenCheckout({
          razorpay_order_id: res.razorpay_order_id,
          razorpay_payment_id: res.razorpay_payment_id,
          razorpay_signature: res.razorpay_signature,
        }),
        modal: { ondismiss: () => showResponse('cart-response', { body: { detail: 'Payment cancelled — cart not checked out.' } }, true) },
      });
      rzp.open();
      return;
    }
    // Mock gateway → sign locally, then check out.
    const { status, data } = await orderApi('POST', '/api/v1/payments/mock/pay', { order_id: order.order_id });
    if (status >= 400) { showResponse('cart-response', { body: data }, true); return; }
    await refsThenCheckout({
      razorpay_order_id: data.razorpay_order_id,
      razorpay_payment_id: data.razorpay_payment_id,
      razorpay_signature: data.razorpay_signature,
    });
  }

  function handleCheckoutResult(status, data) {
    if (status >= 200 && status < 300 && data) {
      const msg = `✅ Checkout: ${data.booked} booked, ${data.failed} failed · charged ${inr(data.charged_total_paise)} · cart ${data.cart_status}`;
      showResponse('cart-response', data.failed ? { body: { title: 'Partial checkout', detail: msg } } : msg, data.failed > 0);
      loadCart();
      if (typeof loadMyBookings === 'function') loadMyBookings();
    } else {
      showResponse('cart-response', { body: data }, true);
    }
  }

  // ════════════════════ BULK UPLOAD (one pickup → many destinations) ════════════════════
  async function downloadTemplate() {
    try {
      const r = await fetch('/api/v1/bulk/template', { headers: { 'Authorization': 'Bearer ' + token } });
      if (!r.ok) { alert('Could not download template (HTTP ' + r.status + ')'); return; }
      const blob = await r.blob();
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url; a.download = 'oneday-destinations-template.xlsx';
      document.body.appendChild(a); a.click(); a.remove();
      URL.revokeObjectURL(url);
    } catch { alert('Network error downloading template.'); }
  }

  // form = 'b2c' | 'b2b'. The single shared pickup comes from that form's pickup pin + sender.
  async function uploadBulk(input, form) {
    const file = input.files && input.files[0];
    if (!file) return;
    const respId = form + '-bulk-response';
    const o = picked[form] && picked[form].origin;
    if (!o) {
      input.value = '';
      showResponse(respId, { body: { detail: 'Set the Pickup location on the map first — it applies to every destination.' } }, true);
      return;
    }
    const p = 'o-' + form;
    const pickup = {
      sender_name: val(p + '-sname'), sender_phone: val(p + '-sphone'),
      origin_address: legAddress(o), origin_city: o.city, origin_pincode: o.pincode,
    };
    const fd = new FormData();
    fd.append('file', file);
    fd.append('pickup', JSON.stringify(pickup));
    const btn = document.getElementById(form + '-bulk-btn');
    if (btn) setLoading(btn, true);
    showResponse(respId, `⏳ Uploading “${file.name}” — validating destinations and pricing each route…`, false);
    const t0 = performance.now();
    try {
      const r = await fetch('/api/v1/bulk/upload', { method: 'POST', headers: { 'Authorization': 'Bearer ' + token }, body: fd });
      const data = await r.json().catch(() => null);
      input.value = '';
      if (!r.ok) { showResponse(respId, { body: data }, true); return; }
      showBulkResult(data, respId, performance.now() - t0);
    } catch {
      input.value = '';
      showResponse(respId, { body: { detail: 'Network error uploading file.' } }, true);
    } finally {
      if (btn) setLoading(btn, false);
    }
  }

  function showBulkResult(data, respId, elapsedMs) {
    const secs = elapsedMs != null ? ` in ${(elapsedMs / 1000).toFixed(1)}s` : '';
    showResponse(respId,
      `📑 ${data.added} destination(s) added to cart, ${data.failed} failed${secs}. Open the 🛒 Cart tab to review and check out.`,
      data.failed > 0);
    loadCart();
    if (data.failed > 0) {
      document.getElementById('bulk-modal-summary').innerHTML =
        `<strong>${data.added}</strong> added · <strong style="color:#dc2626">${data.failed}</strong> failed validation`;
      document.getElementById('bulk-modal-failures').innerHTML = (data.failures || []).map(f =>
        `<div class="loc-card" style="margin-bottom:.5rem">
          <div class="loc-card-head"><span class="loc-kind">Row ${f.row}</span></div>
          <div class="loc-card-body">${(f.errors || []).map(e =>
            `<div class="loc-addr"><code>${esc(e.field)}</code> — ${esc(e.reason)}</div>`).join('')}</div>
        </div>`).join('');
      document.getElementById('bulk-modal').style.display = 'flex';
    }
  }
  function closeBulkModal() { document.getElementById('bulk-modal').style.display = 'none'; }
