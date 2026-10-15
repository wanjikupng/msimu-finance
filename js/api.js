// One fetch wrapper for every backend call. Turns HTTP/validation/network
// failures into ApiError {status, message, fieldErrors} and owns the session:
// JWT in sessionStorage with its expiry, Bearer header on every call, and a
// single 401 path that clears the session and returns to login.html.
import { API_BASE, SESSION_KEY, ROLE_HOME } from "./config.js";

export class ApiError extends Error {
  constructor(status, message, fieldErrors = {}) {
    super(message || "Something went wrong.");
    this.name = "ApiError";
    this.status = status;
    this.fieldErrors = fieldErrors;
  }
}

function readSession() {
  try {
    const raw = sessionStorage.getItem(SESSION_KEY);
    if (!raw) return null;
    return JSON.parse(raw);
  } catch {
    return null;
  }
}

export const session = {
  load() {
    return readSession();
  },
  // auth = POST /auth/login response: {token, expiresInSeconds, userId, ...}
  save(auth) {
    const value = {
      token: auth.token,
      // A 60s skew margin so we never send a token that dies mid-request.
      expiresAt: Date.now() + Math.max(0, (auth.expiresInSeconds || 3600) - 60) * 1000,
      userId: auth.userId,
      customerCode: auth.customerCode,
      fullName: auth.fullName,
      role: auth.role,
    };
    sessionStorage.setItem(SESSION_KEY, JSON.stringify(value));
    return value;
  },
  clear() {
    sessionStorage.removeItem(SESSION_KEY);
  },
  valid() {
    const s = readSession();
    return !!(s && s.token && s.expiresAt && Date.now() < s.expiresAt);
  },
};

export function homeFor(role) {
  return ROLE_HOME[role] || "login.html";
}

// Guards a portal page. Returns the session when the signed-in role is
// allowed, otherwise redirects (wrong role -> their own home, none -> login).
export function requireRole(allowedRoles) {
  const s = readSession();
  const here = window.location.pathname.split("/").pop();
  if (!s || !s.token) {
    window.location.replace(`login.html?next=${encodeURIComponent(here + window.location.hash)}`);
    return null;
  }
  if (!allowedRoles.includes(s.role)) {
    window.location.replace(homeFor(s.role));
    return null;
  }
  return s;
}

export function signOut() {
  session.clear();
  window.location.replace("login.html");
}

function handle401(redirectOn401) {
  session.clear();
  if (redirectOn401) {
    const here = window.location.pathname.split("/").pop();
    window.location.replace(`login.html?next=${encodeURIComponent(here + window.location.hash)}`);
  }
}

export async function api(path, { method, body, headers = {}, redirectOn401 = true } = {}) {
  const s = readSession();
  // Default to POST when a body is present: the Fetch spec forbids bodies on
  // GET/HEAD (it throws TypeError), so an absent method must never stay GET.
  const httpMethod = method || (body !== undefined ? "POST" : "GET");
  let res;
  try {
    res = await fetch(API_BASE + path, {
      method: httpMethod,
      headers: {
        ...(body !== undefined ? { "Content-Type": "application/json" } : {}),
        ...(s && s.token ? { Authorization: `Bearer ${s.token}` } : {}),
        ...headers,
      },
      body: body !== undefined ? JSON.stringify(body) : undefined,
    });
  } catch {
    throw new ApiError(0, "Cannot reach the server on port 8080. Is the backend running?");
  }

  if (res.status === 401) {
    handle401(redirectOn401);
    throw new ApiError(401, "Your session has expired. Please sign in again.");
  }

  let data = null;
  const text = await res.text();
  if (text) {
    try {
      data = JSON.parse(text);
    } catch {
      data = null;
    }
  }

  if (!res.ok) {
    const message =
      (data && (data.message || data.error)) ||
      (res.status === 403
        ? "You do not have permission to do that."
        : res.status === 404
          ? "That record was not found."
          : `Request failed (${res.status}).`);
    throw new ApiError(res.status, message, (data && data.fieldErrors) || {});
  }
  return data;
}
