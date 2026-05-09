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
const AI_AVATAR_KEY = "aiAvatar";
const BOT_BUBBLE_KEY = "botBubbleEnabled";
const SIDEBAR_MODE_KEY = "sidebarMode";
const CONVERSATION_CACHE_KEY = "conversationCache";
const CONVERSATION_CACHE_ORDER_KEY = "conversationCacheOrder";
const USER_CHAT_SESSIONS_KEY = "userChatSessions";
const LAST_AI_CONVERSATION_KEY = "lastAiConversationId";
const PINNED_CONVERSATIONS_KEY = "pinnedConversationIds";
const AUTH_TOKEN_CHANGE_EVENT = "xyai:auth-token-change";
const MAX_CONVERSATION_CACHE_ITEMS = 18;
const MAX_CONVERSATION_MESSAGE_COUNT = 60;
const MAX_CONVERSATION_TEXT_LENGTH = 4000;
const MAX_CONVERSATION_ERROR_LENGTH = 2000;
const MAX_CONVERSATION_RAG_ITEMS = 12;
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

function readPinnedConversationIds() {
  const raw = readJsonStorage(PINNED_CONVERSATIONS_KEY, []);
  if (!Array.isArray(raw)) return [];
  return raw
    .map((id) => String(id || "").trim())
    .filter(Boolean)
    .slice(0, 64);
}

function persistPinnedConversationIds(ids) {
  try {
    localStorage.setItem(
      PINNED_CONVERSATIONS_KEY,
      JSON.stringify(Array.isArray(ids) ? ids : []),
    );
  } catch {
    // ignore
  }
}

function applyPinnedOrdering(history, pinnedIds) {
  const list = Array.isArray(history) ? [...history] : [];
  const pins = Array.isArray(pinnedIds)
    ? pinnedIds.map((id) => String(id || "").trim()).filter(Boolean)
    : [];
  if (pins.length === 0) return list;

  const pinSet = new Set(pins);
  const pinnedItems = [];
  const others = [];

  list.forEach((item) => {
    const id = String(item?.conversationId || "").trim();
    if (id && pinSet.has(id)) {
      pinnedItems.push(item);
    } else {
      others.push(item);
    }
  });

  pinnedItems.sort((a, b) => {
    const ia = pins.indexOf(String(a?.conversationId || ""));
    const ib = pins.indexOf(String(b?.conversationId || ""));
    return ia - ib;
  });

  return [...pinnedItems, ...others];
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

let conversationCacheDebounceTimer = null;

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

function schedulePersistConversationCache(store) {
  if (conversationCacheDebounceTimer) {
    clearTimeout(conversationCacheDebounceTimer);
  }
  conversationCacheDebounceTimer = setTimeout(() => {
    conversationCacheDebounceTimer = null;
    persistConversationCache(
      store.conversationCache,
      store.conversationCacheOrder,
    );
  }, 400);
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

function readAiAvatar() {
  return localStorage.getItem(AI_AVATAR_KEY) || "";
}

function readSidebarMode() {
  const val = localStorage.getItem(SIDEBAR_MODE_KEY);
  return val === "fullscreen" ? "fullscreen" : "collapse";
}

function readBotBubbleEnabled() {
  const val = localStorage.getItem(BOT_BUBBLE_KEY);
  return val === null ? true : val === "true";
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
  botBubbleEnabled,
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

  if (botBubbleEnabled === false) {
    body.classList.add("no-bot-bubble");
  } else {
    body.classList.remove("no-bot-bubble");
  }

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
    sidebarMode: readSidebarMode(),
    isFullscreen: false,
    activeModal: null,
    uploadTargetCollection: null,
    isUploading: false,
    uploadProgress: 0,
    uploadStatusText: "",
    currentView: "chat", // 'chat' or 'db'
    chatMode: "ai", // 'ai' | 'user' | 'group'
    chatTarget: null,
    chatHistory: applyPinnedOrdering(
      JSON.parse(localStorage.getItem("chatHistory")) || [],
      readPinnedConversationIds(),
    ),
    loadingHistory: false,
    loadingOlder: false,
    hasMoreMessages: true,
    activeConversationId: createConversationId(),
    lastAiConversationId:
      localStorage.getItem(LAST_AI_CONVERSATION_KEY) || null,
    currentMessages: [],
    showLogoutConfirm: false,
    darkMode: readDarkMode(),
    themeName: readThemeName(),

    botBubbleEnabled: readBotBubbleEnabled(),
    backgroundImage: readBackgroundImage(),
    backgroundBlur: readBackgroundBlur(),
    conversationCache: readConversationCache(),
    conversationCacheOrder: readConversationCacheOrder(readConversationCache()),
    userChatSessions: readJsonStorage(USER_CHAT_SESSIONS_KEY, {}),
    aiAvatar: readAiAvatar(),
    pinnedConversationIds: readPinnedConversationIds(),
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
        this.botBubbleEnabled,
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
    setSidebarMode(mode) {
      this.sidebarMode = mode === "fullscreen" ? "fullscreen" : "collapse";
      localStorage.setItem(SIDEBAR_MODE_KEY, this.sidebarMode);
    },
    setBotBubbleEnabled(val) {
      this.botBubbleEnabled = !!val;
      localStorage.setItem(BOT_BUBBLE_KEY, String(this.botBubbleEnabled));
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
    setAiAvatar(url) {
      this.aiAvatar = url || "";
      try {
        if (this.aiAvatar) {
          localStorage.setItem(AI_AVATAR_KEY, this.aiAvatar);
        } else {
          localStorage.removeItem(AI_AVATAR_KEY);
        }
      } catch (_) {}
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
      // 先用缓存快速显示，再异步请求后端获取最新数据（含 feedback 字段）
      if (this.conversationCache[nextConversationId]) {
        this.currentMessages = [...this.conversationCache[nextConversationId]];
      } else {
        this.currentMessages = [];
      }
      // 异步刷新消息（含 feedback）
      this.refreshCurrentConversation();
    },
    /** 从后端刷新当前对话的消息数据（含 feedback），不影响本地缓存 */
    async refreshCurrentConversation() {
      const convId = this.activeConversationId;
      if (!convId) return;
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
                  id: msg.chatMessageId,
                  role: "user",
                  text: msg.userMessage,
                  createdAt: msg.createdAt,
                  feedback: msg.feedback ?? -1,
                });
              }
              if (msg.assistantMessage) {
                msgs.push({
                  id: msg.chatMessageId,
                  role: "assistant",
                  text: msg.assistantMessage,
                  createdAt: msg.createdAt,
                  feedback: msg.feedback ?? -1,
                });
              }
              return msgs;
            })
            .flat();
          this.currentMessages = messages;
          this.setConversationCacheEntry(convId, messages);
        }
      } catch (err) {
        // 静默失败，不影响已有显示
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
    updateCurrentMessages(messages, options = {}) {
      const incoming = Array.isArray(messages) ? messages : [];
      const preserveRef = Boolean(options && options.preserveRef);
      const safeMessages = Array.isArray(messages) ? [...messages] : [];

      if (!preserveRef) {
        this.currentMessages = safeMessages;
      } else if (this.currentMessages !== incoming) {
        // 允许调用方在原数组上 push/splice，再在此处仅做持久化更新
        this.currentMessages = incoming;
      }

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
      schedulePersistConversationCache(this);
    },
    resetAuthState() {
      // Cancel pending cache persistence
      if (conversationCacheDebounceTimer) {
        clearTimeout(conversationCacheDebounceTimer);
        conversationCacheDebounceTimer = null;
      }
      this.setUser(null);
      this.chatHistory = [];
      this.currentMessages = [];
      this.conversationCache = {};
      this.conversationCacheOrder = [];
      this.userChatSessions = {};
      this.pinnedConversationIds = [];
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
      localStorage.removeItem(PINNED_CONVERSATIONS_KEY);
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
      this.hasMoreMessages = true;
      this.loadingOlder = false;

      // 2. 先用缓存快速显示（如果有），再发请求获取最新数据（含 feedback）
      const cached = this.conversationCache[convId];
      if (cached && cached.length > 0) {
        this.currentMessages = [...cached];
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
                  id: msg.chatMessageId,
                  role: "user",
                  text: msg.userMessage,
                  createdAt: msg.createdAt,
                  feedback: msg.feedback ?? -1,
                });
              }
              if (msg.assistantMessage) {
                msgs.push({
                  id: msg.chatMessageId,
                  role: "assistant",
                  text: msg.assistantMessage,
                  createdAt: msg.createdAt,
                  feedback: msg.feedback ?? -1,
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
    async loadOlderMessages() {
      if (
        this.loadingOlder ||
        !this.hasMoreMessages ||
        !this.activeConversationId
      ) {
        return;
      }
      const messages = this.currentMessages;
      if (!messages || messages.length === 0) {
        return;
      }

      // 取当前最早消息的 createdAt 作为游标
      const oldestMsg = messages[0];
      const cursor = oldestMsg?.createdAt;
      if (!cursor) {
        return;
      }

      this.loadingOlder = true;
      try {
        const response = await authFetch(
          `/user/history/conversation?conversationId=${encodeURIComponent(this.activeConversationId)}&cursor=${encodeURIComponent(cursor)}&limit=20`,
          { method: "GET" },
        );
        const result = await safeReadJson(response);
        if (response.ok && result?.code === 200) {
          const rawData = Array.isArray(result.data) ? result.data : [];
          if (rawData.length === 0) {
            this.hasMoreMessages = false;
            return;
          }

          const olderMessages = rawData
            .map((msg) => {
              const msgs = [];
              if (msg.userMessage) {
                msgs.push({
                  id: `old_${msg.chatMessageId || Date.now()}`,
                  role: "user",
                  text: msg.userMessage,
                  createdAt: msg.createdAt,
                  feedback: msg.feedback ?? -1,
                });
              }
              if (msg.assistantMessage) {
                msgs.push({
                  id: `old_${msg.chatMessageId || Date.now()}`,
                  role: "assistant",
                  text: msg.assistantMessage,
                  createdAt: msg.createdAt,
                  feedback: msg.feedback ?? -1,
                });
              }
              return msgs;
            })
            .flat();

          // 去重（避免与已有消息重复），然后前置到现有消息之前
          const existingIds = new Set(messages.map((m) => m.id));
          const deduped = olderMessages.filter((m) => !existingIds.has(m.id));
          this.currentMessages = [...deduped, ...messages];

          if (rawData.length < 20) {
            this.hasMoreMessages = false;
          }
        }
      } catch (err) {
        console.error("Failed to load older messages:", err);
      } finally {
        this.loadingOlder = false;
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

        const normalizedHistory = nextHistory.map((item) => {
          const entry = { ...(item || {}) };
          let summary = String(entry.summaryText || "");
          if (summary) {
            // 尝试直接解析为 JSON
            try {
              const parsed = JSON.parse(summary);
              if (parsed && typeof parsed === "object") {
                summary =
                  parsed.lastestSummary ??
                  parsed.summary ??
                  parsed.summaryText ??
                  summary;
              }
            } catch (e) {
              // 解析失败时，尝试从字符串中截取第一个 JSON 对象再解析
              try {
                const s = summary.indexOf("{");
                const eIdx = summary.lastIndexOf("}");
                if (s >= 0 && eIdx > s) {
                  const sub = summary.substring(s, eIdx + 1);
                  const parsed2 = JSON.parse(sub);
                  if (parsed2 && typeof parsed2 === "object") {
                    summary =
                      parsed2.lastestSummary ??
                      parsed2.summary ??
                      parsed2.summaryText ??
                      summary;
                  }
                }
              } catch (ignore) {
                // ignore
              }
            }

            const prefix = "历史摘要（仅用于合并去重，不得作为事实新增来源):";
            if (summary.startsWith(prefix)) {
              summary = summary.substring(prefix.length).trim();
            }
          }

          entry.summaryText = summary;
          return entry;
        });

        // 保留既有的非乐观条目以防后端返回空列表（例如查询参数类型错误导致无记录）
        const existingRemote = this.chatHistory.filter((item) => {
          if (item?.optimistic) return false;
          if (
            normalizedHistory.some(
              (r) => r?.conversationId === item?.conversationId,
            )
          )
            return false;
          return true;
        });

        this.chatHistory = applyPinnedOrdering(
          [...existingRemote, ...optimisticLocal, ...normalizedHistory],
          this.pinnedConversationIds,
        );
        localStorage.setItem("chatHistory", JSON.stringify(this.chatHistory));
      } catch (err) {
        console.error("Failed to fetch history:", err);
      } finally {
        this.loadingHistory = false;
      }
    },
    togglePinConversation(conversationId) {
      const convId = String(conversationId || "").trim();
      if (!convId) return;

      const current = Array.isArray(this.pinnedConversationIds)
        ? [...this.pinnedConversationIds]
        : [];
      const exists = current.includes(convId);
      const next = exists
        ? current.filter((id) => id !== convId)
        : [convId, ...current];

      this.pinnedConversationIds = next;
      persistPinnedConversationIds(next);

      this.chatHistory = applyPinnedOrdering(this.chatHistory, next);
      localStorage.setItem("chatHistory", JSON.stringify(this.chatHistory));
    },
    async renameHistory(conversationId, title) {
      const convId = String(conversationId || "").trim();
      const nextTitle = String(title || "").trim();
      if (!convId || !nextTitle) return false;

      const index = this.chatHistory.findIndex(
        (item) => item?.conversationId === convId,
      );
      if (index >= 0) {
        this.chatHistory[index] = {
          ...this.chatHistory[index],
          title: nextTitle,
        };
        localStorage.setItem("chatHistory", JSON.stringify(this.chatHistory));
      }

      try {
        const url = `/user/history/updata?conversationId=${encodeURIComponent(
          convId,
        )}&title=${encodeURIComponent(nextTitle)}`;
        const response = await authFetch(url, { method: "GET" });
        const result = await safeReadJson(response);
        return Boolean(response.ok && result?.code === 200);
      } catch (error) {
        console.error("Failed to rename history:", error);
        return false;
      } finally {
        this.fetchHistory(true);
      }
    },
    async deleteHistory(conversationId) {
      const convId = String(conversationId || "").trim();
      if (!convId) return false;

      // optimistic remove (UI first)
      this.chatHistory = this.chatHistory.filter(
        (item) => item?.conversationId !== convId,
      );
      localStorage.setItem("chatHistory", JSON.stringify(this.chatHistory));

      if (Array.isArray(this.pinnedConversationIds)) {
        this.pinnedConversationIds = this.pinnedConversationIds.filter(
          (id) => id !== convId,
        );
        persistPinnedConversationIds(this.pinnedConversationIds);
      }

      if (this.conversationCache && this.conversationCache[convId]) {
        delete this.conversationCache[convId];
      }
      if (Array.isArray(this.conversationCacheOrder)) {
        this.conversationCacheOrder = this.conversationCacheOrder.filter(
          (id) => id !== convId,
        );
      }
      this.persistConversationCache();

      if (this.activeConversationId === convId) {
        this.newConversation();
      }

      try {
        const url = `/user/history/delete?conversationId=${encodeURIComponent(
          convId,
        )}`;
        const response = await authFetch(url, { method: "GET" });
        const result = await safeReadJson(response);
        return Boolean(response.ok && result?.code === 200);
      } catch (error) {
        console.error("Failed to delete history:", error);
        return false;
      } finally {
        this.fetchHistory(true);
      }
    },
    toggleHighPerf() {
      this.highPerf = !this.highPerf;
    },
    toggleSidebar() {
      if (this.sidebarMode === "fullscreen") {
        // 全屏模式：切换 isFullscreen 状态
        this.isFullscreen = !this.isFullscreen;
        return;
      }
      // 收起模式：现有折叠动画
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
