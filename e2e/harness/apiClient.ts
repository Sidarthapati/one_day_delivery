/**
 * Thin typed HTTP client over the backend REST API (Node 20 global fetch). Adds Bearer auth and a
 * fresh Idempotency-Key on mutations. Mirrors how oneday-web/packages/api talks to the backend.
 */
import { randomUUID } from 'node:crypto';
import { env } from './env';

export class ApiError extends Error {
  constructor(readonly status: number, readonly path: string, readonly body: unknown) {
    super(`${status} on ${path}: ${typeof body === 'string' ? body : JSON.stringify(body)}`);
    this.name = 'ApiError';
  }
}

export interface RequestOpts {
  token?: string;
  /** extra headers, e.g. X-Device-Id */
  headers?: Record<string, string>;
  /** send an Idempotency-Key (auto for POST/PUT when true) */
  idempotent?: boolean;
}

export class ApiClient {
  constructor(private readonly baseUrl: string = env.backendUrl) {}

  async request<T>(method: string, path: string, body?: unknown, opts: RequestOpts = {}): Promise<T> {
    const headers: Record<string, string> = { ...(opts.headers ?? {}) };
    if (body !== undefined) headers['Content-Type'] = 'application/json';
    if (opts.token) headers['Authorization'] = `Bearer ${opts.token}`;
    if (opts.idempotent) headers['Idempotency-Key'] = randomUUID();

    const res = await fetch(`${this.baseUrl}${path}`, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
    });

    const text = await res.text();
    const parsed = text ? safeJson(text) : undefined;
    if (!res.ok) throw new ApiError(res.status, path, parsed ?? text);
    return parsed as T;
  }

  get<T>(path: string, opts?: RequestOpts) {
    return this.request<T>('GET', path, undefined, opts);
  }
  post<T>(path: string, body?: unknown, opts?: RequestOpts) {
    return this.request<T>('POST', path, body, opts);
  }
  put<T>(path: string, body?: unknown, opts?: RequestOpts) {
    return this.request<T>('PUT', path, body, opts);
  }
  del<T>(path: string, opts?: RequestOpts) {
    return this.request<T>('DELETE', path, undefined, opts);
  }
}

function safeJson(text: string): unknown {
  try {
    return JSON.parse(text);
  } catch {
    return text;
  }
}
