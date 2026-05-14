import { defineStore } from 'pinia'
import { login as loginApi, logout as logoutApi, fetchCurrentAdmin } from '@/api/auth'

const TOKEN_KEY = 'bishe10-manager-token'
const ADMIN_KEY = 'bishe10-manager-admin'

export const useAuthStore = defineStore('auth', {
  state: () => ({
    token: '',
    admin: null,
    restored: false
  }),
  getters: {
    isAuthenticated: (state) => Boolean(state.token),
    displayName: (state) => state.admin?.nickname || state.admin?.username || '管理员'
  },
  actions: {
    restore() {
      if (this.restored) return
      this.token = localStorage.getItem(TOKEN_KEY) || ''
      const savedAdmin = localStorage.getItem(ADMIN_KEY)
      if (savedAdmin) {
        try {
          this.admin = JSON.parse(savedAdmin)
        } catch {
          this.admin = null
        }
      }
      this.restored = true
    },
    saveSession(token, admin) {
      this.token = token || ''
      this.admin = admin || null
      if (this.token) localStorage.setItem(TOKEN_KEY, this.token)
      else localStorage.removeItem(TOKEN_KEY)

      if (this.admin) localStorage.setItem(ADMIN_KEY, JSON.stringify(this.admin))
      else localStorage.removeItem(ADMIN_KEY)
    },
    async login(payload) {
      const data = await loginApi(payload)
      const token = data.token || data.accessToken || ''
      const admin = data.admin || data.user || { username: payload.username }
      this.saveSession(token, admin)
      return data
    },
    async loadCurrentAdmin() {
      if (!this.token) return null
      const data = await fetchCurrentAdmin()
      const admin = data.admin || data.user || data
      if (admin) this.saveSession(this.token, admin)
      return admin
    },
    async logout() {
      try {
        if (this.token) await logoutApi()
      } finally {
        this.saveSession('', null)
      }
    },
    clear() {
      this.saveSession('', null)
    }
  }
})
