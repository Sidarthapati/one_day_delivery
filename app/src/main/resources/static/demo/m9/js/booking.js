// booking.js — Step 1: create a real B2C booking so the rest of the flow (hub → M9 → tracking)
// has a genuine shipment to move through the system.
//
// The backend's Jackson config is SNAKE_CASE in both directions — request bodies must use
// snake_case keys (not just responses), confirmed live: a camelCase body silently binds nothing
// and every field comes back "must not be blank".
//
// A freshly booked shipment lands in BOOKED and stays there — nothing in the product actually
// drives a self-drop shipment to AWAITING_SELF_DROP (unlike DA_PICKUP, which M5 automates, but
// which needs a real serviceable DA and none exist in this DB). Since pickup mechanics aren't what
// this demo is testing, confirm-self-drop is a small stand-in trigger for exactly that one edge —
// see B2cShipmentController.confirmSelfDrop.

document.addEventListener('DOMContentLoaded', () => {
  if (!requireSession()) return;
  paintNav();
  fillCitySelect(document.getElementById('origin-city'));
  fillCitySelect(document.getElementById('dest-city'));
  document.getElementById('dest-city').value = 'BOM';   // default a real DEL→BOM lane
  document.getElementById('book-btn').addEventListener('click', createBooking);
  document.getElementById('copy-ref-btn').addEventListener('click', () => {
    navigator.clipboard.writeText(document.getElementById('ref-value').textContent);
  });

  // Carry a shipmentRef forward automatically if we already booked one this session.
  const existingRef = sessionStorage.getItem('m9demo_shipmentRef');
  if (existingRef) showRef(existingRef);
});

async function createBooking() {
  const btn = document.getElementById('book-btn');
  const originCode = val('origin-city');
  const destCode = val('dest-city');

  const request = {
    sender_name: val('sender-name'),
    sender_phone: val('sender-phone'),
    origin_address: {
      line1: val('origin-line1'),
      city: cityByCode(originCode).name,
      pincode: val('origin-pincode'),
      state: val('origin-state'),
    },
    origin_city: originCode,
    origin_pincode: val('origin-pincode'),
    receiver_name: val('receiver-name'),
    receiver_phone: val('receiver-phone'),
    dest_address: {
      line1: val('dest-line1'),
      city: cityByCode(destCode).name,
      pincode: val('dest-pincode'),
      state: val('dest-state'),
    },
    dest_city: destCode,
    dest_pincode: val('dest-pincode'),
    weight_grams: Number(val('weight-grams')),
    length_cm: Number(val('length-cm')),
    width_cm: Number(val('width-cm')),
    height_cm: Number(val('height-cm')),
    pickup_type: 'SELF_DROP',
    drop_type: 'HUB_COLLECT',
    payment_mode: 'PREPAID',
  };

  setLoading(btn, true);
  try {
    // COD is no longer accepted (B2B portal work disabled it) — PREPAID needs a captured payment
    // before the booking call succeeds: mint a gateway order, "pay" it via the mock gateway
    // (test-mode only, MockPaymentController), then hand the resulting payment id + signature to
    // the booking call, exactly like a real Razorpay checkout would.
    const order = await api('POST', '/api/v1/payments/order', request, { 'Idempotency-Key': uuid() });
    const payment = await api('POST', '/api/v1/payments/mock/pay', { order_id: order.order_id },
      { 'Idempotency-Key': uuid() });
    request.razorpay_order_id = payment.razorpay_order_id;
    request.razorpay_payment_id = payment.razorpay_payment_id;
    request.razorpay_signature = payment.razorpay_signature;

    const data = await api('POST', '/api/v1/b2c/shipments', request, { 'Idempotency-Key': uuid() });
    const ref = data.shipment_ref;
    sessionStorage.setItem('m9demo_shipmentRef', ref);
    sessionStorage.setItem('m9demo_originHub', originCode);
    sessionStorage.setItem('m9demo_destHub', destCode);

    // Bridge the missing self-drop trigger so the shipment is actually hub-receivable next.
    // Every POST under /api/v1/** needs an Idempotency-Key (IdempotencyFilter), same as booking.
    await api('POST', `/api/v1/b2c/shipments/${ref}/confirm-self-drop`, null, { 'Idempotency-Key': uuid() });

    showResponse('book-response', data, false);
    showRef(ref);
  } catch (e) {
    showResponse('book-response', fmtError(e), true);
  } finally {
    setLoading(btn, false);
  }
}

function showRef(ref) {
  document.getElementById('ref-value').textContent = ref;
  document.getElementById('ref-card').style.display = '';
}
