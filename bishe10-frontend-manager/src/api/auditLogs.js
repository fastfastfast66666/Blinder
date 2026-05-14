import http from './request'

export async function listAuditLogs(params) {
  const data = await http.get('/admin/audit-logs', { params })
  const records = data?.records || data?.items || []
  const total = Number(data?.total ?? records.length)
  return {
    records,
    total,
    pageNum: Number(data?.pageNum ?? params?.pageNum ?? 1),
    pageSize: Number(data?.pageSize ?? params?.pageSize ?? 10),
    summary: data?.summary || {},
    modules: data?.modules || []
  }
}
