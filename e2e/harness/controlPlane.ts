/**
 * Control plane for the full-day E2E: the screenless setup / gating / time-travel operations that
 * stand in for a human, the clock, or the airline. Business actions (book, pickup, scan, seal,
 * collect, deliver, OTP) are driven by the UI (Playwright/Maestro), NOT here — except the `actions`
 * block, which drives the API-only *volume/background* parcels (see docs/e2e/FULL-DAY-E2E.md).
 */
import { ApiClient } from './apiClient';
import {
  ADMIN, CITY_SLUG, CITY_UUID, DEMO_B2B_RATE_CARD, HUB_GEOFENCE, type CityCode, type Shift,
} from './constants';
import {
  bumpCreditLimit, findUserIdByEmail, hasApprovedAssignments, setB2bRateCardAndCredit, timeTravelFlight,
} from './db';
import { jwtSub } from './jwt';
import { pollUntil, sleep } from './wait';
import type {
  BookingResponse,
  DaResponse,
  DaTaskView,
  LoginResponse,
  OtpPeek,
  Persona,
  ShipmentTrackResponse,
} from './types';

export class ControlPlane {
  readonly api: ApiClient;
  private adminToken?: string;

  constructor(api = new ApiClient()) {
    this.api = api;
  }

  // ---- auth --------------------------------------------------------------
  async login(email: string, password: string): Promise<LoginResponse> {
    return this.api.post<LoginResponse>('/auth/login', { email, password });
  }

  async admin(): Promise<string> {
    if (!this.adminToken) this.adminToken = (await this.login(ADMIN.email, ADMIN.password)).token;
    return this.adminToken;
  }

  private async asAdmin<T>(fn: (token: string) => Promise<T>): Promise<T> {
    return fn(await this.admin());
  }

  /** ADMIN user id (JWT sub) — used e.g. as the grid-proposal reviewer_id. */
  async adminUserId(): Promise<string> {
    return jwtSub(await this.admin());
  }

  // ---- fleet / meeting mode ---------------------------------------------
  async setHubReturn(city: CityCode, intervalMinutes = 120): Promise<void> {
    await this.asAdmin((token) =>
      this.api.put(`/routing/fleet/${CITY_UUID[city]}`, {
        meeting_mode: 'HUB_RETURN',
        hub_return_interval_minutes: intervalMinutes,
      }, { token }),
    );
  }

  // ---- persona seeding ---------------------------------------------------
  async registerDa(input: {
    firstName: string;
    lastName: string;
    email: string;
    phone?: string;
    city: CityCode;
    shift: Shift;
    contractStart: string; // YYYY-MM-DD
    password?: string;
  }): Promise<Persona> {
    // Idempotent: a re-run against an already-seeded DB reuses the existing DA (POST /das would 409).
    const existingId = await findUserIdByEmail(input.email);
    if (existingId) {
      return {
        userId: existingId,
        email: input.email,
        password: input.password ?? '',
        role: 'DELIVERY_ASSOCIATE',
        city: input.city,
      };
    }
    const da = await this.asAdmin((token) =>
      this.api.post<DaResponse>('/das', {
        first_name: input.firstName,
        last_name: input.lastName,
        email: input.email,
        phone: input.phone,
        // DA roster query (auth availableDaIds) + dispatch shift-load match on users.city_id against
        // the grid.cities KEY form ("delhi"/"hyderabad"), NOT the IATA code. Registering a DA with
        // "DEL" leaves it invisible to the roster → shift-load loads nobody → check-in 404. (Shuttle
        // agents are the exception — HubCode.of wants the IATA "DEL"/"HYD"; see registerUser callers.)
        city_id: CITY_SLUG[input.city],
        shift: input.shift,
        contract_start_date: input.contractStart,
        password: input.password,
      }, { token }),
    );
    return {
      userId: da.da_id,
      email: input.email,
      password: input.password ?? da.temp_password!,
      role: 'DELIVERY_ASSOCIATE',
      city: input.city,
    };
  }

  /** Register HUB_OPERATOR / STATION_MANAGER / AIRLINE_GHA / SHUTTLE_AGENT / B2B_USER. */
  async registerUser(input: {
    name: string;
    email: string;
    password: string;
    role: string;
    cityId: string; // IATA code for shuttle (DEL/HYD), free string otherwise
  }): Promise<Persona> {
    // Idempotent: reuse an existing user on a re-run (POST /users would 409 on the duplicate email).
    let userId = await findUserIdByEmail(input.email);
    if (!userId) {
      const res = await this.asAdmin((token) =>
        this.api.post<{ id?: string; user_id?: string }>('/users', {
          name: input.name,
          email: input.email,
          password: input.password,
          role: input.role,
          city_id: input.cityId,
        }, { token }),
      );
      userId = res.id ?? res.user_id ?? null;
    }
    const login = await this.login(input.email, input.password);
    return {
      userId: userId ?? jwtSub(login.token),
      email: input.email,
      password: input.password,
      role: input.role,
      token: login.token,
    };
  }

  /**
   * Create + activate a B2B account and return the owner persona + account id (idempotent).
   * Flow: POST /auth/request-business-onboarding (pan = gstin[2..12] → mock KYC passes, usually
   * auto-activates) → if still PENDING, ADMIN approves → owner login → GET accounts/mine for the
   * account id → SQL-bump credit_limit_paise (no HTTP path for it). Booking is then done as the owner.
   */
  async provisionB2bAccount(input: {
    companyName: string;
    ownerName: string;
    ownerEmail: string;
    ownerPassword: string;
    phone: string; // +91XXXXXXXXXX
    gstin: string; // 15-char; pan MUST equal gstin.substring(2,12)
    pan: string;
    billingEmail: string;
    homeCity: CityCode; // IATA code, account's home city (does not restrict the lane)
    creditLimitPaise: number;
  }): Promise<{ accountId: string; owner: Persona }> {
    const existing = await findUserIdByEmail(input.ownerEmail);
    if (!existing) {
      const res = await this.api.post<{ request_id: string; status: string; auto_approved?: boolean }>(
        '/auth/request-business-onboarding',
        {
          email: input.ownerEmail,
          name: input.ownerName,
          password: input.ownerPassword,
          phone: input.phone,
          company_name: input.companyName,
          business_type: 'PROPRIETORSHIP',
          gstin: input.gstin,
          pan: input.pan,
          billing_email: input.billingEmail,
          city_id: input.homeCity,
          expected_monthly_orders: '0-200',
        },
      );
      if (res.status !== 'APPROVED') {
        // KYC flagged → clear the ADMIN onboarding queue for this request.
        await this.asAdmin((token) =>
          this.api.post(`/onboarding-requests/${res.request_id}/approve`, undefined, { token }));
      }
    }
    const login = await this.login(input.ownerEmail, input.ownerPassword);
    const owner: Persona = {
      userId: jwtSub(login.token),
      email: input.ownerEmail,
      password: input.ownerPassword,
      role: 'B2B_USER',
      token: login.token,
      city: input.homeCity,
    };
    const account = await this.api.get<{ account_id: string }>('/api/v1/b2b/accounts/mine', {
      token: login.token,
    });
    await setB2bRateCardAndCredit(account.account_id, input.creditLimitPaise, DEMO_B2B_RATE_CARD);
    return { accountId: account.account_id, owner };
  }

  async raiseCredit(accountId: string, limitPaise: number): Promise<void> {
    await bumpCreditLimit(accountId, limitPaise);
  }

  // ---- grid plan (required for pickup auto-assignment) -------------------
  /**
   * Replan + approve a city's DA→hex plan for a specific date + shift. The daIds MUST be passed
   * explicitly: an empty body makes the endpoint default to date=tomorrow and resolve the roster
   * itself, which (a) plans for the wrong day and (b) yields 0 assignments here. With explicit
   * (date, shift, daIds) the solver assigns those DAs and approve materialises da_hex_assignment —
   * the row set gridService.getActiveAssignments + ShiftLoadJob need to put the DAs on shift.
   */
  async replanAndApproveGrid(
    city: CityCode,
    reviewerId: string,
    opts: { date: string; shift: Shift; daIds: string[] },
  ): Promise<void> {
    // Idempotent: re-planning over an already-APPROVED plan for the same DAs+date collides on
    // da_hex_assignment's unique key (backend 500), so skip if today's plan already exists.
    if (await hasApprovedAssignments(opts.daIds, opts.date)) return;
    await this.asAdmin(async (token) => {
      const proposal = await this.api.post<{ proposal_id?: string; id?: string }>(
        `/api/grid/${CITY_SLUG[city]}/replan`,
        { da_ids: opts.daIds, date: opts.date, shift: opts.shift },
        { token },
      );
      const id = proposal.proposal_id ?? proposal.id;
      if (!id) throw new Error(`replan for ${city} returned no proposal id`);
      await this.api.post(`/api/proposals/${id}/approve`, { reviewer_id: reviewerId }, { token });
    });
  }

  // ---- shift roster ------------------------------------------------------
  async shiftLoad(shift: Shift, date?: string): Promise<void> {
    const q = new URLSearchParams({ shift, ...(date ? { date } : {}) });
    await this.asAdmin((token) => this.api.post(`/dispatch/admin/shift-load?${q}`, undefined, { token }));
  }

  // ---- DA presence -------------------------------------------------------
  async daCheckIn(da: Persona): Promise<void> {
    const g = HUB_GEOFENCE[da.city!];
    await this.api.post(`/dispatch/da/${da.userId}/attendance/check-in`, { lat: g.lat, lon: g.lon }, {
      token: da.token,
    });
  }

  async daGps(da: Persona): Promise<void> {
    const g = HUB_GEOFENCE[da.city!];
    await this.api.post(`/dispatch/da/${da.userId}/gps`, { lat: g.lat, lon: g.lon }, { token: da.token });
  }

  // ---- booking (as the B2B owner persona) --------------------------------
  async book(owner: Persona, body: Record<string, unknown>): Promise<BookingResponse> {
    return this.api.post<BookingResponse>('/api/v1/b2b/shipments', body, {
      token: owner.token,
      idempotent: true,
    });
  }

  // ---- gating ------------------------------------------------------------
  async track(ref: string, ownerToken: string): Promise<ShipmentTrackResponse> {
    return this.api.get<ShipmentTrackResponse>(`/api/v1/shipments/mine/${ref}/track`, { token: ownerToken });
  }

  async waitForState(ref: string, ownerToken: string, state: string, timeoutMs = 90_000): Promise<void> {
    await pollUntil(() => this.track(ref, ownerToken), (t) => t.state === state, {
      timeoutMs,
      label: `${ref} → ${state}`,
    });
  }

  async waitForAnyState(ref: string, ownerToken: string, states: string[], timeoutMs = 90_000): Promise<string> {
    const t = await pollUntil(() => this.track(ref, ownerToken), (x) => states.includes(x.state), {
      timeoutMs,
      label: `${ref} → one of [${states.join(', ')}]`,
    });
    return t.state;
  }

  // ---- OTP peek (dev) ----------------------------------------------------
  async peekPickupOtp(ref: string): Promise<string> {
    return this.asAdmin(async (token) =>
      (await this.api.get<OtpPeek>(`/internal/dev/shipments/${ref}/pickup-otp`, { token })).otp,
    );
  }

  async peekDeliveryOtp(ref: string): Promise<string> {
    return this.asAdmin(async (token) =>
      (await this.api.get<OtpPeek>(`/internal/dev/shipments/${ref}/delivery-otp`, { token })).otp,
    );
  }

  // ---- flight time-travel ------------------------------------------------
  async departAndLand(flightNo: string, flightDate: string): Promise<void> {
    await timeTravelFlight(flightNo, flightDate);
    // Two polls: the job advances one step per poll (SCHEDULED→DEPARTED, then DEPARTED→LANDED),
    // each notifying the parcels on the flight.
    const forcePoll = () => this.asAdmin((token) =>
      this.api.post('/internal/dev/airline/force-poll', undefined, { token }));
    await forcePoll();
    await sleep(2000);
    await forcePoll();
  }

  // ---- DA task queue (read) ----------------------------------------------
  async daTasks(da: Persona, date?: string): Promise<DaTaskView[]> {
    const q = date ? `?date=${date}` : '';
    return this.api.get<DaTaskView[]>(`/dispatch/da/${da.userId}/tasks${q}`, { token: da.token });
  }

  /**
   * REST driving of the API-only *volume/background* parcels (not the UI cohort). Each method maps
   * to a DA/hub/airline/shuttle transition. UI-cohort parcels use Playwright/Maestro instead.
   */
  readonly actions = {
    daEnRoute: (da: Persona, taskId: string) =>
      this.api.post(`/dispatch/da/${da.userId}/tasks/${taskId}/en-route`, undefined, { token: da.token }),
    daArrived: (da: Persona, taskId: string) =>
      this.api.post(`/dispatch/da/${da.userId}/tasks/${taskId}/arrived`, undefined, { token: da.token }),
    daVerifyPickupOtp: (ref: string, daToken: string, otp: string) =>
      this.api.post(`/internal/v1/shipments/${ref}/pickup-otp/verify`, { otp }, { token: daToken }),
    daHubHandoff: (da: Persona, taskId: string, scans: string[]) =>
      this.api.post(`/dispatch/da/${da.userId}/tasks/${taskId}/hub-handoff`, { parcel_scans: scans }, { token: da.token }),
    daHubCollect: (da: Persona, taskId: string) =>
      this.api.post(`/dispatch/da/${da.userId}/tasks/${taskId}/hub-collect`, undefined, { token: da.token }),
    daVerifyDeliveryOtp: (ref: string, daToken: string, otp: string) =>
      this.api.post(`/internal/v1/shipments/${ref}/delivery-otp/verify`, { otp }, { token: daToken }),
    daDropCompleted: (da: Persona, taskId: string, codCollected = false) =>
      this.api.post(`/dispatch/da/${da.userId}/tasks/${taskId}/drop-completed`, { cod_collected: codCollected }, { token: da.token }),

    hubReceive: (hubCity: CityCode, ref: string, opToken: string) =>
      this.api.post(`/hub/${CITY_UUID[hubCity]}/receive`, { shipment_ref: ref }, { token: opToken }),
    hubSealBag: (hubCity: CityCode, bagId: string, opToken: string) =>
      this.api.post(`/hub/${CITY_UUID[hubCity]}/bags/${bagId}/seal`, undefined, { token: opToken }),
    hubSealDeliveryBag: (hubCity: CityCode, bagId: string, opToken: string) =>
      this.api.post(`/hub/${CITY_UUID[hubCity]}/delivery-bags/${bagId}/seal`, undefined, { token: opToken }),

    shuttleQueue: (agent: Persona) =>
      this.api.get<{ outbound: { bag_id: string }[]; inbound: { awb_id: string }[] }>(
        `/shuttle/${agent.userId}/queue`, { token: agent.token }),
    shuttleOutToAirport: (agent: Persona, bagIds: string[]) =>
      this.api.post(`/shuttle/${agent.userId}/out-to-airport`, { bag_ids: bagIds }, { token: agent.token }),
    shuttleCollect: (agent: Persona, awbId: string) =>
      this.api.post(`/shuttle/${agent.userId}/awbs/${awbId}/collect-from-airport`, undefined, { token: agent.token }),

    airlineGhaAccepted: (awbId: string, ghaToken: string) =>
      this.api.post(`/airline/awb/${awbId}/gha-accepted`, undefined, { token: ghaToken }),
    airlineAwbByBag: (bagId: string, token: string) =>
      this.api.get<{ id: string; awb_no: string; status: string }>(`/airline/awb/by-bag/${bagId}`, { token }),
  };
}
