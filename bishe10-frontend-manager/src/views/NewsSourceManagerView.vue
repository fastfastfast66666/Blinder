<template>
  <section class="content-stack">
    <div class="source-summary">
      <div class="source-metric">
        <span class="metric-label">新闻源</span>
        <strong>{{ summary.total }}</strong>
      </div>
      <div class="source-metric">
        <span class="metric-label">已启用</span>
        <strong>{{ summary.enabledCount }}</strong>
      </div>
      <div class="source-metric">
        <span class="metric-label">RSS</span>
        <strong>{{ rssCount }}</strong>
      </div>
      <div class="source-metric">
        <span class="metric-label">搜索</span>
        <strong>{{ searchCount }}</strong>
      </div>
    </div>

    <div class="table-panel">
      <div class="table-header">
        <div>
          <h2>新闻源列表</h2>
          <span>{{ summary.enabledCount }} / {{ summary.total }} 个来源参与推荐</span>
        </div>
        <div class="source-actions">
          <el-button :icon="Refresh" @click="loadSources">刷新</el-button>
          <el-button type="primary" :icon="Finished" @click="handleReset">恢复默认源</el-button>
        </div>
      </div>

      <el-alert
        v-if="!summary.enabledCount"
        type="warning"
        show-icon
        :closable="false"
        title="当前没有启用的新闻源，推荐接口将只能使用缓存和系统兜底内容。"
        class="source-alert"
      />

      <el-table
        v-loading="loading"
        :data="sources"
        row-key="sourceKey"
        border
        stripe
        class="user-table"
        empty-text="暂无新闻源配置"
      >
        <el-table-column label="启用" width="92" align="center" fixed>
          <template #default="{ row }">
            <el-switch
              v-model="row.enabled"
              :loading="savingKey === row.sourceKey"
              @change="(value) => handleToggle(row, value)"
            />
          </template>
        </el-table-column>
        <el-table-column prop="sourceName" label="来源名称" min-width="170" show-overflow-tooltip />
        <el-table-column label="类型" width="128" align="center">
          <template #default="{ row }">
            <el-tag :type="row.sourceType === 'RSS' ? 'success' : 'primary'" effect="light">
              {{ sourceTypeText(row.sourceType) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="endpoint" label="接口地址" min-width="260" show-overflow-tooltip />
        <el-table-column label="优先级" width="130" align="center">
          <template #default="{ row }">
            <el-input-number
              v-model="row.priority"
              :min="1"
              :max="999"
              :step="5"
              size="small"
              controls-position="right"
              @change="(value) => handlePriority(row, value)"
            />
          </template>
        </el-table-column>
        <el-table-column label="采集状态" width="120" align="center">
          <template #default="{ row }">
            <el-tag :type="statusType(row.lastStatus)" effect="light">
              {{ row.lastStatus || 'INIT' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="lastFetchAt" label="最近采集" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">{{ formatEmpty(row.lastFetchAt) }}</template>
        </el-table-column>
        <el-table-column prop="lastMessage" label="状态说明" min-width="220" show-overflow-tooltip>
          <template #default="{ row }">{{ row.lastMessage || row.description || '-' }}</template>
        </el-table-column>
      </el-table>
    </div>
  </section>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Finished, Refresh } from '@element-plus/icons-vue'
import {
  listNewsSources,
  resetNewsSources,
  updateNewsSource
} from '@/api/newsSources'

const loading = ref(false)
const savingKey = ref('')
const sources = ref([])

const summary = reactive({
  total: 0,
  enabledCount: 0
})

const rssCount = computed(() => sources.value.filter((item) => item.sourceType === 'RSS').length)
const searchCount = computed(() => sources.value.filter((item) => item.sourceType !== 'RSS').length)

onMounted(loadSources)

async function loadSources() {
  loading.value = true
  try {
    const data = await listNewsSources()
    sources.value = data.items || []
    summary.total = Number(data.total ?? sources.value.length)
    summary.enabledCount = Number(data.enabledCount ?? sources.value.filter((item) => item.enabled).length)
  } catch (error) {
    ElMessage.error(error.message || '新闻源加载失败')
  } finally {
    loading.value = false
  }
}

async function handleToggle(row, enabled) {
  await saveSource(row, { enabled })
}

async function handlePriority(row, priority) {
  await saveSource(row, { priority })
}

async function saveSource(row, payload) {
  const previous = { ...row }
  savingKey.value = row.sourceKey
  try {
    const updated = await updateNewsSource(row.sourceKey, payload)
    Object.assign(row, updated)
    await loadSources()
    ElMessage.success('新闻源配置已保存')
  } catch (error) {
    Object.assign(row, previous)
    ElMessage.error(error.message || '新闻源配置保存失败')
  } finally {
    savingKey.value = ''
  }
}

async function handleReset() {
  await ElMessageBox.confirm('恢复默认新闻源配置？', '确认操作', {
    type: 'warning',
    confirmButtonText: '恢复',
    cancelButtonText: '取消'
  })
  try {
    await resetNewsSources()
    await loadSources()
    ElMessage.success('已恢复默认新闻源')
  } catch (error) {
    ElMessage.error(error.message || '恢复默认新闻源失败')
  }
}

function sourceTypeText(type) {
  if (type === 'RSS') return 'RSS'
  if (type === 'BAIDU_SEARCH') return '百度搜索'
  if (type === 'SOGOU_SEARCH') return '搜狗搜索'
  return type || '-'
}

function statusType(status) {
  if (status === 'OK') return 'success'
  if (status === 'BLOCKED' || status === 'COOLDOWN') return 'warning'
  if (status === 'ERROR') return 'danger'
  if (status === 'EMPTY') return 'info'
  return ''
}

function formatEmpty(value) {
  return value || '-'
}
</script>
