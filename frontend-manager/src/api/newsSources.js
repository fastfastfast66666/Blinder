import http from './request'
import {
  mockListNewsSources,
  mockResetNewsSources,
  mockUpdateNewsSource
} from './mock'

const useMock = import.meta.env.VITE_USE_MOCK === 'true'

export function listNewsSources() {
  if (useMock) return mockListNewsSources()
  return http.get('/admin/news-sources')
}

export function updateNewsSource(sourceKey, payload) {
  if (useMock) return mockUpdateNewsSource(sourceKey, payload)
  return http.put(`/admin/news-sources/${sourceKey}`, payload)
}

export function resetNewsSources() {
  if (useMock) return mockResetNewsSources()
  return http.post('/admin/news-sources/defaults')
}
