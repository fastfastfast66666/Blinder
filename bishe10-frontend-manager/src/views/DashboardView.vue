<template>
  <section v-loading="loading" class="content-stack dashboard-page">
    <div class="dashboard-toolbar">
      <div>
        <h2>数据总览看板</h2>
        <span>统计时间：{{ formatDateTime(dashboard.generatedAt) }}</span>
      </div>
      <el-button type="primary" :icon="Refresh" :loading="loading" @click="loadDashboard">刷新</el-button>
    </div>

    <el-alert
      v-if="!dashboard.database.available"
      type="warning"
      show-icon
      :closable="false"
      :title="dashboard.database.message || '数据库暂不可用，当前只展示可读取的本地历史数据。'"
    />

    <div class="metric-grid">
      <article v-for="card in metricCards" :key="card.label" class="metric-card" :class="card.tone">
        <div class="metric-head">
          <span class="metric-icon">
            <el-icon><component :is="card.icon" /></el-icon>
          </span>
          <span>{{ card.label }}</span>
        </div>
        <strong>{{ formatNumber(card.value) }}</strong>
        <small>{{ card.hint }}</small>
      </article>
    </div>

    <div class="dashboard-grid">
      <section class="dashboard-panel">
        <div class="panel-header">
          <div>
            <h3>用户状态</h3>
            <span>{{ formatNumber(dashboard.users.total) }} 个小程序用户</span>
          </div>
        </div>
        <div class="progress-list">
          <div v-for="row in userRows" :key="row.label" class="progress-row" :style="{ '--bar-color': row.color }">
            <div class="progress-meta">
              <span>{{ row.label }}</span>
              <strong>{{ formatNumber(row.value) }}</strong>
            </div>
            <div class="mini-progress">
              <span :style="{ width: `${percentage(row.value, row.total)}%` }"></span>
            </div>
          </div>
        </div>
      </section>

      <section class="dashboard-panel">
        <div class="panel-header">
          <div>
            <h3>新闻缓存构成</h3>
            <span>最近更新：{{ dashboard.news.latestUpdatedAt || '-' }}</span>
          </div>
        </div>
        <div class="progress-list">
          <div v-for="row in newsScopeRows" :key="row.key" class="progress-row" :style="{ '--bar-color': row.color }">
            <div class="progress-meta">
              <span>{{ row.label }}</span>
              <strong>{{ formatNumber(row.value) }}</strong>
            </div>
            <div class="mini-progress">
              <span :style="{ width: `${percentage(row.value, row.total)}%` }"></span>
            </div>
          </div>
        </div>
      </section>
    </div>

    <div class="dashboard-grid">
      <section class="dashboard-panel">
        <div class="panel-header">
          <div>
            <h3>用户反馈分布</h3>
            <span>今日新增 {{ formatNumber(dashboard.feedback.today) }} 条</span>
          </div>
        </div>
        <div class="progress-list compact">
          <div v-for="row in feedbackRows" :key="row.key" class="progress-row" :style="{ '--bar-color': row.color }">
            <div class="progress-meta">
              <span>{{ row.label }}</span>
              <strong>{{ formatNumber(row.value) }}</strong>
            </div>
            <div class="mini-progress">
              <span :style="{ width: `${percentage(row.value, row.total)}%` }"></span>
            </div>
          </div>
        </div>
      </section>

      <section class="dashboard-panel">
        <div class="panel-header">
          <div>
            <h3>新闻源状态</h3>
            <span>{{ formatNumber(dashboard.sources.enabled) }} / {{ formatNumber(dashboard.sources.total) }} 个来源启用</span>
          </div>
        </div>
        <div class="source-health">
          <div v-for="row in sourceHealthRows" :key="row.label" class="health-item" :class="row.tone">
            <span>{{ row.label }}</span>
            <strong>{{ formatNumber(row.value) }}</strong>
          </div>
        </div>
        <div class="source-split">
          <span>RSS：{{ formatNumber(dashboard.sources.rss) }}</span>
          <span>搜索源：{{ formatNumber(dashboard.sources.search) }}</span>
          <span>停用：{{ formatNumber(dashboard.sources.disabled) }}</span>
        </div>
      </section>
    </div>

    <div class="dashboard-grid">
      <section class="dashboard-panel">
        <div class="panel-header">
          <div>
            <h3>历史记录类型</h3>
            <span>共 {{ formatNumber(dashboard.history.total) }} 条历史记录</span>
          </div>
        </div>
        <div class="progress-list compact">
          <div v-for="row in historyRows" :key="row.key" class="progress-row" :style="{ '--bar-color': row.color }">
            <div class="progress-meta">
              <span>{{ row.label }}</span>
              <strong>{{ formatNumber(row.value) }}</strong>
            </div>
            <div class="mini-progress">
              <span :style="{ width: `${percentage(row.value, row.total)}%` }"></span>
            </div>
          </div>
        </div>
      </section>

      <section class="dashboard-panel">
        <div class="panel-header">
          <div>
            <h3>个性化数据</h3>
            <span>由用户反馈实时沉淀</span>
          </div>
        </div>
        <div class="profile-grid">
          <div>
            <span>兴趣画像</span>
            <strong>{{ formatNumber(dashboard.personalization.interestProfiles) }}</strong>
          </div>
          <div>
            <span>画像用户</span>
            <strong>{{ formatNumber(dashboard.personalization.profileUsers) }}</strong>
          </div>
          <div>
            <span>屏蔽规则</span>
            <strong>{{ formatNumber(dashboard.personalization.blockRules) }}</strong>
          </div>
          <div>
            <span>活跃会话</span>
            <strong>{{ formatNumber(dashboard.admins.activeSessions) }}</strong>
          </div>
        </div>
      </section>
    </div>

    <section class="table-panel">
      <div class="table-header">
        <div>
          <h2>最近缓存新闻</h2>
          <span>{{ formatNumber(dashboard.news.realArticles) }} 条真实新闻缓存</span>
        </div>
      </div>
      <el-table :data="recentArticles" border stripe class="user-table" empty-text="暂无缓存新闻">
        <el-table-column prop="title" label="标题" min-width="260" show-overflow-tooltip />
        <el-table-column prop="source" label="来源" min-width="140" show-overflow-tooltip />
        <el-table-column label="范围" width="106" align="center">
          <template #default="{ row }">
            <el-tag effect="light">{{ scopeText(row.fetchScope) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="city" label="城市" width="112" show-overflow-tooltip>
          <template #default="{ row }">{{ row.city || row.province || '-' }}</template>
        </el-table-column>
        <el-table-column prop="publishTime" label="发布时间" min-width="170">
          <template #default="{ row }">{{ row.publishTime || row.updatedAt || '-' }}</template>
        </el-table-column>
      </el-table>
    </section>

    <section class="table-panel">
      <div class="table-header">
        <div>
          <h2>新闻源运行概览</h2>
          <span>按优先级展示前 {{ sourceItems.length }} 个来源</span>
        </div>
      </div>
      <el-table :data="sourceItems" border stripe class="user-table" empty-text="暂无新闻源数据">
        <el-table-column prop="sourceName" label="来源名称" min-width="180" show-overflow-tooltip />
        <el-table-column label="类型" width="118" align="center">
          <template #default="{ row }">{{ sourceTypeText(row.sourceType) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="118" align="center">
          <template #default="{ row }">
            <el-tag :type="statusType(row.lastStatus)" effect="light">{{ row.lastStatus || 'INIT' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="启用" width="96" align="center">
          <template #default="{ row }">
            <el-tag :type="row.enabled ? 'success' : 'info'" effect="light">{{ row.enabled ? '启用' : '停用' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="lastFetchAt" label="最近采集" min-width="170">
          <template #default="{ row }">{{ row.lastFetchAt || '-' }}</template>
        </el-table-column>
      </el-table>
    </section>

    <section class="table-panel">
      <div class="table-header">
        <div>
          <h2>最近历史记录</h2>
          <span>来自后端 history.json</span>
        </div>
      </div>
      <el-table :data="latestHistory" border stripe class="user-table" empty-text="暂无历史记录">
        <el-table-column prop="title" label="标题" min-width="220" show-overflow-tooltip />
        <el-table-column label="类型" width="112" align="center">
          <template #default="{ row }">
            <el-tag effect="light">{{ historyTypeText(row.type) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="summary" label="摘要" min-width="280" show-overflow-tooltip />
        <el-table-column prop="time" label="时间" width="150" />
      </el-table>
    </section>
  </section>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import {
  DataAnalysis,
  Document,
  Refresh,
  TrendCharts,
  UserFilled
} from '@element-plus/icons-vue'
import { fetchDashboard } from '@/api/dashboard'

const palette = ['#0ea5e9', '#22c55e', '#f59e0b', '#ef4444', '#8b5cf6', '#14b8a6', '#64748b', '#d946ef']

const loading = ref(false)
const dashboard = ref(defaultDashboard())

const metricCards = computed(() => [
  {
    label: '用户总数',
    value: dashboard.value.users.total,
    hint: `启用 ${formatNumber(dashboard.value.users.enabled)}，停用 ${formatNumber(dashboard.value.users.disabled)}`,
    icon: UserFilled,
    tone: 'tone-blue'
  },
  {
    label: '新闻缓存',
    value: dashboard.value.news.total,
    hint: `今日更新 ${formatNumber(dashboard.value.news.updatedToday)} 条`,
    icon: Document,
    tone: 'tone-green'
  },
  {
    label: '互动反馈',
    value: dashboard.value.feedback.total,
    hint: `覆盖 ${formatNumber(dashboard.value.feedback.users)} 个用户`,
    icon: TrendCharts,
    tone: 'tone-amber'
  },
  {
    label: '历史记录',
    value: dashboard.value.history.total,
    hint: `新闻源启用 ${formatNumber(dashboard.value.sources.enabled)} 个`,
    icon: DataAnalysis,
    tone: 'tone-violet'
  }
])

const userRows = computed(() => {
  const users = dashboard.value.users
  return [
    row('enabled', '启用用户', users.enabled, users.total, '#22c55e'),
    row('disabled', '停用用户', users.disabled, users.total, '#94a3b8'),
    row('activeLast7Days', '近 7 天登录', users.activeLast7Days, users.total, '#0ea5e9'),
    row('newToday', '今日新增', users.newToday, Math.max(numberValue(users.total), numberValue(users.newToday)), '#f59e0b')
  ]
})

const newsScopeRows = computed(() => {
  const total = numberValue(dashboard.value.news.total)
  return normalizeRows(dashboard.value.news.scopeCounts).map((item, index) => ({
    ...item,
    total,
    color: palette[index % palette.length]
  }))
})

const feedbackRows = computed(() => {
  const total = numberValue(dashboard.value.feedback.total)
  return normalizeRows(dashboard.value.feedback.actionCounts).map((item, index) => ({
    ...item,
    total,
    color: palette[(index + 2) % palette.length]
  }))
})

const historyRows = computed(() => {
  const total = numberValue(dashboard.value.history.total)
  return normalizeRows(dashboard.value.history.typeCounts).map((item, index) => ({
    ...item,
    total,
    color: palette[(index + 4) % palette.length]
  }))
})

const sourceHealthRows = computed(() => [
  { label: '正常', value: dashboard.value.sources.healthy, tone: 'success' },
  { label: '等待', value: dashboard.value.sources.pending, tone: 'info' },
  { label: '预警', value: dashboard.value.sources.warning, tone: 'warning' },
  { label: '异常', value: dashboard.value.sources.error, tone: 'danger' }
])

const recentArticles = computed(() => dashboard.value.news.recentArticles || [])
const sourceItems = computed(() => dashboard.value.sources.items || [])
const latestHistory = computed(() => dashboard.value.history.latest || [])

onMounted(loadDashboard)

async function loadDashboard() {
  loading.value = true
  try {
    const data = await fetchDashboard()
    dashboard.value = normalizeDashboard(data)
  } catch (error) {
    ElMessage.error(error.message || '数据总览加载失败')
  } finally {
    loading.value = false
  }
}

function defaultDashboard() {
  return {
    generatedAt: '',
    database: { available: true, message: '' },
    users: { total: 0, enabled: 0, disabled: 0, newToday: 0, activeLast7Days: 0 },
    admins: { total: 0, enabled: 0, activeSessions: 0 },
    news: { total: 0, realArticles: 0, syntheticArticles: 0, updatedToday: 0, latestUpdatedAt: '', scopeCounts: [], recentArticles: [] },
    feedback: { total: 0, today: 0, users: 0, articles: 0, actionCounts: [] },
    sources: { total: 0, enabled: 0, disabled: 0, rss: 0, search: 0, healthy: 0, warning: 0, error: 0, pending: 0, items: [] },
    personalization: { interestProfiles: 0, profileUsers: 0, blockRules: 0 },
    history: { available: true, total: 0, typeCounts: [], latest: [] }
  }
}

function normalizeDashboard(data) {
  const fallback = defaultDashboard()
  const next = data && typeof data === 'object' ? data : {}
  return {
    ...fallback,
    ...next,
    database: { ...fallback.database, ...(next.database || {}) },
    users: { ...fallback.users, ...(next.users || {}) },
    admins: { ...fallback.admins, ...(next.admins || {}) },
    news: { ...fallback.news, ...(next.news || {}) },
    feedback: { ...fallback.feedback, ...(next.feedback || {}) },
    sources: { ...fallback.sources, ...(next.sources || {}) },
    personalization: { ...fallback.personalization, ...(next.personalization || {}) },
    history: { ...fallback.history, ...(next.history || {}) }
  }
}

function normalizeRows(rows) {
  if (!Array.isArray(rows)) return []
  return rows.map((item) => ({
    key: item.key || item.label,
    label: item.label || item.key || '-',
    value: numberValue(item.value)
  }))
}

function row(key, label, value, total, color) {
  return {
    key,
    label,
    value: numberValue(value),
    total: numberValue(total),
    color
  }
}

function numberValue(value) {
  const number = Number(value)
  return Number.isFinite(number) ? number : 0
}

function formatNumber(value) {
  return numberValue(value).toLocaleString('zh-CN')
}

function percentage(value, total) {
  const base = numberValue(total)
  if (base <= 0) return 0
  return Math.min(100, Math.round((numberValue(value) / base) * 100))
}

function formatDateTime(value) {
  if (!value) return '-'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return date.toLocaleString('zh-CN', { hour12: false })
}

function scopeText(scope) {
  const map = {
    CITY: '城市',
    INTEREST: '兴趣',
    PROVINCE: '同省',
    NATIONAL: '全国',
    FALLBACK: '兜底'
  }
  return map[scope] || scope || '-'
}

function sourceTypeText(type) {
  if (type === 'RSS') return 'RSS'
  if (type === 'BAIDU_SEARCH') return '百度搜索'
  if (type === 'SOGOU_SEARCH') return '搜狗搜索'
  return type || '-'
}

function statusType(status) {
  if (status === 'OK') return 'success'
  if (status === 'ERROR') return 'danger'
  if (status === 'EMPTY' || status === 'BLOCKED' || status === 'COOLDOWN') return 'warning'
  return 'info'
}

function historyTypeText(type) {
  const map = {
    vision: '识图',
    voice: '语音',
    news: '新闻',
    weather: '天气',
    generic: '其他'
  }
  return map[type] || type || '其他'
}
</script>

<style scoped>
.dashboard-page {
  min-width: 0;
}

.dashboard-toolbar {
  min-height: 74px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 18px;
  border: 1px solid #dbe4ef;
  border-radius: 8px;
  background: #ffffff;
}

.dashboard-toolbar h2 {
  margin: 0 0 6px;
  font-size: 20px;
  line-height: 1.25;
  color: #111827;
  letter-spacing: 0;
}

.dashboard-toolbar span,
.panel-header span {
  color: #64748b;
  font-size: 13px;
}

.metric-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(180px, 1fr));
  gap: 14px;
}

.metric-card,
.dashboard-panel {
  border: 1px solid #dbe4ef;
  border-radius: 8px;
  background: #ffffff;
}

.metric-card {
  min-height: 128px;
  padding: 18px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.metric-head {
  display: flex;
  align-items: center;
  gap: 10px;
  color: #475569;
  font-size: 14px;
}

.metric-icon {
  width: 34px;
  height: 34px;
  display: grid;
  place-items: center;
  border-radius: 8px;
}

.metric-card strong {
  color: #111827;
  font-size: 31px;
  line-height: 1;
  letter-spacing: 0;
}

.metric-card small {
  color: #64748b;
  font-size: 13px;
}

.tone-blue .metric-icon {
  background: #e0f2fe;
  color: #0284c7;
}

.tone-green .metric-icon {
  background: #dcfce7;
  color: #16a34a;
}

.tone-amber .metric-icon {
  background: #fef3c7;
  color: #d97706;
}

.tone-violet .metric-icon {
  background: #ede9fe;
  color: #7c3aed;
}

.dashboard-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14px;
}

.dashboard-panel {
  padding: 18px;
}

.panel-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 16px;
}

.panel-header h3 {
  margin: 0 0 4px;
  font-size: 17px;
  color: #111827;
  letter-spacing: 0;
}

.progress-list {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.progress-list.compact {
  gap: 11px;
}

.progress-meta {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 7px;
  color: #334155;
  font-size: 13px;
}

.progress-meta strong {
  color: #111827;
}

.mini-progress {
  height: 8px;
  overflow: hidden;
  border-radius: 999px;
  background: #e2e8f0;
}

.mini-progress span {
  display: block;
  height: 100%;
  min-width: 0;
  border-radius: inherit;
  background: var(--bar-color, #0ea5e9);
}

.source-health {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 10px;
}

.health-item {
  min-height: 76px;
  display: flex;
  flex-direction: column;
  justify-content: center;
  gap: 8px;
  padding: 14px;
  border-radius: 8px;
  background: #f8fafc;
}

.health-item span {
  color: #64748b;
  font-size: 13px;
}

.health-item strong {
  font-size: 24px;
  color: #111827;
}

.health-item.success {
  background: #f0fdf4;
}

.health-item.info {
  background: #f8fafc;
}

.health-item.warning {
  background: #fffbeb;
}

.health-item.danger {
  background: #fef2f2;
}

.source-split {
  display: flex;
  flex-wrap: wrap;
  gap: 10px 18px;
  margin-top: 14px;
  color: #475569;
  font-size: 13px;
}

.profile-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 10px;
}

.profile-grid div {
  min-height: 82px;
  display: flex;
  flex-direction: column;
  justify-content: center;
  gap: 8px;
  padding: 14px;
  border-radius: 8px;
  background: #f8fafc;
}

.profile-grid span {
  color: #64748b;
  font-size: 13px;
}

.profile-grid strong {
  color: #111827;
  font-size: 24px;
}

@media (max-width: 1100px) {
  .metric-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .dashboard-grid {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 720px) {
  .dashboard-toolbar {
    align-items: flex-start;
    flex-direction: column;
  }

  .metric-grid,
  .source-health,
  .profile-grid {
    grid-template-columns: 1fr;
  }
}
</style>
