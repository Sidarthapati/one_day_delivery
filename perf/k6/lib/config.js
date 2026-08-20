// Shared config for k6 scripts. Everything is env-driven; defaults point at staging.
// Usage: BASE_URL=... k6 run perf/k6/load.js
export const BASE_URL = __ENV.BASE_URL || 'https://one-day-delivery.onrender.com';

// Serviceable city coordinates (grid-seeded cities) — used to build realistic quote/booking bodies.
export const CITIES = {
  DEL: { lat: 28.6139, lon: 77.209, pincode: '110001' },
  BOM: { lat: 19.076, lon: 72.8777, pincode: '400001' },
  BLR: { lat: 12.9716, lon: 77.5946, pincode: '560001' },
  HYD: { lat: 17.385, lon: 78.4867, pincode: '500001' },
  MAA: { lat: 13.0827, lon: 80.2707, pincode: '600001' },
};

// A quote request body for the open pricing endpoint (no auth needed).
export function quoteBody(origin = CITIES.DEL, dest = CITIES.BOM) {
  return JSON.stringify({
    originPincode: origin.pincode,
    destinationPincode: dest.pincode,
    originLat: origin.lat,
    originLon: origin.lon,
    destinationLat: dest.lat,
    destinationLon: dest.lon,
    weightGrams: 1500,
    lengthCm: 20,
    breadthCm: 15,
    heightCm: 10,
    paymentMode: 'PREPAID',
  });
}

export const JSON_HEADERS = { headers: { 'Content-Type': 'application/json' } };
