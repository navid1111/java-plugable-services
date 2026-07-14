export const GATEWAY = 'http://localhost:18000';
export const TOKEN_KEY = 'appbuilder.jwt';

export class ApiError extends Error {
  constructor(status, message, payload = null) {
    super(message || `Request failed (${status})`);
    this.name = 'ApiError'; this.status = status; this.payload = payload;
  }
}

export function getToken() { return localStorage.getItem(TOKEN_KEY); }
export function setToken(token) { token ? localStorage.setItem(TOKEN_KEY, token) : localStorage.removeItem(TOKEN_KEY); }
export function logout() { localStorage.removeItem(TOKEN_KEY); }

export async function api(path, options = {}) {
  const headers = { ...(options.headers || {}) };
  const token = getToken();
  if (token) headers.Authorization = `Bearer ${token}`;
  if (options.body != null && !(options.body instanceof FormData)) {
    headers['Content-Type'] = headers['Content-Type'] || 'application/json';
  }
  const response = await fetch(GATEWAY + path, { ...options, headers });
  const contentType = response.headers.get('content-type') || '';
  const payload = response.status === 204 ? null : contentType.includes('application/json')
    ? await response.json() : await response.text();
  if (!response.ok) {
    const message = typeof payload === 'string' ? payload : payload?.error || payload?.message;
    throw new ApiError(response.status, message, payload);
  }
  return payload;
}
