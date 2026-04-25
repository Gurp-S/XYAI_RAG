import { defineStore } from "pinia";
import { authFetch, safeReadJson } from "../services/api";
import {
  clearAccessToken,
  getAccessToken,
  getAccessTokenExpMs,
  refreshAccessToken,
  setAccessToken,
} from "../services/auth";
import router from "../router";

const THEME_KEY = "themeName";
const DARK_KEY = "theme";
const BG_KEY = "userBackgroundImage";
const BG_BLUR_KEY = "userBackgroundBlur";
const SIDEBAR_STYLE_KEY = "sidebarStyle";
const CONVERSATION_CACHE_KEY = "conversationCache";
const CONVERSATION_CACHE_ORDER_KEY = "conversationCacheOrder";
const USER_CHAT_SESSIONS_KEY = "userChatSessions";
const LAST_AI_CONVERSATION_KEY = "lastAiConversationId";
const AUTH_TOKEN_CHANGE_EVENT = "xyai:auth-token-change";
const MAX_CONVERSATION_CACHE_ITEMS = 18;
const MAX_CONVERSATION_MESSAGE_COUNT = 60;
const MAX_CONVERSATION_TEXT_LENGTH = 4000;
const MAX_CONVERSATION_ERROR_LENGTH = 2000;
const MAX_CONVERSATION_RAG_ITEMS = 12;
const SIDEBAR_STYLES = new Set(["orbit", "outline"]);
const THEME_NAMES = new Set([
  "deep-space",
  "ink-gold",
  "pine-night",
  "cyber-violet",
]);
let sidebarAnimationTimer = null;
let sidebarAnimating = false;
let themeTransitionCleanupTimer = null;
let themeTransitionFrame = 0;
let sessionExpiryTimer = null;
let authTokenListenerInstalled = false;
let authTokenListenerCallback = null;

function clampBackgroundBlur(value) {
  const num = Number(value);
  if (!Number.isFinite(num)) return 0;
  return Math.min(24, Math.max(0, Math.round(num)));
}

function normalizeUser(raw) {
  if (!raw || typeof raw !== "object") return null;

  const rawId =
    raw.id ??
    raw.userId ??
    raw.uid ??
    raw.userID ??
    raw.accountId ??
    raw.username ??
    null;

  // 保证返回的 id 始终为字符串（若存在），避免后续在应用中误用数字并丢失精度
  const id = rawId === null || rawId === undefined ? null : String(rawId);

  const name =
    raw.name ??
    raw.nickName ??
    raw.nickname ??
    raw.userName ??
    raw.username ??
    raw.displayName ??
    raw.email ??
    null;

  if (!id && !name) return null;

  return {
    ...raw,
    id: id ?? name,
    name: name ?? String(id ?? ""),
  };
}

function readJsonStorage(key, fallback) {
  try {
    const raw = localStorage.getItem(key);
    return raw ? JSON.parse(raw) : fallback;
  } catch {
    return fallback;
  }
}

function trimText(value, maxLength = MAX_CONVERSATION_TEXT_LENGTH) {
  const text = String(value || "");
  if (text.length <= maxLength) return text;
  return `${text.slice(0, Math.max(0, maxLength - 3))}...`;
}

function normalizeRagEntry(entry) {
  if (entry === null || entry === undefined) return entry;
  if (typeof entry !== "object") {
    return trimText(entry, MAX_CONVERSATION_TEXT_LENGTH);
  }

  const normalized = {};
  const keyList = [
    "title",
    "name",
    "source",
    "url",
    "type",
    "fileName",
    "chunkId",
    "score",
    "content",
    "snippet",
    "summary",
  ];

  keyList.forEach((key) => {
    if (entry[key] === undefined) return;
    normalized[key] =
      typeof entry[key] === "string"
        ? trimText(entry[key], MAX_CONVERSATION_TEXT_LENGTH)
        : entry[key];
  });

  if (Object.keys(normalized).length > 0) {
    return normalized;
  }

  try {
    return trimText(JSON.stringify(entry), MAX_CONVERSATION_TEXT_LENGTH);
  } catch {
    return {};
  }
}

function normalizeConversationMessage(message) {
  if (!message || typeof message !== "object") {
    return null;
  }

  const normalized = { ...message };
  if ("text" in normalized) {
    normalized.text = trimText(normalized.text, MAX_CONVERSATION_TEXT_LENGTH);
  }
  if ("errorMessage" in normalized) {
    normalized.errorMessage = trimText(
      normalized.errorMessage,
      MAX_CONVERSATION_ERROR_LENGTH,
    );
  }
  if ("pendingSendText" in normalized) {
    normalized.pendingSendText = trimText(
      normalized.pendingSendText,
      MAX_CONVERSATION_TEXT_LENGTH,
    );
  }
  if ("fromName" in normalized) {
    normalized.fromName = trimText(normalized.fromName, 120);
  }
  if (Array.isArray(normalized.ragData)) {
    normalized.ragData = normalized.ragData
      .slice(0, MAX_CONVERSATION_RAG_ITEMS)
      .map((entry) => normalizeRagEntry(entry));
  }

  return normalized;
}

function normalizeConversationMessages(messages) {
  if (!Array.isArray(messages)) return [];
  return messages
    .map((message) => normalizeConversationMessage(message))
    .filter(Boolean)
    .slice(-MAX_CONVERSATION_MESSAGE_COUNT);
}

function readConversationCache() {
  const rawCache = readJsonStorage(CONVERSATION_CACHE_KEY, {});
  if (!rawCache || typeof rawCache !== "object" || Array.isArray(rawCache)) {
    return {};
  }

  const normalized = {};
  Object.entries(rawCache).forEach(([conversationId, messages]) => {
    const safeConversationId = String(conversationId || "").trim();
    if (!safeConversationId) return;
    const safeMessages = normalizeConversationMessages(messages);
    if (safeMessages.length > 0) {
      normalized[safeConversationId] = safeMessages;
    }
  });
  return normalized;
}

function readConversationCacheOrder(cache) {
  const storedOrder = readJsonStorage(CONVERSATION_CACHE_ORDER_KEY, []);
  const normalizedOrder = [];
  const rawOrder = Array.isArray(storedOrder)
    ? storedOrder
        .map((conversationId) => String(conversationId || "").trim())
        .filter((conversationId) => conversationId && cache[conversationId])
    : [];

  rawOrder.forEach((conversationId) => {
    if (!normalizedOrder.includes(conversationId)) {
      normalizedOrder.push(conversationId);
    }
  });

  Object.keys(cache).forEach((conversationId) => {
    if (!normalizedOrder.includes(conversationId)) {
      normalizedOrder.push(conversationId);
    }
  });

  return normalizedOrder;
}

function persistConversationCache(cache, order) {
  try {
    localStorage.setItem(CONVERSATION_CACHE_KEY, JSON.stringify(cache || {}));
    localStorage.setItem(
      CONVERSATION_CACHE_ORDER_KEY,
      JSON.stringify(Array.isArray(order) ? order : []),
    );
  } catch (error) {
    console.warn("Failed to persist conversation cache:", error);
  }
}

function resolveViewFromPath(path) {
  return String(path || "").startsWith("/db") ? "db" : "chat";
}

function ensureSessionExpiryTimer(store) {
  if (sessionExpiryTimer) {
    clearTimeout(sessionExpiryTimer);
    sessionExpiryTimer = null;
  }

  const token = getAccessToken();
  const expMs = getAccessTokenExpMs();
  if (!token || !expMs) {
    return;
  }

  const remaining = expMs - Date.now();
  if (remaining <= 0) {
    return;
  }

  sessionExpiryTimer = setTimeout(async () => {
    sessionExpiryTimer = null;
    if (!store.currentUser) {
      return;
    }

    try {
      await store.logout({ localOnly: false, reason: "session-expired" });
    } catch (error) {
      console.warn("Auto logout failed:", error);
      clearAccessToken();
      store.setUser(null);
    }
  }, remaining);
}

function clearSessionExpiryTimer() {
  if (!sessionExpiryTimer) return;
  clearTimeout(sessionExpiryTimer);
  sessionExpiryTimer = null;
}

function normalizeThemeName(themeName) {
  const next = String(themeName || "").trim();
  if (THEME_NAMES.has(next)) return next;
  return "deep-space";
}

function readThemeName() {
  return normalizeThemeName(localStorage.getItem(THEME_KEY));
}

function readDarkMode() {
  return localStorage.getItem(DARK_KEY) === "dark";
}

function readBackgroundImage() {
  return localStorage.getItem(BG_KEY) || "";
}

function readBackgroundBlur() {
  return clampBackgroundBlur(localStorage.getItem(BG_BLUR_KEY) || 4);
}

function readSidebarStyle() {
  const style = localStorage.getItem(SIDEBAR_STYLE_KEY) || "orbit";
  return SIDEBAR_STYLES.has(style) ? style : "orbit";
}

function buildContactConversationId(type, currentUserId, targetId) {
  const targetType = type === "group" ? "group" : "user";
  const normalizedTargetId = String(targetId || "").trim();
  if (!normalizedTargetId) return null;

  if (targetType === "group") {
    return `group:${normalizedTargetId}`;
  }

  const current = String(currentUserId || "").trim();
  if (!current) return null;

  // 如果两个 id 都是纯数字字符串，优先使用 BigInt 做数值比较以避免字符串字典序带来的不一致性
  const isNumericPair =
    /^\d+$/.test(current) && /^\d+$/.test(normalizedTargetId);
  if (isNumericPair) {
    try {
      const a = BigInt(current);
      const b = BigInt(normalizedTargetId);
      return a <= b
        ? `user:${current}:${normalizedTargetId}`
        : `user:${normalizedTargetId}:${current}`;
    } catch (e) {
      // 若 BigInt 不可用或转换失败，回退到字符串比较
    }
  }

  return current <= normalizedTargetId
    ? `user:${current}:${normalizedTargetId}`
    : `user:${normalizedTargetId}:${current}`;
}

function createConversationId() {
  if (
    typeof crypto !== "undefined" &&
    typeof crypto.randomUUID === "function"
  ) {
    return crypto.randomUUID();
  }

  return `conv_${Date.now()}_${Math.random().toString(16).slice(2)}`;
}

function toCssUrl(value) {
  if (!value) return "none";
  const safe = String(value).replace(/["\\\n\r]/g, "\\$&");
  return `url("${safe}")`;
}

function applyBodyAppearance(
  darkMode,
  themeName,
  backgroundImage,
  backgroundBlur,
  sidebarStyle,
) {
  const body = document.body;
  if (!body) return;

  const nextThemeName = normalizeThemeName(themeName);

  body.classList.toggle("dark", !!darkMode);

  const themeClassList = Array.from(body.classList).filter((cls) =>
    cls.startsWith("theme-"),
  );
  themeClassList.forEach((cls) => body.classList.remove(cls));

  body.classList.add(`theme-${nextThemeName}`);

  const sidebarStyleClassList = Array.from(body.classList).filter((cls) =>
    cls.startsWith("sidebar-style-"),
  );
  sidebarStyleClassList.forEach((cls) => body.classList.remove(cls));

  const nextSidebarStyle = SIDEBAR_STYLES.has(sidebarStyle)
    ? sidebarStyle
    : "orbit";
  body.classList.add(`sidebar-style-${nextSidebarStyle}`);

  if (backgroundImage) {
    body.classList.add("has-user-bg");
    body.style.setProperty("--user-bg-image", toCssUrl(backgroundImage));
  } else {
    body.classList.remove("has-user-bg");
    body.style.setProperty("--user-bg-image", "none");
  }

  body.style.setProperty(
    "--user-bg-blur",
    `${clampBackgroundBlur(backgroundBlur)}px`,
  );
  body.style.setProperty(
    "--panel-backdrop-blur",
    `${clampBackgroundBlur(backgroundBlur)}px`,
  );
}

function runAppearanceTransition(
  update,
  overlayColor = "rgba(255, 255, 255, 0.36)",
) {
  const body = typeof document !== "undefined" ? document.body : null;
  if (!body) {
    update();
    return;
  }

  const prefersReducedMotion =
    typeof window !== "undefined" &&
    typeof window.matchMedia === "function" &&
    window.matchMedia("(prefers-reduced-motion: reduce)").matches;
  if (prefersReducedMotion) {
    update();
    return;
  }

  if (typeof document.startViewTransition === "function") {
    body.classList.add("theme-switching");
    if (themeTransitionCleanupTimer) {
      clearTimeout(themeTransitionCleanupTimer);
    }

    try {
      const transition = document.startViewTransition(() => {
        update();
      });

      Promise.resolve(transition?.finished)
        .catch(() => {})
        .finally(() => {
          themeTransitionCleanupTimer = setTimeout(() => {
            body.classList.remove("theme-switching");
          }, 96);
        });
      return;
    } catch (error) {
      console.warn("View transition failed, fallback to overlay:", error);
    }
  }

  body.classList.add("theme-switching");
  if (themeTransitionCleanupTimer) {
    clearTimeout(themeTransitionCleanupTimer);
  }
  themeTransitionCleanupTimer = setTimeout(() => {
    body.classList.remove("theme-switching");
  }, 96);

  let overlay = body.querySelector(".theme-transition-overlay");
  if (!overlay) {
    overlay = document.createElement("div");
    overlay.className = "theme-transition-overlay";
    body.appendChild(overlay);
  }

  overlay.style.background = overlayColor;
  overlay.style.opacity = "0";

  if (themeTransitionFrame) {
    cancelAnimationFrame(themeTransitionFrame);
    themeTransitionFrame = 0;
  }

  themeTransitionFrame = requestAnimationFrame(() => {
    themeTransitionFrame = 0;
    overlay.style.opacity = "0.08";
    themeTransitionFrame = requestAnimationFrame(() => {
      themeTransitionFrame = 0;
      update();
      themeTransitionFrame = requestAnimationFrame(() => {
        themeTransitionFrame = 0;
        overlay.style.opacity = "0";
      });
    });
  });
}

export const useUiStore = defineStore("ui", {
  state: () => ({
    showLogin: false,
    currentUser: normalizeUser(readJsonStorage("currentUser", null)),
    highPerf: false,
    isSidebarCollapsed: false,
    activeModal: null,
    uploadTargetCollection: null,
    isUploading: false,
    uploadProgress: 0,
    uploadStatusText: "",
    currentView: "chat", // 'chat' or 'db'
    chatMode: "ai", // 'ai' | 'user' | 'group'
    chatTarget: null,
    chatHistory: JSON.parse(localStorage.getItem("chatHistory")) || [],
    loadingHistory: false,
    activeConversationId: createConversationId(),
    lastAiConversationId:
      localStorage.getItem(LAST_AI_CONVERSATION_KEY) || null,
    currentMessages: [],
    showLogoutConfirm: false,
    darkMode: readDarkMode(),
    themeName: readThemeName(),
    sidebarStyle: readSidebarStyle(),
    backgroundImage: readBackgroundImage(),
    backgroundBlur: readBackgroundBlur(),
    conversationCache: readConversationCache(),
    conversationCacheOrder: readConversationCacheOrder(readConversationCache()),
    userChatSessions: readJsonStorage(USER_CHAT_SESSIONS_KEY, {}),
  }),
  getters: {
    userDisplayName: (state) =>
      state.currentUser?.name || state.currentUser?.id || "未登录",
    userDisplayId: (state) => state.currentUser?.id || "",
    userAvatarText: (state) => {
      const source = state.currentUser?.name || state.currentUser?.id || "?";
      return String(source).substring(0, 2).toUpperCase();
    },
  },
  actions: {
    async bootstrapAuth() {
      try {
        this.initAuthSession();
        const token = await refreshAccessToken(true);
        if (!token) {
          clearAccessToken();
          this.setUser(null);
          return;
        }

        const profile = await this.fetchCurrentUserInfo();
        if (!profile) {
          clearAccessToken();
          this.setUser(null);
          return;
        }

        this.setUser(profile);
        this.showLogin = false;
        this.initAuthSession();
        this.fetchHistory(true);
      } catch (error) {
        console.error("Auth bootstrap failed:", error);
        clearAccessToken();
        this.setUser(null);
      }
    },
    async fetchCurrentUserInfo() {
      try {
        const response = await authFetch("/user/Info", { method: "GET" });
        const result = await safeReadJson(response);
        if (!response.ok || !result || result.code !== 200 || !result.data) {
          return null;
        }
        return result.data;
      } catch (error) {
        console.error("Failed to fetch current user info:", error);
        return null;
      }
    },
    async applyLoginSession(accessToken, fallbackUserId = "") {
      const token = String(accessToken || "").trim();
      if (!token) {
        return false;
      }

      setAccessToken(token);
      this.initAuthSession();

      const profile = await this.fetchCurrentUserInfo();
      if (profile) {
        this.setUser(profile);
        this.showLogin = false;
        this.fetchHistory(true);
        return true;
      }

      const normalizedId = String(fallbackUserId || "").trim();
      if (!normalizedId) {
        clearAccessToken();
        return false;
      }

      this.setUser({
        id: normalizedId,
        name: `User_${normalizedId}`,
      });
      this.showLogin = false;
      this.fetchHistory(true);
      return true;
    },
    initAppearance() {
      this.applyAppearance();
    },
    applyAppearance() {
      applyBodyAppearance(
        this.darkMode,
        this.themeName,
        this.backgroundImage,
        this.backgroundBlur,
        this.sidebarStyle,
      );
    },
    setDarkMode(darkMode) {
      const next = !!darkMode;
      if (next === this.darkMode) return;

      const update = () => {
        this.darkMode = next;
        try {
          localStorage.setItem(DARK_KEY, this.darkMode ? "dark" : "light");
        } catch (_) {}
        this.applyAppearance();
      };

      const overlayColor = next
        ? "rgba(7, 13, 24, 0.16)"
        : "rgba(255, 250, 244, 0.12)";
      runAppearanceTransition(update, overlayColor);
    },
    toggleDarkMode() {
      this.setDarkMode(!this.darkMode);
    },
    setTheme(themeName) {
      const next = normalizeThemeName(themeName);
      if (next === this.themeName) return;

      const update = () => {
        this.themeName = next;
        try {
          localStorage.setItem(THEME_KEY, this.themeName);
        } catch (_) {}
        this.applyAppearance();
      };

      const overlayColor = this.darkMode
        ? "rgba(7, 13, 24, 0.14)"
        : "rgba(255, 250, 244, 0.1)";
      runAppearanceTransition(update, overlayColor);
    },
    setSidebarStyle(styleName) {
      this.sidebarStyle = SIDEBAR_STYLES.has(styleName) ? styleName : "orbit";
      localStorage.setItem(SIDEBAR_STYLE_KEY, this.sidebarStyle);
      this.applyAppearance();
    },
    setBackgroundImage(dataUrl) {
      const nextBg = dataUrl || "";

      // 清除背景（同步）
      if (!nextBg) {
        this.backgroundImage = "";
        try {
          localStorage.removeItem(BG_KEY);
        } catch (_) {}
        this.applyAppearance();
        return true;
      }

      // 预加载图片，加载成功后再持久化并应用样式，避免在图片未就绪时导致伪元素显示异常或裁剪
      try {
        const img = new Image();
        img.onload = () => {
          this.backgroundImage = nextBg;
          try {
            localStorage.setItem(BG_KEY, this.backgroundImage);
          } catch (err) {
            console.error("保存背景图片失败，可能超出本地存储限制", err);
            // 如果保存失败，不阻止显示，但不持久化
          }
          this.applyAppearance();
        };
        img.onerror = (e) => {
          console.error("背景图片加载失败", e);
          // 加载失败时保持现有背景（或清除）并通知
          this.backgroundImage = "";
          try {
            localStorage.removeItem(BG_KEY);
          } catch (_) {}
          this.applyAppearance();
        };
        // 触发加载
        img.src = nextBg;
      } catch (err) {
        console.error("加载背景图片时出错", err);
        this.backgroundImage = "";
        try {
          localStorage.removeItem(BG_KEY);
        } catch (_) {}
        this.applyAppearance();
        return false;
      }

      return true;
    },
    clearBackgroundImage() {
      this.setBackgroundImage("");
    },
    setBackgroundBlur(blurPx) {
      this.backgroundBlur = clampBackgroundBlur(blurPx);
      localStorage.setItem(BG_BLUR_KEY, String(this.backgroundBlur));
      this.applyAppearance();
    },
    openLogin() {
      this.showLogin = true;
    },
    closeLogin() {
      this.showLogin = false;
    },
    confirmLogout() {
      this.showLogoutConfirm = true;
    },
    closeLogoutConfirm() {
      this.showLogoutConfirm = false;
    },
    setAiChatContext() {
      let nextConversationId = this.lastAiConversationId;
      if (!nextConversationId) {
        nextConversationId = this.chatHistory.find(
          (item) => item?.conversationId,
        )?.conversationId;
      }
      if (!nextConversationId) {
        nextConversationId = createConversationId();
      }

      this.chatMode = "ai";
      this.chatTarget = null;
      this.activeConversationId = nextConversationId;
      this.lastAiConversationId = nextConversationId;
      localStorage.setItem(LAST_AI_CONVERSATION_KEY, this.lastAiConversationId);
      this.setView("chat");
      if (this.conversationCache[nextConversationId]) {
        this.currentMessages = [...this.conversationCache[nextConversationId]];
        this.touchConversationCache(nextConversationId);
        this.persistConversationCache();
      } else {
        this.currentMessages = [];
      }
    },
    openContactChat(type, target) {
      const targetType = type === "group" ? "group" : "user";
      const targetId =
        target?.id ??
        target?.userId ??
        target?.uid ??
        target?.groupId ??
        target?.name ??
        null;

      if (!targetId) return;

      const targetName =
        target?.name ??
        target?.nickname ??
        target?.userName ??
        target?.username ??
        target?.groupId ??
        String(targetId);

      const normalizedTarget = {
        type: targetType,
        id: String(targetId),
        name: String(targetName),
      };
      const key = `${targetType}:${normalizedTarget.id}`;
      const existing = this.userChatSessions[key];
      const stableConversationId = buildContactConversationId(
        targetType,
        this.currentUser?.id,
        normalizedTarget.id,
      );

      if (this.chatMode === "ai" && this.activeConversationId) {
        this.lastAiConversationId = this.activeConversationId;
        localStorage.setItem(
          LAST_AI_CONVERSATION_KEY,
          this.lastAiConversationId,
        );
      }

      this.chatMode = targetType;
      this.chatTarget = normalizedTarget;
      this.setView("chat");

      if (existing?.conversationId) {
        this.activeConversationId =
          stableConversationId || existing.conversationId;
        this.currentMessages = Array.isArray(existing.messages)
          ? [...existing.messages]
          : [];

        this.userChatSessions[key] = {
          ...existing,
          conversationId: this.activeConversationId,
          target: normalizedTarget,
          updatedAt: new Date().toISOString(),
        };
        localStorage.setItem(
          USER_CHAT_SESSIONS_KEY,
          JSON.stringify(this.userChatSessions),
        );
        return;
      }

      const nextConversationId = stableConversationId || createConversationId();
      this.activeConversationId = nextConversationId;
      this.currentMessages = [];
      this.userChatSessions[key] = {
        conversationId: nextConversationId,
        target: normalizedTarget,
        messages: [],
        updatedAt: new Date().toISOString(),
      };
      localStorage.setItem(
        USER_CHAT_SESSIONS_KEY,
        JSON.stringify(this.userChatSessions),
      );
    },
    updateCurrentMessages(messages) {
      const safeMessages = Array.isArray(messages) ? [...messages] : [];
      this.currentMessages = safeMessages;

      if (this.chatMode === "ai") {
        if (!this.activeConversationId) return;

        this.lastAiConversationId = this.activeConversationId;
        localStorage.setItem(
          LAST_AI_CONVERSATION_KEY,
          this.lastAiConversationId,
        );

        this.setConversationCacheEntry(this.activeConversationId, safeMessages);
        return;
      }

      if (!this.chatTarget?.id) return;

      const key = `${this.chatMode}:${this.chatTarget.id}`;
      this.userChatSessions[key] = {
        conversationId: this.activeConversationId || createConversationId(),
        target: this.chatTarget,
        messages: safeMessages,
        updatedAt: new Date().toISOString(),
      };
      localStorage.setItem(
        USER_CHAT_SESSIONS_KEY,
        JSON.stringify(this.userChatSessions),
      );
    },
    setUser(user) {
      const normalized = normalizeUser(user);
      this.currentUser = normalized;
      if (normalized) {
        localStorage.setItem("currentUser", JSON.stringify(normalized));
      } else {
        localStorage.removeItem("currentUser");
      }
    },
    initAuthSession() {
      if (typeof window !== "undefined" && !authTokenListenerInstalled) {
        const store = this;
        authTokenListenerCallback = (event) => {
          store.handleAuthTokenChange(event);
        };
        window.addEventListener(
          AUTH_TOKEN_CHANGE_EVENT,
          authTokenListenerCallback,
        );
        authTokenListenerInstalled = true;
      }

      ensureSessionExpiryTimer(this);
    },
    handleAuthTokenChange() {
      if (!getAccessToken()) {
        clearSessionExpiryTimer();
        if (
          this.currentUser ||
          this.chatHistory.length ||
          this.currentMessages.length
        ) {
          this.resetAuthState();
        }
        return;
      }

      ensureSessionExpiryTimer(this);
    },
    syncViewFromRoute(path) {
      this.currentView = resolveViewFromPath(path);
    },
    setConversationCacheEntry(conversationId, messages) {
      const safeConversationId = String(conversationId || "").trim();
      if (!safeConversationId) return;

      const safeMessages = normalizeConversationMessages(messages);
      this.conversationCache[safeConversationId] = safeMessages;
      this.touchConversationCache(safeConversationId);
      this.pruneConversationCache();
      this.persistConversationCache();
    },
    touchConversationCache(conversationId) {
      const safeConversationId = String(conversationId || "").trim();
      if (!safeConversationId) return;

      const currentOrder = Array.isArray(this.conversationCacheOrder)
        ? this.conversationCacheOrder.filter((item) => {
            const next = String(item || "").trim();
            return next && next !== safeConversationId;
          })
        : [];
      currentOrder.push(safeConversationId);
      this.conversationCacheOrder = currentOrder;
    },
    pruneConversationCache() {
      if (!Array.isArray(this.conversationCacheOrder)) {
        this.conversationCacheOrder = [];
      }

      while (
        this.conversationCacheOrder.length > MAX_CONVERSATION_CACHE_ITEMS
      ) {
        const oldestConversationId = this.conversationCacheOrder.shift();
        if (oldestConversationId) {
          delete this.conversationCache[oldestConversationId];
        }
      }
    },
    persistConversationCache() {
      persistConversationCache(
        this.conversationCache,
        this.conversationCacheOrder,
      );
    },
    resetAuthState() {
      this.setUser(null);
      this.chatHistory = [];
      this.currentMessages = [];
      this.conversationCache = {};
      this.conversationCacheOrder = [];
      this.userChatSessions = {};
      this.chatMode = "ai";
      this.chatTarget = null;
      this.lastAiConversationId = null;
      this.activeConversationId = null;
      this.showLogoutConfirm = false;
      this.showLogin = false;
      this.currentView = "chat";
      localStorage.removeItem("chatHistory");
      localStorage.removeItem(CONVERSATION_CACHE_KEY);
      localStorage.removeItem(CONVERSATION_CACHE_ORDER_KEY);
      localStorage.removeItem(USER_CHAT_SESSIONS_KEY);
      localStorage.removeItem(LAST_AI_CONVERSATION_KEY);
      if (router.currentRoute.value.path !== "/") {
        router.push("/").catch(() => {});
      }
    },
    async logout(options = {}) {
      const { localOnly = false } = options || {};
      clearSessionExpiryTimer();

      if (!localOnly) {
        try {
          await authFetch("/user/logout", { method: "POST" });
        } catch (error) {
          console.warn("Logout request failed:", error);
        }
      }

      clearAccessToken();
      this.resetAuthState();
    },
    async selectConversation(session) {
      if (!session || !session.conversationId) return;

      this.chatMode = "ai";
      this.chatTarget = null;
      this.lastAiConversationId = session.conversationId;
      localStorage.setItem(LAST_AI_CONVERSATION_KEY, this.lastAiConversationId);

      const convId = session.conversationId;

      // 1. 如果点击的是当前已激活的对话，且消息已经存在，绝对拦截
      if (
        this.activeConversationId === convId &&
        this.currentMessages.length > 0
      ) {
        return;
      }

      this.activeConversationId = convId;
      this.setView("chat");

      // 2. 检查 Pinia 内存缓存
      if (
        this.conversationCache[convId] &&
        this.conversationCache[convId].length > 0
      ) {
        this.currentMessages = [...this.conversationCache[convId]];
        this.touchConversationCache(convId);
        this.persistConversationCache();
        return;
      }

      try {
        const response = await authFetch(
          `/user/history/conversation?conversationId=${encodeURIComponent(convId)}`,
          { method: "GET" },
        );
        const result = await safeReadJson(response);
        if (response.ok && result?.code === 200) {
          const rawData = Array.isArray(result.data) ? result.data : [];
          const messages = rawData
            .map((msg) => {
              const msgs = [];
              if (msg.userMessage) {
                msgs.push({
                  role: "user",
                  text: msg.userMessage,
                  createdAt: msg.createdAt,
                });
              }
              if (msg.assistantMessage) {
                msgs.push({
                  role: "assistant",
                  text: msg.assistantMessage,
                  createdAt: msg.createdAt,
                });
              }
              return msgs;
            })
            .flat();

          // 保存并持久化缓存
          this.currentMessages = messages;
          this.setConversationCacheEntry(convId, messages);
        }
      } catch (err) {
        console.error("Failed to fetch conversation detail:", err);
      }
    },
    async fetchHistory(force = false) {
      if (!this.currentUser || !this.currentUser.id) {
        return;
      }
      if (this.loadingHistory) {
        return;
      }

      // 如果不是强制更新，且已经有历史记录了，就不再重复拉取
      if (force !== true && this.chatHistory.length > 0) {
        return;
      }

      this.loadingHistory = true;
      try {
        const response = await authFetch("/user/history", { method: "GET" });
        const result = await safeReadJson(response);

        if (response.status === 401) {
          clearAccessToken();
          this.setUser(null);
          return;
        }

        if (!response.ok || result?.code !== 200) {
          return;
        }

        const nextHistory = Array.isArray(result.data) ? [...result.data] : [];
        nextHistory.sort((a, b) => {
          const ta = new Date(a?.createdAt || 0).getTime();
          const tb = new Date(b?.createdAt || 0).getTime();
          return tb - ta;
        });

        // 保留尚未在后端落地的本地占位会话，避免新建后短暂丢失。
        const optimisticLocal = this.chatHistory.filter((item) => {
          if (!item?.optimistic) return false;
          if (
            nextHistory.some(
              (remote) => remote?.conversationId === item?.conversationId,
            )
          ) {
            return false;
          }

          const cachedMessages = Array.isArray(
            this.conversationCache[item?.conversationId],
          )
            ? this.conversationCache[item.conversationId]
            : [];

          return cachedMessages.some((msg) => String(msg?.text || "").trim());
        });

        this.chatHistory = [...optimisticLocal, ...nextHistory];
        localStorage.setItem("chatHistory", JSON.stringify(this.chatHistory));
      } catch (err) {
        console.error("Failed to fetch history:", err);
      } finally {
        this.loadingHistory = false;
      }
    },
    toggleHighPerf() {
      this.highPerf = !this.highPerf;
    },
    toggleSidebar() {
      if (sidebarAnimating) return;

      const next = !this.isSidebarCollapsed;
      const body = typeof document !== "undefined" ? document.body : null;
      sidebarAnimating = true;

      if (body) {
        body.classList.add("sidebar-animating");
        if (sidebarAnimationTimer) {
          clearTimeout(sidebarAnimationTimer);
        }
      }

      this.isSidebarCollapsed = next;

      sidebarAnimationTimer = setTimeout(() => {
        sidebarAnimating = false;
        if (body) {
          body.classList.remove("sidebar-animating");
        }
      }, 220);
    },
    openModal(modalName) {
      if (modalName === "db") {
        this.setView("db");
        return;
      }

      if (this.activeModal === modalName) {
        // Force re-open so repeated trigger clicks still produce visible feedback.
        this.activeModal = null;
        setTimeout(() => {
          this.activeModal = modalName;
        }, 0);
        return;
      }

      this.activeModal = modalName;
    },
    setView(view) {
      const nextView = view === "db" ? "db" : "chat";
      this.currentView = nextView;

      const targetPath = nextView === "db" ? "/db" : "/";
      if (router.currentRoute.value.path !== targetPath) {
        router.push(targetPath).catch(() => {});
      }
    },
    closeModal() {
      this.activeModal = null;
    },
    shouldRefreshHistoryTitle(conversationId) {
      if (!conversationId) return false;
      const target = this.chatHistory.find(
        (item) => item?.conversationId === conversationId,
      );
      if (!target) return true;
      if (target.optimistic) return true;

      const title = String(target.title || "").trim();
      const summary = String(target.summaryText || "").trim();

      if (!title && !summary) return true;
      if (!title) return true;
      if (title === "新对话" || title === "新会话") return true;
      if (/^对话\s*\d+$/i.test(title)) return true;
      return false;
    },
    setConversationPreviewTitle(conversationId, rawText) {
      if (!conversationId || !rawText) return;

      const index = this.chatHistory.findIndex(
        (item) => item?.conversationId === conversationId,
      );
      if (index < 0) return;

      const currentTitle = String(this.chatHistory[index]?.title || "").trim();
      if (
        currentTitle &&
        currentTitle !== "新对话" &&
        currentTitle !== "新会话" &&
        !/^对话\s*\d+$/i.test(currentTitle)
      ) {
        return;
      }

      const compact = String(rawText).replace(/\s+/g, " ").trim();
      if (!compact) return;

      const preview =
        compact.length > 22 ? `${compact.slice(0, 22)}...` : compact;

      this.chatHistory[index] = {
        ...this.chatHistory[index],
        title: preview,
      };
      localStorage.setItem("chatHistory", JSON.stringify(this.chatHistory));
    },
    ensureAiConversationEntry(conversationId, seedText = "") {
      if (!conversationId) return;

      const exists = this.chatHistory.some(
        (item) => item?.conversationId === conversationId,
      );
      if (exists) return;

      const normalizedText = String(seedText).replace(/\s+/g, " ").trim();
      const title = normalizedText
        ? normalizedText.length > 22
          ? `${normalizedText.slice(0, 22)}...`
          : normalizedText
        : "新对话";

      const summaryText = normalizedText
        ? normalizedText.length > 44
          ? `${normalizedText.slice(0, 44)}...`
          : normalizedText
        : "";

      const optimisticRecord = {
        conversationId,
        title,
        summaryText,
        createdAt: new Date().toISOString(),
        optimistic: true,
      };

      this.chatHistory = [
        optimisticRecord,
        ...this.chatHistory.filter(
          (item) => item?.conversationId !== conversationId,
        ),
      ];
      localStorage.setItem("chatHistory", JSON.stringify(this.chatHistory));
    },
    // 新增：清除当前对话状态
    newConversation() {
      this.chatMode = "ai";
      this.chatTarget = null;

      const cleanedHistory = this.chatHistory.filter((item) => {
        if (!item?.optimistic) return true;

        const title = String(item.title || "").trim();
        const summary = String(item.summaryText || "").trim();
        const isPlaceholderTitle =
          !title ||
          title === "新对话" ||
          title === "新会话" ||
          /^对话\s*\d+$/i.test(title);

        return !(isPlaceholderTitle && !summary);
      });

      if (cleanedHistory.length !== this.chatHistory.length) {
        this.chatHistory = cleanedHistory;
        localStorage.setItem("chatHistory", JSON.stringify(this.chatHistory));
      }

      const nextConversationId = createConversationId();
      this.activeConversationId = nextConversationId;
      this.lastAiConversationId = nextConversationId;
      localStorage.setItem(LAST_AI_CONVERSATION_KEY, this.lastAiConversationId);
      this.currentMessages = [];
      this.setView("chat");
      // 确保点击“新对话”后重新同步后端历史，防止本地历史丢失
      try {
        this.fetchHistory(true);
      } catch (e) {
        // ignore
      }
    },
  },
});
