let accessToken = "";
let accessTokenExpMs = 0;
let refreshInFlight = null;
const REFRESH_TIMEOUT_MS = 7000;
const TOKEN_REFRESH_SKEW_MS = 10000;
const AUTH_SESSION_KEY = "authSession";
const AUTH_TOKEN_CHANGE_EVENT = "xyai:auth-token-change";

function toToken(raw) {
  if (typeof raw !== "string") return "";
  return raw.trim();
}

function decodeJwtPayload(token) {
  const parts = String(token || "").split(".");
  if (parts.length < 2) return null;

  const base64 = parts[1].replace(/-/g, "+").replace(/_/g, "/");
  const padded = base64.padEnd(Math.ceil(base64.length / 4) * 4, "=");

  if (typeof atob !== "function") return null;
  return JSON.parse(atob(padded));
}

function resolveTokenExpMs(token) {
  try {
    const payload = decodeJwtPayload(token);
    const exp = Number(payload?.exp);
    if (!Number.isFinite(exp) || exp <= 0) return 0;
    return exp * 1000;
  } catch {
    return 0;
  }
}

function readPersistedSession() {
  if (typeof window === "undefined" || !window.sessionStorage) {
    return null;
  }

  try {
    const raw = window.sessionStorage.getItem(AUTH_SESSION_KEY);
    if (!raw) return null;

    const parsed = JSON.parse(raw);
    const token = toToken(parsed?.accessToken);
    const expMs = Number(parsed?.expMs || 0);
    if (!token) return null;
    if (Number.isFinite(expMs) && expMs > 0 && expMs <= Date.now()) {
      window.sessionStorage.removeItem(AUTH_SESSION_KEY);
      return null;
    }

    return {
      token,
      expMs:
        Number.isFinite(expMs) && expMs > 0 ? expMs : resolveTokenExpMs(token),
    };
  } catch {
    return null;
  }
}

function persistSession(token) {
  if (typeof window === "undefined" || !window.sessionStorage) {
    return;
  }

  try {
    if (!token) {
      window.sessionStorage.removeItem(AUTH_SESSION_KEY);
      return;
    }

    window.sessionStorage.setItem(
      AUTH_SESSION_KEY,
      JSON.stringify({
        accessToken: token,
        expMs: accessTokenExpMs,
      }),
    );
  } catch {
    // Ignore persistence failures so auth still works in constrained browsers.
  }
}

function emitTokenChange() {
  if (
    typeof window === "undefined" ||
    typeof window.dispatchEvent !== "function"
  ) {
    return;
  }

  try {
    window.dispatchEvent(
      new CustomEvent(AUTH_TOKEN_CHANGE_EVENT, {
        detail: {
          accessToken,
          expMs: accessTokenExpMs,
        },
      }),
    );
  } catch {
    // No-op: consumers can still read the current token directly.
  }
}

function hasUsableToken(skewMs = 0) {
  if (!accessToken) return false;
  if (!accessTokenExpMs) return true;
  return accessTokenExpMs - Date.now() > skewMs;
}

async function safeJson(response) {
  try {
    return await response.json();
  } catch {
    return null;
  }
}

export function getAccessToken() {
  return accessToken;
}

export function getAccessTokenExpMs() {
  return accessTokenExpMs;
}

export function setAccessToken(token) {
  accessToken = toToken(token);
  accessTokenExpMs = accessToken ? resolveTokenExpMs(accessToken) : 0;
  persistSession(accessToken);
  emitTokenChange();
}

export function clearAccessToken() {
  accessToken = "";
  accessTokenExpMs = 0;
  persistSession("");
  emitTokenChange();
}

export async function refreshAccessToken(force = false) {
  if (!force && hasUsableToken(TOKEN_REFRESH_SKEW_MS)) {
    return accessToken;
  }

  if (refreshInFlight) {
    return refreshInFlight;
  }

  refreshInFlight = (async () => {
    const controller =
      typeof AbortController !== "undefined" ? new AbortController() : null;
    const timeoutId = controller
      ? setTimeout(() => controller.abort(), REFRESH_TIMEOUT_MS)
      : null;

    try {
      const response = await fetch("/user/refresh", {
        method: "POST",
        credentials: "include",
        ...(controller ? { signal: controller.signal } : {}),
      });

      const payload = await safeJson(response);
      const nextToken = toToken(payload?.data?.accessToken);

      if (!response.ok || payload?.code !== 200 || !nextToken) {
        clearAccessToken();
        return "";
      }

      setAccessToken(nextToken);
      return nextToken;
    } catch {
      clearAccessToken();
      return "";
    } finally {
      if (timeoutId) {
        clearTimeout(timeoutId);
      }
      refreshInFlight = null;
    }
  })();

  return refreshInFlight;
}

export async function ensureAccessToken() {
  if (hasUsableToken(TOKEN_REFRESH_SKEW_MS)) {
    return accessToken;
  }
  return refreshAccessToken(true);
}

const persistedSession = readPersistedSession();
if (persistedSession) {
  accessToken = persistedSession.token;
  accessTokenExpMs = persistedSession.expMs;
}
