<template>
  <section class="content-stack algorithm-page">
    <div class="algorithm-hero">
      <div>
        <el-tag type="info" effect="light">本地规则预览</el-tag>
        <h2>新闻推荐规则管理</h2>
        <p>
          本页面用于展示小程序资讯推荐的主要规则，包括城市优先、同省补充、全国兜底、用户反馈和内容过滤。
          当前参数只保存在管理端本地，用于页面展示和论文演示，不会写入后端服务或数据库。
        </p>
      </div>
      <div class="hero-actions">
        <el-select v-model="state.mode" class="mode-select" @change="applyMode">
          <el-option label="普通推荐" value="normal" />
          <el-option label="有用户反馈" value="profile" />
          <el-option label="本地新闻不足" value="fallback" />
        </el-select>
        <el-button :icon="Refresh" @click="handleReset">恢复默认</el-button>
        <el-button type="primary" :icon="Finished" @click="saveSnapshot">保存本地配置</el-button>
      </div>
    </div>

    <div class="algo-summary">
      <div v-for="card in summaryCards" :key="card.label" class="algo-metric" :style="{ '--tone': card.tone }">
        <span>{{ card.label }}</span>
        <strong>{{ card.value }}</strong>
        <small>{{ card.hint }}</small>
      </div>
    </div>

    <el-row :gutter="16" align="stretch">
      <el-col :lg="13" :md="24">
        <div class="table-panel algorithm-panel">
          <div class="table-header">
            <div>
              <h2>候选新闻来源</h2>
              <span>模拟后端推荐时不同新闻池的取数比例</span>
            </div>
          </div>

          <el-table :data="state.sources" border stripe class="user-table">
            <el-table-column label="启用" width="80" align="center">
              <template #default="{ row }">
                <el-switch v-model="row.enabled" />
              </template>
            </el-table-column>
            <el-table-column prop="name" label="来源" min-width="150" />
            <el-table-column prop="scope" label="标识" width="100" align="center">
              <template #default="{ row }">
                <el-tag effect="light">{{ row.scope }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="比例" min-width="190">
              <template #default="{ row }">
                <el-slider v-model="row.ratio" :min="0" :max="60" />
              </template>
            </el-table-column>
            <el-table-column label="说明" min-width="230" show-overflow-tooltip>
              <template #default="{ row }">{{ row.desc }}</template>
            </el-table-column>
          </el-table>
        </div>
      </el-col>

      <el-col :lg="11" :md="24">
        <div class="table-panel algorithm-panel side-panel">
          <div class="table-header">
            <div>
              <h2>排序加分规则</h2>
              <span>接近后端规则打分的主要字段</span>
            </div>
          </div>

          <div class="score-rule-list">
            <div v-for="rule in state.scoreRules" :key="rule.key" class="score-rule">
              <div class="score-rule__text">
                <strong>{{ rule.label }}</strong>
                <span>{{ rule.desc }}</span>
              </div>
              <el-input-number v-model="rule.score" :min="0" :max="30" controls-position="right" />
            </div>
          </div>
        </div>
      </el-col>
    </el-row>

    <el-row :gutter="16">
      <el-col :lg="14" :md="24">
        <div class="table-panel algorithm-panel">
          <div class="table-header">
            <div>
              <h2>用户反馈处理</h2>
              <span>喜欢、收藏、不喜欢等操作对用户画像的模拟影响</span>
            </div>
          </div>

          <el-table :data="state.feedbackRules" border stripe class="user-table">
            <el-table-column prop="label" label="用户操作" min-width="130" />
            <el-table-column prop="action" label="动作值" width="150" align="center" />
            <el-table-column label="标签变化" width="130" align="center">
              <template #default="{ row }">
                <el-input-number v-model="row.tagDelta" :step="0.1" :precision="1" :min="-1" :max="1" size="small" />
              </template>
            </el-table-column>
            <el-table-column label="分类变化" width="130" align="center">
              <template #default="{ row }">
                <el-input-number v-model="row.categoryDelta" :step="0.1" :precision="1" :min="-1" :max="1" size="small" />
              </template>
            </el-table-column>
            <el-table-column label="来源变化" width="130" align="center">
              <template #default="{ row }">
                <el-input-number v-model="row.sourceDelta" :step="0.1" :precision="1" :min="-1" :max="1" size="small" />
              </template>
            </el-table-column>
            <el-table-column prop="desc" label="处理说明" min-width="220" show-overflow-tooltip />
          </el-table>
        </div>
      </el-col>

      <el-col :lg="10" :md="24">
        <div class="table-panel algorithm-panel">
          <div class="table-header">
            <div>
              <h2>过滤与兜底</h2>
              <span>避免重复推荐和空列表的基础规则</span>
            </div>
          </div>

          <div class="filter-list">
            <div v-for="item in state.filters" :key="item.key" class="filter-item">
              <div>
                <strong>{{ item.label }}</strong>
                <span>{{ item.desc }}</span>
              </div>
              <el-switch v-model="item.enabled" />
            </div>
          </div>
        </div>
      </el-col>
    </el-row>

    <div class="table-panel algorithm-panel">
      <div class="table-header">
        <div>
          <h2>推荐结果预览</h2>
          <span>根据当前页面参数计算一条示例新闻的展示分</span>
        </div>
        <el-button :icon="Refresh" @click="nextPreview">换一条示例</el-button>
      </div>

      <div class="preview-box">
        <div class="preview-news">
          <el-tag effect="light">{{ preview.scope }}</el-tag>
          <h3>{{ preview.title }}</h3>
          <p>{{ preview.summary }}</p>
          <div class="preview-meta">
            <span>分类：{{ preview.category }}</span>
            <span>来源：{{ preview.source }}</span>
            <span>发布时间：{{ preview.freshness }}</span>
          </div>
        </div>
        <div class="preview-score">
          <span>模拟分值</span>
          <strong>{{ previewScore }}</strong>
          <small>{{ previewReason }}</small>
        </div>
      </div>
    </div>
  </section>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  Finished,
  Refresh
} from '@element-plus/icons-vue'

const STORAGE_KEY = 'bishe10-manager-news-rule-preview-v1'

const previewIndex = ref(0)
const state = reactive(createDefaultState())

const enabledSources = computed(() => state.sources.filter((item) => item.enabled))
const sourceRatioTotal = computed(() => enabledSources.value.reduce((sum, item) => sum + Number(item.ratio || 0), 0))
const savedStateText = computed(() => (state.updatedAt ? '已保存' : '未保存'))

const summaryCards = computed(() => [
  {
    label: '配置状态',
    value: savedStateText.value,
    hint: state.updatedAt || '仅本地预览',
    tone: '#0ea5e9'
  },
  {
    label: '候选来源',
    value: `${enabledSources.value.length} 类`,
    hint: `启用比例合计 ${sourceRatioTotal.value}%`,
    tone: '#16a34a'
  },
  {
    label: '最高加分',
    value: topScoreRule.value,
    hint: '城市、出行、无障碍等规则',
    tone: '#d97706'
  },
  {
    label: '过滤规则',
    value: `${enabledFilterCount.value} 项`,
    hint: '负反馈、屏蔽和兜底',
    tone: '#7c3aed'
  }
])

const topScoreRule = computed(() => {
  const top = [...state.scoreRules].sort((a, b) => Number(b.score) - Number(a.score))[0]
  return top ? `+${top.score}` : '+0'
})

const enabledFilterCount = computed(() => state.filters.filter((item) => item.enabled).length)

const previewItems = [
  {
    title: '上海地铁多座车站优化无障碍通行服务',
    summary: '相关车站增设出入口引导和无障碍电梯提示，方便特殊群体出行。',
    category: '交通',
    source: '上海发布',
    scope: 'CITY',
    freshness: '6小时内',
    tags: ['交通', '无障碍', '地铁']
  },
  {
    title: '江苏多地发布强降雨出行提醒',
    summary: '降雨天气可能导致路面湿滑，建议市民合理安排出行路线。',
    category: '民生',
    source: '中国新闻网',
    scope: 'PROVINCE',
    freshness: '24小时内',
    tags: ['天气', '交通', '民生']
  },
  {
    title: '全国助残日活动陆续开展',
    summary: '多地围绕无障碍环境建设和志愿服务开展宣传活动。',
    category: '无障碍',
    source: '人民网',
    scope: 'NATIONAL',
    freshness: '3天内',
    tags: ['无障碍', '助残', '服务']
  }
]

const preview = computed(() => previewItems[previewIndex.value % previewItems.length])

const previewScore = computed(() => {
  const item = preview.value
  let score = 0
  if (item.scope === 'CITY') score += ruleScore('city')
  if (item.scope === 'PROVINCE') score += ruleScore('province')
  if (item.scope === 'NATIONAL') score += ruleScore('national')
  if (item.tags.includes('交通')) score += ruleScore('traffic')
  if (item.tags.includes('无障碍')) score += ruleScore('accessibility')
  if (item.category === '民生') score += ruleScore('livelihood')
  if (['人民网', '上海发布', '中国新闻网'].includes(item.source)) score += ruleScore('authority')
  if (item.freshness.includes('6小时') || item.freshness.includes('24小时')) score += ruleScore('freshness')
  return Math.min(100, score)
})

const previewReason = computed(() => {
  if (previewScore.value >= 70) return '适合排在列表前部'
  if (previewScore.value >= 45) return '适合作为普通推荐'
  return '适合作为补充内容'
})

onMounted(() => {
  restoreSnapshot()
})

function createDefaultState() {
  return {
    mode: 'normal',
    updatedAt: '',
    sources: [
      { key: 'city', name: '当前城市新闻池', scope: 'CITY', enabled: true, ratio: 50, desc: '优先展示用户所在城市的新闻' },
      { key: 'province', name: '同省补充新闻池', scope: 'PROVINCE', enabled: true, ratio: 20, desc: '城市新闻不足时补充同省内容' },
      { key: 'national', name: '全国热点新闻池', scope: 'NATIONAL', enabled: true, ratio: 20, desc: '补充全国民生和公共服务新闻' },
      { key: 'interest', name: '兴趣扩展新闻池', scope: 'INTEREST', enabled: false, ratio: 0, desc: '有用户反馈后按标签扩展推荐' },
      { key: 'fallback', name: '兜底内容', scope: 'FALLBACK', enabled: true, ratio: 10, desc: '新闻不足时避免首页空列表' }
    ],
    scoreRules: [
      { key: 'city', label: '城市匹配', score: 25, desc: '新闻城市或标题内容命中当前城市' },
      { key: 'province', label: '同省匹配', score: 14, desc: '新闻与当前省份相关' },
      { key: 'national', label: '全国补充', score: 8, desc: '全国新闻作为补充内容' },
      { key: 'traffic', label: '交通出行', score: 15, desc: '命中地铁、公交、路口、施工、绕行等词' },
      { key: 'accessibility', label: '无障碍相关', score: 15, desc: '命中无障碍、盲道、视障、助残等词' },
      { key: 'livelihood', label: '民生服务', score: 10, desc: '命中社区、医院、便民、公共服务等词' },
      { key: 'authority', label: '权威来源', score: 15, desc: '来自政府、主流媒体或稳定新闻源' },
      { key: 'freshness', label: '发布时间较近', score: 15, desc: '优先展示近期发布的新闻' }
    ],
    feedbackRules: [
      { label: '喜欢', action: 'LIKE', tagDelta: 0.3, categoryDelta: 0.2, sourceDelta: 0.1, desc: '提高相同标签、分类和来源的权重' },
      { label: '收藏', action: 'FAVORITE', tagDelta: 0.5, categoryDelta: 0.3, sourceDelta: 0.2, desc: '比喜欢更强地提高后续推荐倾向' },
      { label: '不喜欢', action: 'DISLIKE', tagDelta: -0.4, categoryDelta: -0.3, sourceDelta: -0.1, desc: '降低同类内容再次出现的概率' },
      { label: '跳过', action: 'SKIP', tagDelta: -0.1, categoryDelta: -0.1, sourceDelta: 0, desc: '轻微降低该类内容权重' },
      { label: '不再推荐类似', action: 'BLOCK_SIMILAR', tagDelta: -1.0, categoryDelta: -0.8, sourceDelta: -0.3, desc: '写入屏蔽规则，后续过滤相似内容' }
    ],
    filters: [
      { key: 'negative', label: '负反馈过滤', enabled: true, desc: '用户不喜欢、跳过或不再推荐类似的新闻不再重复展示' },
      { key: 'blockRule', label: '屏蔽规则过滤', enabled: true, desc: '按标签、分类或来源过滤用户明确屏蔽的内容' },
      { key: 'dedupe', label: '候选新闻去重', enabled: true, desc: '相同标题、链接或内容相近的新闻只保留一条' },
      { key: 'cacheFirst', label: '数据库缓存兜底', enabled: true, desc: '外部新闻源不可用时优先返回数据库已有新闻' }
    ]
  }
}

function restoreSnapshot() {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (!raw) return
    replaceState(JSON.parse(raw))
  } catch {
    localStorage.removeItem(STORAGE_KEY)
  }
}

function replaceState(nextState) {
  const fallback = createDefaultState()
  Object.assign(state, {
    ...fallback,
    ...nextState,
    sources: Array.isArray(nextState.sources) ? nextState.sources : fallback.sources,
    scoreRules: Array.isArray(nextState.scoreRules) ? nextState.scoreRules : fallback.scoreRules,
    feedbackRules: Array.isArray(nextState.feedbackRules) ? nextState.feedbackRules : fallback.feedbackRules,
    filters: Array.isArray(nextState.filters) ? nextState.filters : fallback.filters
  })
}

function applyMode(mode) {
  const presets = {
    normal: {
      sources: { city: 50, province: 20, national: 20, interest: 0, fallback: 10 }
    },
    profile: {
      sources: { city: 40, province: 15, national: 10, interest: 30, fallback: 5 }
    },
    fallback: {
      sources: { city: 35, province: 25, national: 25, interest: 5, fallback: 10 }
    }
  }
  const preset = presets[mode]
  if (!preset) return
  for (const item of state.sources) {
    if (preset.sources[item.key] !== undefined) {
      item.ratio = preset.sources[item.key]
      item.enabled = item.ratio > 0 || item.key === 'fallback'
    }
  }
  ElMessage.success('已切换推荐规则模板')
}

function saveSnapshot() {
  state.updatedAt = formatNow()
  localStorage.setItem(STORAGE_KEY, JSON.stringify(JSON.parse(JSON.stringify(state))))
  ElMessage.success('本地配置已保存')
}

async function handleReset() {
  await ElMessageBox.confirm('恢复默认推荐规则？当前本地配置会被清除。', '确认操作', {
    type: 'warning',
    confirmButtonText: '恢复默认',
    cancelButtonText: '取消'
  })
  localStorage.removeItem(STORAGE_KEY)
  replaceState(createDefaultState())
  previewIndex.value = 0
  ElMessage.success('已恢复默认规则')
}

function nextPreview() {
  previewIndex.value += 1
}

function ruleScore(key) {
  return Number(state.scoreRules.find((item) => item.key === key)?.score || 0)
}

function formatNow() {
  const now = new Date()
  const pad = (value) => String(value).padStart(2, '0')
  return `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())} ${pad(now.getHours())}:${pad(now.getMinutes())}:${pad(now.getSeconds())}`
}
</script>

<style scoped>
.algorithm-page {
  color: #1f2937;
}

.algorithm-hero {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 18px;
  padding: 22px;
  border: 1px solid #dbe4ef;
  border-radius: 8px;
  background: #ffffff;
}

.algorithm-hero h2 {
  margin: 12px 0 8px;
  font-size: 24px;
  color: #111827;
}

.algorithm-hero p {
  max-width: 820px;
  margin: 0;
  color: #64748b;
  line-height: 1.7;
}

.hero-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  justify-content: flex-end;
}

.mode-select {
  width: 160px;
}

.algo-summary {
  display: grid;
  grid-template-columns: repeat(4, minmax(150px, 1fr));
  gap: 14px;
}

.algo-metric {
  min-height: 96px;
  padding: 18px;
  border: 1px solid #dbe4ef;
  border-radius: 8px;
  background: #ffffff;
  box-shadow: inset 4px 0 0 var(--tone);
}

.algo-metric span {
  color: #64748b;
}

.algo-metric strong {
  display: block;
  margin: 8px 0 6px;
  color: #111827;
  font-size: 28px;
  line-height: 1;
}

.algo-metric small {
  color: #64748b;
}

.algorithm-panel {
  padding-bottom: 16px;
}

.algorithm-panel :deep(.el-table) {
  margin: 0 16px 16px;
  width: calc(100% - 32px);
}

.side-panel {
  height: 100%;
}

.score-rule-list,
.filter-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 0 16px 16px;
}

.score-rule,
.filter-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 14px;
  border: 1px solid #dbe4ef;
  border-radius: 8px;
  background: #fbfdff;
}

.score-rule__text strong,
.filter-item strong {
  display: block;
  margin-bottom: 4px;
  color: #111827;
}

.score-rule__text span,
.filter-item span {
  display: block;
  color: #64748b;
  font-size: 12px;
  line-height: 1.45;
}

.preview-box {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 180px;
  gap: 18px;
  padding: 0 16px 16px;
}

.preview-news {
  padding: 18px;
  border: 1px solid #dbe4ef;
  border-radius: 8px;
  background: #fbfdff;
}

.preview-news h3 {
  margin: 12px 0 8px;
  color: #111827;
  font-size: 18px;
}

.preview-news p {
  margin: 0 0 12px;
  color: #475569;
  line-height: 1.7;
}

.preview-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 14px;
  color: #64748b;
  font-size: 13px;
}

.preview-score {
  display: flex;
  flex-direction: column;
  justify-content: center;
  padding: 18px;
  border: 1px solid #dbe4ef;
  border-radius: 8px;
  background: #ffffff;
  text-align: center;
}

.preview-score span,
.preview-score small {
  color: #64748b;
}

.preview-score strong {
  margin: 10px 0;
  color: #0f766e;
  font-size: 42px;
  line-height: 1;
}

@media (max-width: 1100px) {
  .algo-summary,
  .preview-box {
    grid-template-columns: 1fr;
  }

  .algorithm-hero {
    flex-direction: column;
  }

  .hero-actions {
    justify-content: flex-start;
  }
}
</style>
