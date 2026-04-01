import { defineStore } from "pinia";

export const useUiStore = defineStore("ui", {
  state: () => ({
    showLogin: false,
    currentUser: null,
    highPerf: false,
    isSidebarCollapsed: false,
    activeModal: null,
    chatHistory: [],
    loadingHistory: false,
    activeConversationId: null,
    currentMessages: [],
  }),
  actions: {
    openLogin() {
      this.showLogin = true;
    },
    closeLogin() {
      this.showLogin = false;
    },
    setUser(user) {
      this.currentUser = user;
      if (user && user.id) {
        this.fetchHistory();
      }
    },
    async selectConversation(session) {
      if (!session || !session.conversationId) return;
      this.activeConversationId = session.conversationId;
      try {
        const response = await fetch(
          `/user/conversationHistory?conversationId=${session.conversationId}`,
        );
        const result = await response.json();
        if (result.code === 200) {
          // 转换后端消息格式为前端可用格式
          this.currentMessages = result.data
            .map((msg) => [
              { role: "user", text: msg.userMessage, createdAt: msg.createdAt },
              {
                role: "assistant",
                text: msg.assistantMessage,
                createdAt: msg.createdAt,
              },
            ])
            .flat()
            .filter((m) => m.text); // 过滤掉空消息
        }
      } catch (err) {
        console.error("Failed to fetch conversation detail:", err);
      }
    },
    async fetchHistory() {
      if (!this.currentUser || !this.currentUser.id) return;
      this.loadingHistory = true;
      try {
        const response = await fetch(
          `/user/history?userId=${this.currentUser.id}`,
        );
        const result = await response.json();
        if (result.code === 200) {
          this.chatHistory = result.data || [];
        }
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
      this.isSidebarCollapsed = !this.isSidebarCollapsed;
    },
    openModal(modalName) {
      this.activeModal = modalName;
    },
    closeModal() {
      this.activeModal = null;
    },
  },
});
