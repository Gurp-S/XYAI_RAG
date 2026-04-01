import { defineStore } from 'pinia'

export const useUiStore = defineStore('ui', {
  state: () => ({
    showLogin: false,
    currentUser: null,
    highPerf: false,
    isSidebarCollapsed: false,
    activeModal: null
  }),
  actions: {
    openLogin() { this.showLogin = true },
    closeLogin() { this.showLogin = false },
    setUser(user) { this.currentUser = user },
    toggleHighPerf() { this.highPerf = !this.highPerf },
    toggleSidebar() { this.isSidebarCollapsed = !this.isSidebarCollapsed },
    openModal(modalName) { this.activeModal = modalName },
    closeModal() { this.activeModal = null }
  }
})
