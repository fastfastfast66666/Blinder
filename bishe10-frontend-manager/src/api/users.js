import http, { normalizePage } from './request'
import {
  mockCreateUser,
  mockDeleteUser,
  mockListUsers,
  mockUpdateUser
} from './mock'

const useMock = import.meta.env.VITE_USE_MOCK === 'true'

export async function listUsers(params) {
  const data = useMock ? await mockListUsers(params) : await http.get('/admin/users', { params })
  return normalizePage(data)
}

export function createUser(payload) {
  if (useMock) return mockCreateUser(payload)
  return http.post('/admin/users', payload)
}

export function updateUser(id, payload) {
  if (useMock) return mockUpdateUser(id, payload)
  return http.put(`/admin/users/${id}`, payload)
}

export function deleteUser(id) {
  if (useMock) return mockDeleteUser(id)
  return http.delete(`/admin/users/${id}`)
}
