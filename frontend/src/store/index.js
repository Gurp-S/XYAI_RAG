import { defineStore } from "pinia";
import { authFetch, safeReadJson } from "../services/api";
import {
  clearAccessToken,
  refreshAccessToken,
  setAccessToken,
} from "../services/auth";

const THEME_KEY = "themeName";
const DARK_KEY = "theme";
const BG_KEY = "userBackgroundImage";
const BG_BLUR_KEY = "userBackgroundBlur";
const SIDEBAR_STYLE_KEY = "sidebarStyle";
const USER_CHAT_SESSIONS_KEY = "userChatSessions";
const LAST_AI_CONVERSATION_KEY = "lastAiConversationId";
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

function clampBackgroundBlur(value) {
  const num = Number(value);
  if (!Number.isFinite(num)) return 0;
  return Math.min(24, Math.max(0, Math.round(num)));
}

function normalizeUser(raw) {
  if (!raw || typeof raw !== "object") return null;

  const id =
    raw.id ??
    raw.userId ??
    raw.uid ??
    raw.userID ??
    raw.accountId ??
    raw.username ??
    null;

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
    name: name ?? String(id),
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
  return current <= normalizedTargetId
    ? `user:${current}:${normalizedTargetId}`
    : `user:${normalizedTargetId}:${current}`;
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
    activeConversationId: crypto.randomUUID(),
    lastAiConversationId:
      localStorage.getItem(LAST_AI_CONVERSATION_KEY) || null,
    currentMessages: [],
    showLogoutConfirm: false,
    darkMode: readDarkMode(),
    themeName: readThemeName(),
    sidebarStyle: readSidebarStyle(),
    backgroundImage: readBackgroundImage(),
    backgroundBlur: readBackgroundBlur(),
    conversationCache:
      JSON.parse(localStorage.getItem("conversationCache")) || {}, // 持久化缓存内容
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
      if (this.chatMode === "ai" && !this.chatTarget) {
        return;
      }

      let nextConversationId = this.lastAiConversationId;
      if (!nextConversationId) {
        nextConversationId = this.chatHistory.find(
          (item) => item?.conversationId,
        )?.conversationId;
      }
      if (!nextConversationId) {
        nextConversationId = crypto.randomUUID();
      }

      this.chatMode = "ai";
      this.chatTarget = null;
      this.activeConversationId = nextConversationId;
      this.lastAiConversationId = nextConversationId;
      localStorage.setItem(LAST_AI_CONVERSATION_KEY, this.lastAiConversationId);
      this.currentMessages = this.conversationCache[nextConversationId]
        ? [...this.conversationCache[nextConversationId]]
        : [];
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
      this.currentView = "chat";

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

      const nextConversationId = stableConversationId || crypto.randomUUID();
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

        this.conversationCache[this.activeConversationId] = safeMessages;
        localStorage.setItem(
          "conversationCache",
          JSON.stringify(this.conversationCache),
        );
        return;
      }

      if (!this.chatTarget?.id) return;

      const key = `${this.chatMode}:${this.chatTarget.id}`;
      this.userChatSessions[key] = {
        conversationId: this.activeConversationId || crypto.randomUUID(),
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
    async logout() {
      try {
        await authFetch("/user/logout", { method: "POST" });
      } catch (error) {
        console.warn("Logout request failed:", error);
      }

      clearAccessToken();
      this.setUser(null);
      this.chatHistory = [];
      this.currentMessages = [];
      this.conversationCache = {};
      this.userChatSessions = {};
      this.chatMode = "ai";
      this.chatTarget = null;
      this.lastAiConversationId = null;
      localStorage.removeItem("chatHistory");
      localStorage.removeItem("conversationCache");
      localStorage.removeItem(USER_CHAT_SESSIONS_KEY);
      localStorage.removeItem(LAST_AI_CONVERSATION_KEY);
      this.activeConversationId = null;
      this.showLogoutConfirm = false;
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

      // 2. 检查 Pinia 内存缓存
      if (
        this.conversationCache[convId] &&
        this.conversationCache[convId].length > 0
      ) {
        this.currentMessages = this.conversationCache[convId];
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
          this.conversationCache[convId] = messages;
          localStorage.setItem(
            "conversationCache",
            JSON.stringify(this.conversationCache),
          );
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
        this.currentView = "db";
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
      this.currentView = view;
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

      const nextConversationId = crypto.randomUUID();
      this.activeConversationId = nextConversationId;
      this.lastAiConversationId = nextConversationId;
      localStorage.setItem(LAST_AI_CONVERSATION_KEY, this.lastAiConversationId);
      this.currentMessages = [];
    },
  },
});
