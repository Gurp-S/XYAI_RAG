import { defineStore } from "pinia";

export const useUiStore = defineStore("ui", {
  state: () => ({
    showLogin: false,
    currentUser: JSON.parse(localStorage.getItem("currentUser")) || null,
    highPerf: false,
    isSidebarCollapsed: false,
    activeModal: null,
    uploadTargetCollection: null,
    isUploading: false,
    uploadProgress: 0,
    uploadStatusText: "",
    currentView: "chat", // 'chat' or 'db'
    chatHistory: JSON.parse(localStorage.getItem("chatHistory")) || [],
    loadingHistory: false,
    activeConversationId: crypto.randomUUID(),
    currentMessages: [],
    showLogoutConfirm: false,
    conversationCache:
      JSON.parse(localStorage.getItem("conversationCache")) || {}, // 持久化缓存内容
  }),
  actions: {
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
    setUser(user) {
      this.currentUser = user;
      if (user) {
        localStorage.setItem("currentUser", JSON.stringify(user));
      } else {
        localStorage.removeItem("currentUser");
      }
      if (user && user.id) {
        this.fetchHistory();
      }
    },
    logout() {
      // 如果需要调用后端登出接口(可选)
      if (this.currentUser && this.currentUser.id) {
        fetch(`/user/logout?userId=${this.currentUser.id}`, { method: "POST" });
      }
      this.setUser(null);
      this.chatHistory = [];
      this.currentMessages = [];
      this.conversationCache = {};
      localStorage.removeItem("chatHistory");
      localStorage.removeItem("conversationCache");
      this.activeConversationId = null;
      this.showLogoutConfirm = false;
    },
    async selectConversation(session) {
      if (!session || !session.conversationId) return;

      const convId = session.conversationId;
      console.log("--- [DEBUG] selectConversation called for:", convId);

      // 1. 如果点击的是当前已激活的对话，且消息已经存在，绝对拦截
      if (
        this.activeConversationId === convId &&
        this.currentMessages.length > 0
      ) {
        console.log("--- [DEBUG] Already active, skipping fetch.");
        return;
      }

      this.activeConversationId = convId;

      // 2. 检查 Pinia 内存缓存
      if (
        this.conversationCache[convId] &&
        this.conversationCache[convId].length > 0
      ) {
        console.log("--- [DEBUG] Found in memory cache, loading...");
        this.currentMessages = this.conversationCache[convId];
        return;
      }

      // 3. 检查 localStorage 缓存（应对刷新页面的情况）
      const localCacheStr = localStorage.getItem("conversationCache");
      if (localCacheStr) {
        try {
          const localCache = JSON.parse(localCacheStr);
          if (localCache[convId] && localCache[convId].length > 0) {
            console.log(
              "--- [DEBUG] Found in local storage, loading and syncing to memory...",
            );
            this.currentMessages = localCache[convId];
            this.conversationCache[convId] = localCache[convId]; // 同步回内存防止下次再查磁盘
            return;
          }
        } catch (e) {
          console.error("LocalCache parse error", e);
        }
      }

      try {
        console.log("--- [DEBUG] No cache found. Fetching from API...");
        const response = await fetch(
          `/user/history/conversation?conversationId=${convId}`,
        );
        const result = await response.json();
        if (result.code === 200) {
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
    fetchHistory(force = false) {
      console.log("--- [DEBUG] fetchHistory called with force:", force);
      if (!this.currentUser || !this.currentUser.id) {
        console.warn("--- [DEBUG] fetchHistory aborted: No current user/ID");
        return;
      }
      if (this.loadingHistory) {
        console.warn("--- [DEBUG] fetchHistory aborted: Already loading");
        return;
      }

      // 如果不是强制更新，且已经有历史记录了，就不再重复拉取
      if (force !== true && this.chatHistory.length > 0) {
        console.log("--- [DEBUG] chatHistory already exists, skipping fetch.");
        return;
      }

      console.log("--- [DEBUG] FETCHING FROM BACKEND - force:", force);
      this.loadingHistory = true;
      fetch(`/user/history?userId=${this.currentUser.id}`)
        .then((res) => res.json())
        .then((result) => {
          console.log("--- [DEBUG] Backend returned history:", result);
          if (result.code === 200) {
            this.chatHistory = result.data || [];
            localStorage.setItem(
              "chatHistory",
              JSON.stringify(this.chatHistory),
            );
          }
        })
        .catch((err) => console.error("Failed to fetch history:", err))
        .finally(() => {
          this.loadingHistory = false;
        });
    },
    toggleHighPerf() {
      this.highPerf = !this.highPerf;
    },
    toggleSidebar() {
      this.isSidebarCollapsed = !this.isSidebarCollapsed;
    },
    openModal(modalName) {
      if (modalName === "db") {
        this.currentView = "db";
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
    // 新增：清除当前对话状态
    newConversation() {
      this.activeConversationId = crypto.randomUUID();
      this.currentMessages = [];
    },
  },
});
