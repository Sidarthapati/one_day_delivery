/**
 * Deterministic test-data factories: parcels + coordinates spread across a city's serviceable box
 * (so pickups distribute across DAs' hexes — scenario C3). Seeded RNG keeps runs reproducible.
 */
import { SERVICEABLE_BOX, type CityCode } from './constants';

/** mulberry32 — tiny deterministic RNG. */
export function rng(seed: number): () => number {
  let a = seed >>> 0;
  return () => {
    a |= 0;
    a = (a + 0x6d2b79f5) | 0;
    let t = Math.imul(a ^ (a >>> 15), 1 | a);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

export interface Coord {
  latitude: number;
  longitude: number;
}

/** Spread `total` pins over a grid inside the box, jittered, so they land on distinct hexes. */
export function spreadCoords(city: CityCode, total: number, seed = 42): Coord[] {
  const box = SERVICEABLE_BOX[city];
  const rand = rng(seed + city.charCodeAt(0));
  const cols = Math.ceil(Math.sqrt(total));
  const rows = Math.ceil(total / cols);
  const out: Coord[] = [];
  for (let i = 0; i < total; i++) {
    const cx = (i % cols) + 0.5;
    const cy = Math.floor(i / cols) + 0.5;
    const lon = box.lonMin + ((box.lonMax - box.lonMin) * (cx + (rand() - 0.5) * 0.6)) / cols;
    const lat = box.latMin + ((box.latMax - box.latMin) * (cy + (rand() - 0.5) * 0.6)) / rows;
    out.push({
      latitude: round6(clamp(lat, box.latMin, box.latMax)),
      longitude: round6(clamp(lon, box.lonMin, box.lonMax)),
    });
  }
  return out;
}

export interface ParcelOptions {
  b2bAccountId: string;
  originCity: CityCode;
  destCity: CityCode;
  origin: Coord;
  dest: Coord;
  index: number;
  cod?: number; // paise
  pickupType?: 'DA_PICKUP' | 'SELF_DROP';
  dropType?: 'DA_DELIVERY' | 'HUB_COLLECT';
  fundingSource?: 'CREDIT' | 'WALLET';
  purchaseOrderRef?: string;
}

const PINCODE: Record<CityCode, string> = { DEL: '110001', HYD: '500001' };
const STATE: Record<CityCode, string> = { DEL: 'Delhi', HYD: 'Telangana' };

/** Build a B2bBookingRequest body (snake_case) for a single parcel. */
export function buildBooking(o: ParcelOptions): Record<string, unknown> {
  const n = String(o.index).padStart(4, '0');
  return {
    b2b_account_id: o.b2bAccountId,
    purchase_order_ref: o.purchaseOrderRef ?? `E2E-${o.originCity}-${n}`,
    sender_name: `Sender ${n}`,
    sender_phone: '+919000000001',
    origin_address: {
      line1: `Pickup ${n}, ${o.originCity}`,
      city: o.originCity,
      pincode: PINCODE[o.originCity],
      state: STATE[o.originCity],
      latitude: o.origin.latitude,
      longitude: o.origin.longitude,
    },
    origin_city: o.originCity,
    origin_pincode: PINCODE[o.originCity],
    receiver_name: `Receiver ${n}`,
    receiver_phone: '+919000000002',
    dest_address: {
      line1: `Drop ${n}, ${o.destCity}`,
      city: o.destCity,
      pincode: PINCODE[o.destCity],
      state: STATE[o.destCity],
      latitude: o.dest.latitude,
      longitude: o.dest.longitude,
    },
    dest_city: o.destCity,
    dest_pincode: PINCODE[o.destCity],
    weight_grams: 1000,
    length_cm: 20,
    width_cm: 15,
    height_cm: 10,
    declared_value_paise: 50000,
    cod_amount_to_collect_paise: o.cod ?? null,
    pickup_type: o.pickupType ?? 'DA_PICKUP',
    drop_type: o.dropType ?? 'DA_DELIVERY',
    funding_source: o.fundingSource ?? 'CREDIT',
  };
}

function clamp(v: number, lo: number, hi: number): number {
  return Math.max(lo, Math.min(hi, v));
}
function round6(v: number): number {
  return Math.round(v * 1e6) / 1e6;
}
