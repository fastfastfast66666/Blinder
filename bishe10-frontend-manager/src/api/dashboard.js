import http from './request'

export function fetchDashboard() {
  return http.get('/admin/dashboard')
}
