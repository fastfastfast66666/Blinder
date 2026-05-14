import http from './request'
import { mockLogin, mockLogout, mockCurrentAdmin } from './mock'

const useMock = import.meta.env.VITE_USE_MOCK === 'true'

export function login(payload) {
  if (useMock) return mockLogin(payload)
  return http.post('/admin/auth/login', payload)
}

export function fetchCurrentAdmin() {
  if (useMock) return mockCurrentAdmin()
  return http.get('/admin/auth/me')
}

export function logout() {
  if (useMock) return mockLogout()
  return http.post('/admin/auth/logout')
}
