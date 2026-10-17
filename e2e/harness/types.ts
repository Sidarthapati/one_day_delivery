/**
 * Wire DTOs (snake_case — the backend uses Jackson SNAKE_CASE). Only the fields the harness reads.
 */
import type { CityCode, Shift } from './constants';

export interface LoginResponse {
  token: string;
  expires_at: string;
  role: string;
  city_id: string | null;
  name: string;
  phone: string | null;
  must_change_password: boolean;
  refresh_token?: string;
}

export interface DaResponse {
  da_id: string;
  name: string;
  email: string;
  phone: string | null;
  city_id: string;
  shift: Shift;
  contract_start_date: string;
  contract_end_date: string | null;
  active: boolean;
  temp_password: string | null;
}

export interface BookingResponse {
  shipment_ref: string;
  order_ref: string | null;
  customer_type: string;
  state: string;
  state_label: string;
  delivery_type: string;
  eta_promised: string | null;
  tracking_url: string;
}

export interface ShipmentTrackResponse {
  shipment_ref: string;
  state: string;
  state_label: string;
  milestones: { label: string; occurred_at: string | null; done: boolean }[];
}

export interface DaTaskView {
  task_id: string;
  shipment_id: string;
  shipment_ref: string | null;
  order_ref: string | null;
  task_type: string; // PICKUP | DELIVERY | CUSTODY_COLLECT | RETURN_TO_HUB ...
  status: string;
  queue_position: number;
  contact_name: string | null;
  contact_phone: string | null;
  address_text: string | null;
  cod_amount_paise: number | null;
}

export interface OtpPeek {
  shipment_ref: string;
  otp: string;
}

export interface ServiceableAtResponse {
  serviceable: boolean;
  city_code: string | null;
  city_id: string | null;
  hex_id: string | null;
  h3_index: string | null;
}

/** A registered persona the harness can drive (DA, shuttle, hub op, etc.). */
export interface Persona {
  userId: string; // == JWT sub; for a DA this is da_id
  email: string;
  password: string;
  role: string;
  city?: CityCode;
  token?: string;
}
