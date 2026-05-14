import axios from 'axios'

const http = axios.create({
  baseURL: import.meta.env.VITE_API_BASE || '/api',
  timeout: 15000
})

http.interceptors.request.use((config) => {
  const token = localStorage.getItem('bishe10-manager-token')
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})

http.interceptors.response.use(
  (response) => normalizeResponse(response.data),
  (error) => {
    const status = error.response?.status
    const body = error.response?.data
    const message = body?.message || body?.msg || error.message || '请求失败'

    if (status === 401) {
      localStorage.removeItem('bishe10-manager-token')
      localStorage.removeItem('bishe10-manager-admin')
      window.dispatchEvent(new CustomEvent('bishe10-manager:unauthorized'))
    }

    return Promise.reject(new Error(message))
  }
)

function normalizeResponse(body) {
  if (!body || typeof body !== 'object') return body

  if ('success' in body) {
    if (body.success) return body.data
    return Promise.reject(new Error(body.message || '请求失败'))
  }

  if ('code' in body && Number(body.code) !== 200) {
    return Promise.reject(new Error(body.message || body.msg || '请求失败'))
  }

  return body.data ?? body
}

export function normalizePage(data) {
  if (!data || typeof data !== 'object') {
    return { records: [], total: 0 }
  }

  const records = data.records || data.list || data.items || data.rows || data.content || []
  const total = Number(data.total ?? data.totalElements ?? data.count ?? records.length)

  return { records, total }
}

export default http
