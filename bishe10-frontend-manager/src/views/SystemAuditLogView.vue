<template>
  <section class="content-stack">
    <div class="audit-summary">
      <article v-for="card in summaryCards" :key="card.label" class="audit-card" :class="card.tone">
        <span>{{ card.label }}</span>
        <strong>{{ formatNumber(card.value) }}</strong>
        <small>{{ card.hint }}</small>
      </article>
    </div>

    <div class="toolbar-panel">
      <el-form :model="query" class="audit-query-form" label-width="72px" @submit.prevent>
        <el-form-item label="关键词">
          <el-input
            v-model.trim="query.keyword"
            clearable
            placeholder="操作人 / 模块 / 行为 / 接口 / IP"
            @keyup.enter="handleSearch"
          >
            <template #prefix>
              <el-icon><Search /></el-icon>
            </template>
          </el-input>
        </el-form-item>

        <el-form-item label="模块">
          <el-select v-model="query.moduleKey" clearable filterable placeholder="全部模块">
            <el-option
              v-for="module in moduleOptions"
              :key="module.value"
              :label="module.label"
              :value="module.value"
            />
          </el-select>
        </el-form-item>

        <el-form-item label="操作者">
          <el-select v-model="query.actorType" clearable placeholder="全部">
            <el-option label="管理员" value="ADMIN" />
            <el-option label="小程序用户" value="USER" />
            <el-option label="访客" value="GUEST" />
          </el-select>
        </el-form-item>

        <el-form-item label="结果">
          <el-select v-model="query.result" clearable placeholder="全部">
            <el-option label="成功" value="success" />
            <el-option label="异常" value="failed" />
          </el-select>
        </el-form-item>

        <el-form-item label="时间">
          <el-date-picker
            v-model="query.timeRange"
            type="datetimerange"
            value-format="YYYY-MM-DD HH:mm:ss"
            start-placeholder="开始时间"
            end-placeholder="结束时间"
            range-separator="至"
            clearable
          />
        </el-form-item>

        <div class="query-actions audit-actions">
          <el-button type="primary" :icon="Search" @click="handleSearch">查询</el-button>
          <el-button :icon="Refresh" @click="handleReset">重置</el-button>
        </div>
      </el-form>
    </div>

    <div class="table-panel">
      <div class="table-header">
        <div>
          <h2>系统操作日志</h2>
          <span>{{ pagination.total }} 条记录</span>
        </div>
        <el-button :icon="Refresh" :loading="loading" @click="loadLogs">刷新</el-button>
      </div>

      <el-table
        v-loading="loading"
        :data="logs"
        row-key="id"
        border
        stripe
        class="user-table"
        empty-text="暂无系统日志"
      >
        <el-table-column prop="createdAt" label="时间" width="172" fixed />
        <el-table-column prop="moduleName" label="模块" width="134" show-overflow-tooltip>
          <template #default="{ row }">
            <el-tag effect="light">{{ row.moduleName || '-' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="actionName" label="行为" min-width="150" show-overflow-tooltip />
        <el-table-column label="操作者" min-width="170" show-overflow-tooltip>
          <template #default="{ row }">
            <div class="actor-cell">
              <el-tag :type="actorTypeTag(row.actorType)" effect="plain" size="small">
                {{ actorTypeText(row.actorType) }}
              </el-tag>
              <span>{{ row.actorName || '-' }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="接口" min-width="260" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="request-path">
              <el-tag size="small" effect="plain">{{ row.httpMethod }}</el-tag>
              {{ row.requestPath }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="结果" width="104" align="center">
          <template #default="{ row }">
            <el-tag :type="row.success ? 'success' : 'danger'" effect="light">
              {{ row.success ? '成功' : '异常' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="statusCode" label="状态码" width="92" align="center" />
        <el-table-column label="耗时" width="98" align="right">
          <template #default="{ row }">{{ formatDuration(row.durationMs) }}</template>
        </el-table-column>
        <el-table-column prop="ipAddress" label="IP" width="138" show-overflow-tooltip />
        <el-table-column prop="message" label="说明" min-width="180" show-overflow-tooltip />
        <el-table-column prop="userAgent" label="客户端" min-width="220" show-overflow-tooltip />
      </el-table>

      <div class="pagination-bar">
        <el-pagination
          v-model:current-page="pagination.pageNum"
          v-model:page-size="pagination.pageSize"
          :total="pagination.total"
          :page-sizes="[10, 20, 50, 100]"
          layout="total, sizes, prev, pager, next, jumper"
          background
          @size-change="loadLogs"
          @current-change="loadLogs"
        />
      </div>
    </div>
  </section>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Refresh, Search } from '@element-plus/icons-vue'
import { listAuditLogs } from '@/api/auditLogs'

const loading = ref(false)
const logs = ref([])
const moduleOptions = ref([])

const query = reactive({
  keyword: '',
  moduleKey: '',
  actorType: '',
  result: '',
  timeRange: []
})

const pagination = reactive({
  pageNum: 1,
  pageSize: 10,
  total: 0
})

const summary = reactive({
  total: 0,
  today: 0,
  success: 0,
  failed: 0,
  authEvents: 0,
  adminEvents: 0
})

const summaryCards = computed(() => [
  { label: '日志总数', value: summary.total, hint: `今日新增 ${formatNumber(summary.today)}`, tone: 'tone-blue' },
  { label: '成功行为', value: summary.success, hint: '状态码小于 400', tone: 'tone-green' },
  { label: '异常行为', value: summary.failed, hint: '失败请求与异常响应', tone: 'tone-red' },
  { label: '登录注册', value: summary.authEvents, hint: '用户与管理员认证', tone: 'tone-violet' },
  { label: '管理操作', value: summary.adminEvents, hint: '后台管理端接口', tone: 'tone-amber' }
])

onMounted(loadLogs)

async function loadLogs() {
  loading.value = true
  try {
    const [startTime, endTime] = Array.isArray(query.timeRange) ? query.timeRange : []
    const data = await listAuditLogs({
      keyword: query.keyword,
      moduleKey: query.moduleKey,
      actorType: query.actorType,
      result: query.result,
      startTime,
      endTime,
      pageNum: pagination.pageNum,
      pageSize: pagination.pageSize
    })
    logs.value = data.records
    pagination.total = data.total
    Object.assign(summary, normalizeSummary(data.summary))
    moduleOptions.value = data.modules || []
  } catch (error) {
    ElMessage.error(error.message || '系统日志加载失败')
  } finally {
    loading.value = false
  }
}

function handleSearch() {
  pagination.pageNum = 1
  loadLogs()
}

function handleReset() {
  query.keyword = ''
  query.moduleKey = ''
  query.actorType = ''
  query.result = ''
  query.timeRange = []
  pagination.pageNum = 1
  loadLogs()
}

function normalizeSummary(data) {
  return {
    total: Number(data?.total || 0),
    today: Number(data?.today || 0),
    success: Number(data?.success || 0),
    failed: Number(data?.failed || 0),
    authEvents: Number(data?.authEvents || 0),
    adminEvents: Number(data?.adminEvents || 0)
  }
}

function actorTypeText(type) {
  if (type === 'ADMIN') return '管理员'
  if (type === 'USER') return '用户'
  return '访客'
}

function actorTypeTag(type) {
  if (type === 'ADMIN') return 'primary'
  if (type === 'USER') return 'success'
  return 'info'
}

function formatNumber(value) {
  const number = Number(value)
  return Number.isFinite(number) ? number.toLocaleString('zh-CN') : '0'
}

function formatDuration(value) {
  const number = Number(value)
  if (!Number.isFinite(number)) return '0ms'
  if (number >= 1000) return `${(number / 1000).toFixed(1)}s`
  return `${number}ms`
}
</script>

<style scoped>
.audit-summary {
  display: grid;
  grid-template-columns: repeat(5, minmax(150px, 1fr));
  gap: 14px;
}

.audit-card {
  min-height: 106px;
  display: flex;
  flex-direction: column;
  justify-content: center;
  gap: 8px;
  padding: 18px;
  border: 1px solid #dbe4ef;
  border-radius: 8px;
  background: #ffffff;
}

.audit-card span {
  color: #64748b;
  font-size: 13px;
}

.audit-card strong {
  color: #111827;
  font-size: 28px;
  line-height: 1;
}

.audit-card small {
  color: #64748b;
  font-size: 12px;
}

.audit-card.tone-blue {
  border-top: 3px solid #0ea5e9;
}

.audit-card.tone-green {
  border-top: 3px solid #22c55e;
}

.audit-card.tone-red {
  border-top: 3px solid #ef4444;
}

.audit-card.tone-violet {
  border-top: 3px solid #8b5cf6;
}

.audit-card.tone-amber {
  border-top: 3px solid #f59e0b;
}

.audit-query-form {
  display: grid;
  grid-template-columns: minmax(240px, 1.3fr) minmax(170px, 0.8fr) minmax(150px, 0.7fr) minmax(130px, 0.6fr) minmax(310px, 1.5fr) auto;
  column-gap: 16px;
  align-items: start;
}

.audit-query-form .el-select,
.audit-query-form .el-date-editor {
  width: 100%;
}

.audit-actions {
  min-width: 156px;
}

.actor-cell {
  min-width: 0;
  display: inline-flex;
  align-items: center;
  gap: 8px;
}

.actor-cell span:last-child,
.request-path {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.request-path {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  max-width: 100%;
}

@media (max-width: 1280px) {
  .audit-summary {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }

  .audit-query-form {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 720px) {
  .audit-summary,
  .audit-query-form {
    grid-template-columns: 1fr;
  }
}
</style>
