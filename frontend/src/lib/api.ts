// Browser-side API client. All calls go to the Next origin under /api/gateway/*,
// which Next rewrites to the Spring gateway (see next.config.mjs). This keeps the
// browser talking only to the Next server — no CORS, works via SSH tunnel.

const BASE = "/api/gateway";

export class ApiError extends Error {
  status: number;
  constructor(status: number, message: string) {
    super(message);
    this.status = status;
  }
}

function authHeaders(token?: string | null): Record<string, string> {
  const headers: Record<string, string> = { "Content-Type": "application/json" };
  if (token) headers["Authorization"] = `Bearer ${token}`;
  return headers;
}

async function handle<T>(res: Response): Promise<T> {
  if (!res.ok) {
    let message = `HTTP ${res.status}`;
    try {
      const body = await res.json();
      if (body?.error) message = body.error;
    } catch {
      /* ignore */
    }
    throw new ApiError(res.status, message);
  }
  if (res.status === 204) return undefined as T;
  return res.json() as Promise<T>;
}

export async function apiGet<T>(path: string, token?: string | null): Promise<T> {
  const res = await fetch(`${BASE}${path}`, { headers: authHeaders(token), cache: "no-store" });
  return handle<T>(res);
}

export async function apiPost<T>(path: string, body: unknown, token?: string | null): Promise<T> {
  const res = await fetch(`${BASE}${path}`, {
    method: "POST",
    headers: authHeaders(token),
    body: JSON.stringify(body),
  });
  return handle<T>(res);
}

export async function apiPut<T>(path: string, body: unknown, token?: string | null): Promise<T> {
  const res = await fetch(`${BASE}${path}`, {
    method: "PUT",
    headers: authHeaders(token),
    body: JSON.stringify(body),
  });
  return handle<T>(res);
}

export async function apiDelete<T>(path: string, token?: string | null): Promise<T> {
  const res = await fetch(`${BASE}${path}`, { method: "DELETE", headers: authHeaders(token) });
  return handle<T>(res);
}
