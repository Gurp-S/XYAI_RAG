import { clearAccessToken, getAccessToken, refreshAccessToken } from "./auth";

const AUTH_EXCLUDE_PATHS = ["/user/login", "/user/registry", "/user/refresh"];

const AUTH_FETCH_STATE_KEY = "__xyai_auth_fetch_state__";
const AUTH_FETCH_WRAPPED_FLAG = "__xyai_auth_fetch_wrapped__";
const AUTH_FETCH_NATIVE_PROP = "__xyai_auth_fetch_native__";

function getAuthFetchState() {
  const g = globalThis;
  if (!g[AUTH_FETCH_STATE_KEY]) {
    g[AUTH_FETCH_STATE_KEY] = {
      installed: false,
      nativeFetch: null,
    };
  }
  return g[AUTH_FETCH_STATE_KEY];
}

function resolveUrl(input) {
  try {
    if (typeof input === "string") {
      return new URL(input, window.location.origin);
    }
    if (input instanceof URL) {
      return input;
    }
    if (typeof Request !== "undefined" && input instanceof Request) {
      return new URL(input.url, window.location.origin);
    }
  } catch {
    return null;
  }
  return null;
}

function resolvePath(input) {
  const url = resolveUrl(input);
  return url ? url.pathname : "";
}

function shouldSkipAuth(input, init, resolvedUrl, sameOrigin) {
  if (init?.__skipAuth) return true;
  if (sameOrigin === false) return true;
  const path = resolvedUrl ? resolvedUrl.pathname : resolvePath(input);
  return AUTH_EXCLUDE_PATHS.some((prefix) => path.startsWith(prefix));
}

function getFetchImpl() {
  const state = getAuthFetchState();
  if (typeof state.nativeFetch === "function") {
    return state.nativeFetch;
  }
  const currentFetch = fetch;
  if (
    currentFetch &&
    typeof currentFetch[AUTH_FETCH_NATIVE_PROP] === "function"
  ) {
    return currentFetch[AUTH_FETCH_NATIVE_PROP];
  }
  return fetch.bind(globalThis);
}

function normalizeInit(init, sameOrigin) {
  const next = { ...(init || {}) };
  if ("__skipAuth" in next) {
    delete next.__skipAuth;
  }
  if (sameOrigin && !next.credentials) {
    next.credentials = "include";
  }
  return next;
}

async function requestWithAuth(input, init = {}, allowRetry = true) {
  const baseFetch = getFetchImpl();
  const resolvedUrl = resolveUrl(input);
  const sameOrigin =
    typeof window === "undefined" || !resolvedUrl
      ? true
      : resolvedUrl.origin === window.location.origin;
  const skipAuth = shouldSkipAuth(input, init, resolvedUrl, sameOrigin);
  const requestInit = normalizeInit(init, sameOrigin);

  const headers = new Headers(requestInit.headers || {});
  const existingAuthHeader = headers.get("Authorization");
  let token;
  const path = resolvedUrl ? resolvedUrl.pathname : resolvePath(input);
  if (!skipAuth && !existingAuthHeader) {
    token = getAccessToken();
    if (token) {
      headers.set("Authorization", `Bearer ${token}`);
    } else {
      if (
        typeof console !== "undefined" &&
        typeof console.debug === "function"
      ) {
        console.debug(
          `[authFetch] no access token for protected request: ${requestInit.method || "GET"} ${path}`,
        );
      }
    }
  }

  // CRITICAL: Do NOT set Content-Type for FormData uploads —
  // the browser must set multipart/form-data with boundary automatically.
  const isFormData = requestInit.body instanceof FormData;
  if (!isFormData && !headers.has("Content-Type")) {
    headers.set("Content-Type", "application/json");
  }

  requestInit.headers = headers;

  let response = await baseFetch(input, requestInit);

  if (response?.status === 401 && !skipAuth) {
    if (typeof console !== "undefined" && typeof console.warn === "function") {
      console.warn(
        `[authFetch] received 401 for ${requestInit.method || "GET"} ${path}`,
      );
    }
  }

  if (response.status === 401 && allowRetry && !skipAuth) {
    const refreshed = await refreshAccessToken(true);
    if (refreshed) {
      const retryHeaders = new Headers(requestInit.headers || {});
      retryHeaders.set("Authorization", `Bearer ${refreshed}`);
      response = await baseFetch(input, {
        ...requestInit,
        headers: retryHeaders,
      });
    } else {
      clearAccessToken();
    }
  }

  return response;
}

export async function authFetch(input, init = {}) {
  return requestWithAuth(input, init, true);
}

export async function safeReadJson(response) {
  try {
    return await response.json();
  } catch {
    return null;
  }
}

// ===================== 文件分享相关 API =====================

/**
 * 分享文件给指定用户（创建分享消息）
 */
export async function apiShareFile(collectionName, fileId, userId, chunkId, fileName) {
  const params = new URLSearchParams({
    collectionName,
    fileId,
    userId,
  });
  if (chunkId != null && chunkId > 0) {
    params.set("chunkId", chunkId);
  }
  if (fileName) {
    params.set("fileName", fileName);
  }
  const response = await authFetch(
    `/milvus/share/message?${params.toString()}`,
    { method: "POST" },
  );
  return safeReadJson(response);
}

/**
 * 获取当前用户的好友列表
 */
export async function apiGetFriends() {
  const response = await authFetch("/user/friend", { method: "GET" });
  return safeReadJson(response);
}

/**
 * 接受文件分享（授予接收方 ACL 权限）
 */
export async function apiAcceptFileShare(collectionName, fileId, userId, chunkId = 0) {
  const params = new URLSearchParams({
    collectionName,
    fileId,
    userId: String(userId),
    chunkId: String(chunkId),
  });
  const response = await authFetch(`/milvus/share?${params.toString()}`, {
    method: "POST",
  });
  return safeReadJson(response);
}

/**
 * 接收方接受文件分享 — 由接收方调用
 */
export async function apiAcceptShare(collectionName, fileId, senderId, chunkId = 0) {
  const params = new URLSearchParams({
    collectionName,
    fileId,
    senderId: String(senderId),
  });
  if (chunkId > 0) {
    params.set("chunkId", String(chunkId));
  }
  const response = await authFetch(`/milvus/share/accept?${params.toString()}`, {
    method: "POST",
  });
  return safeReadJson(response);
}

/**
 * 接收方拒绝文件分享 — 删除聊天消息
 */
export async function apiRejectShare(conversationId, messageId) {
  const params = new URLSearchParams({
    conversationId,
    messageId: String(messageId),
  });
  const response = await authFetch(`/milvus/share/reject?${params.toString()}`, {
    method: "POST",
  });
  return safeReadJson(response);
}

/**
 * 获取公告列表（用户端）
 */
export async function apiGetAnnouncements() {
  const response = await authFetch("/user/announcement/list", { method: "GET" });
  const result = await safeReadJson(response);
  return result?.data ?? [];
}

export function installAuthFetch() {
  if (typeof fetch !== "function") return;

  const state = getAuthFetchState();
  const currentFetch = fetch;

  // Already wrapped by us (or previous HMR instance)
  if (currentFetch && currentFetch[AUTH_FETCH_WRAPPED_FLAG]) {
    state.installed = true;
    if (typeof currentFetch[AUTH_FETCH_NATIVE_PROP] === "function") {
      state.nativeFetch = currentFetch[AUTH_FETCH_NATIVE_PROP];
    }
    return;
  }

  if (state.installed) return;

  const nativeFetch = currentFetch.bind(globalThis);
  state.nativeFetch = nativeFetch;

  const wrappedFetch = (input, init) =>
    requestWithAuth(input, init || {}, true);
  wrappedFetch[AUTH_FETCH_WRAPPED_FLAG] = true;
  wrappedFetch[AUTH_FETCH_NATIVE_PROP] = nativeFetch;

  globalThis.fetch = wrappedFetch;
  state.installed = true;
}
