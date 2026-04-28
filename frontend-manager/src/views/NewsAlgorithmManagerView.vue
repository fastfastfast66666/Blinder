<template>
  <section class="content-stack algorithm-page">
    <div class="algorithm-hero">
      <div>
        <el-tag type="warning" effect="light">离线调参沙盒</el-tag>
        <h2>新闻推送算法权重中枢</h2>
        <p>
          面向城市资讯流的召回、排序、个性化和安全阈值预案。当前配置仅作为管理端策略快照，
          不会写入推荐服务、接口或数据库。
        </p>
      </div>
      <div class="hero-actions">
        <el-select v-model="state.scenario" class="scenario-select" @change="applyScenario">
          <el-option
            v-for="item in scenarioOptions"
            :key="item.value"
            :label="item.label"
            :value="item.value"
          />
        </el-select>
        <el-button :icon="Refresh" @click="handleReset">恢复默认</el-button>
        <el-button type="primary" :icon="Finished" @click="saveSnapshot">保存策略快照</el-button>
      </div>
    </div>

    <div class="algo-summary">
      <div v-for="card in summaryCards" :key="card.label" class="algo-metric" :style="{ '--tone': card.tone }">
        <span class="metric-label">{{ card.label }}</span>
        <strong>{{ card.value }}</strong>
        <small>{{ card.hint }}</small>
      </div>
    </div>

    <el-row :gutter="16" align="stretch">
      <el-col :lg="15" :md="24">
        <div class="table-panel algorithm-panel">
          <div class="table-header">
            <div>
              <h2>一级排序权重矩阵</h2>
              <span>归一化总权重 {{ factorTotal }}，用于展示排序策略倾向</span>
            </div>
            <el-button :icon="MagicStick" @click="randomizeFineTune">微调噪声</el-button>
          </div>

          <div class="factor-grid">
            <div v-for="factor in state.factors" :key="factor.key" class="factor-card">
              <div class="factor-head">
                <div>
                  <strong>{{ factor.label }}</strong>
                  <span>{{ factor.desc }}</span>
                </div>
                <el-tag size="small" :type="factorType(factor.group)" effect="light">
                  {{ factor.group }}
                </el-tag>
              </div>
              <div class="factor-control">
                <el-slider v-model="factor.weight" :min="0" :max="100" :step="1" />
                <el-input-number
                  v-model="factor.weight"
                  :min="0"
                  :max="100"
                  :step="1"
                  controls-position="right"
                />
              </div>
              <div class="factor-foot">
                <span>归一占比</span>
                <el-progress
                  :percentage="normalizedPercent(factor.weight)"
                  :stroke-width="8"
                  :show-text="false"
                />
                <b>{{ normalizedPercent(factor.weight) }}%</b>
              </div>
            </div>
          </div>
        </div>
      </el-col>

      <el-col :lg="9" :md="24">
        <div class="table-panel algorithm-panel side-panel">
          <div class="table-header compact">
            <div>
              <h2>全局门控</h2>
              <span>模拟推荐策略的上层约束</span>
            </div>
          </div>

          <div class="gate-list">
            <div class="gate-item">
              <div>
                <strong>个性化强度</strong>
                <span>用户反馈、兴趣画像参与排序的力度</span>
              </div>
              <el-slider v-model="state.global.personalization" :min="0" :max="100" />
            </div>
            <div class="gate-item">
              <div>
                <strong>探索流量</strong>
                <span>给长尾来源和新主题的试探曝光</span>
              </div>
              <el-slider v-model="state.global.exploration" :min="0" :max="35" />
            </div>
            <div class="gate-item">
              <div>
                <strong>时效半衰期</strong>
                <span>热点新闻排序衰减窗口</span>
              </div>
              <el-input-number v-model="state.global.halfLifeHours" :min="1" :max="72" />
            </div>
            <div class="gate-item">
              <div>
                <strong>单源占比上限</strong>
                <span>避免同一新闻源刷屏</span>
              </div>
              <el-input-number v-model="state.global.sourceCap" :min="5" :max="80" />
            </div>
          </div>

          <el-divider />

          <div class="simulation-box">
            <div class="simulation-title">
              <span>离线评估仪表</span>
              <el-button size="small" type="primary" :icon="DataAnalysis" @click="generateReport">
                生成报告
              </el-button>
            </div>
            <div v-for="item in simulationMetrics" :key="item.label" class="sim-row">
              <span>{{ item.label }}</span>
              <el-progress :percentage="item.value" :color="item.color" />
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
              <h2>召回管线编排</h2>
              <span>控制不同候选池在模拟排序中的配额、优先级与冷却窗口</span>
            </div>
          </div>

          <el-table :data="state.channels" border stripe class="user-table">
            <el-table-column label="启用" width="82" align="center">
              <template #default="{ row }">
                <el-switch v-model="row.enabled" />
              </template>
            </el-table-column>
            <el-table-column prop="name" label="候选通道" min-width="150" />
            <el-table-column label="类型" width="110" align="center">
              <template #default="{ row }">
                <el-tag :type="row.type === '实时' ? 'danger' : row.type === '画像' ? 'success' : 'info'" effect="light">
                  {{ row.type }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="配额" min-width="190">
              <template #default="{ row }">
                <el-slider v-model="row.quota" :min="0" :max="60" />
              </template>
            </el-table-column>
            <el-table-column label="优先级" width="120" align="center">
              <template #default="{ row }">
                <el-input-number v-model="row.priority" :min="1" :max="9" size="small" />
              </template>
            </el-table-column>
            <el-table-column label="冷却" width="120" align="center">
              <template #default="{ row }">
                <el-input-number v-model="row.cooldown" :min="0" :max="240" size="small" />
              </template>
            </el-table-column>
          </el-table>
        </div>
      </el-col>

      <el-col :lg="10" :md="24">
        <div class="table-panel algorithm-panel">
          <div class="table-header compact">
            <div>
              <h2>城市画像偏置</h2>
              <span>模拟不同城市主题的加权倾向</span>
            </div>
          </div>

          <div class="city-bias-list">
            <div v-for="item in state.cityBias" :key="item.key" class="city-bias-item">
              <div class="city-bias-title">
                <strong>{{ item.label }}</strong>
                <span>{{ item.value }}</span>
              </div>
              <el-slider v-model="item.value" :min="-40" :max="40" />
            </div>
          </div>
        </div>
      </el-col>
    </el-row>

    <el-row :gutter="16">
      <el-col :lg="12" :md="24">
        <div class="table-panel algorithm-panel">
          <div class="table-header compact">
            <div>
              <h2>安全与去重阈值</h2>
              <span>模拟敏感、重复、低质候选的过滤门槛</span>
            </div>
          </div>

          <div class="guard-grid">
            <div v-for="guard in state.safeguards" :key="guard.key" class="guard-card">
              <div>
                <strong>{{ guard.label }}</strong>
                <span>{{ guard.desc }}</span>
              </div>
              <el-input-number v-model="guard.threshold" :min="guard.min" :max="guard.max" />
            </div>
          </div>
        </div>
      </el-col>

      <el-col :lg="12" :md="24">
        <div class="table-panel algorithm-panel">
          <div class="table-header compact">
            <div>
              <h2>实验灰度开关</h2>
              <span>展示实验分桶和策略灰度，不连接真实用户流量</span>
            </div>
          </div>

          <el-table :data="state.experiments" border stripe class="user-table">
            <el-table-column label="启用" width="76" align="center">
              <template #default="{ row }">
                <el-switch v-model="row.enabled" />
              </template>
            </el-table-column>
            <el-table-column prop="name" label="实验名称" min-width="150" />
            <el-table-column label="灰度" min-width="170">
              <template #default="{ row }">
                <el-slider v-model="row.ratio" :min="0" :max="100" />
              </template>
            </el-table-column>
            <el-table-column label="状态" width="96" align="center">
              <template #default="{ row }">
                <el-tag :type="row.enabled ? 'success' : 'info'" effect="light">
                  {{ row.enabled ? '观察中' : '暂停' }}
                </el-tag>
              </template>
            </el-table-column>
          </el-table>
        </div>
      </el-col>
    </el-row>

    <div class="table-panel algorithm-panel">
      <div class="table-header">
        <div>
          <h2>策略快照与模拟报告</h2>
          <span>最近保存：{{ state.updatedAt || '尚未保存' }}</span>
        </div>
        <el-radio-group v-model="state.simulationMode">
          <el-radio-button label="shadow">影子流量</el-radio-button>
          <el-radio-button label="replay">历史回放</el-radio-button>
          <el-radio-button label="city">城市抽样</el-radio-button>
        </el-radio-group>
      </div>

      <el-table :data="state.reports" border stripe class="user-table" empty-text="暂无模拟报告">
        <el-table-column prop="time" label="生成时间" width="180" />
        <el-table-column prop="scenario" label="策略场景" width="150" />
        <el-table-column prop="recall" label="召回覆盖" width="120" />
        <el-table-column prop="precision" label="排序稳定" width="120" />
        <el-table-column prop="diversity" label="多样性" width="120" />
        <el-table-column prop="note" label="模拟结论" min-width="260" show-overflow-tooltip />
      </el-table>
    </div>
  </section>
</template>

<script setup>
import { computed, onMounted, reactive } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  DataAnalysis,
  Finished,
  MagicStick,
  Refresh
} from '@element-plus/icons-vue'

const STORAGE_KEY = 'bishe10-manager-news-algorithm-sandbox-v1'

const scenarioOptions = [
  { label: '平衡推荐', value: 'balanced' },
  { label: '本地安全优先', value: 'citySafety' },
  { label: '实时热点优先', value: 'realtimeHot' },
  { label: '个性化探索', value: 'personalExplore' }
]

const state = reactive(createDefaultState())

const factorTotal = computed(() => state.factors.reduce((sum, item) => sum + Number(item.weight || 0), 0))

const summaryCards = computed(() => [
  {
    label: '策略版本',
    value: state.updatedAt ? 'SANDBOX-2.7' : 'DRAFT',
    hint: state.updatedAt || '等待保存快照',
    tone: '#0ea5e9'
  },
  {
    label: '个性化强度',
    value: `${state.global.personalization}%`,
    hint: state.global.personalization >= 70 ? '强画像驱动' : '混合排序',
    tone: '#16a34a'
  },
  {
    label: '召回预算',
    value: pipelineQuota.value,
    hint: `${enabledChannels.value} 个通道已启用`,
    tone: '#d97706'
  },
  {
    label: '风险门控',
    value: `${riskGateScore.value}%`,
    hint: riskGateScore.value >= 75 ? '过滤偏严格' : '过滤偏温和',
    tone: '#7c3aed'
  }
])

const enabledChannels = computed(() => state.channels.filter((item) => item.enabled).length)
const pipelineQuota = computed(() => state.channels.filter((item) => item.enabled).reduce((sum, item) => sum + Number(item.quota || 0), 0))

const riskGateScore = computed(() => {
  const values = state.safeguards.map((item) => Number(item.threshold || 0))
  return clamp(Math.round(values.reduce((sum, value) => sum + value, 0) / values.length), 0, 100)
})

const simulationMetrics = computed(() => {
  const recency = findFactor('recency')
  const locality = findFactor('locality')
  const diversity = findFactor('diversity')
  const reliability = findFactor('sourceReliability')
  const personalization = state.global.personalization
  const exploration = state.global.exploration

  return [
    {
      label: '召回覆盖',
      value: clamp(Math.round(52 + pipelineQuota.value * 0.18 + exploration * 0.38), 0, 100),
      color: '#0ea5e9'
    },
    {
      label: '排序稳定',
      value: clamp(Math.round(58 + reliability * 0.32 + riskGateScore.value * 0.18), 0, 100),
      color: '#16a34a'
    },
    {
      label: '本地相关',
      value: clamp(Math.round(45 + locality * 0.45 + personalization * 0.16), 0, 100),
      color: '#d97706'
    },
    {
      label: '内容新鲜',
      value: clamp(Math.round(40 + recency * 0.52 + state.global.halfLifeHours * -0.18), 0, 100),
      color: '#dc2626'
    },
    {
      label: '多样性',
      value: clamp(Math.round(42 + diversity * 0.4 + exploration * 0.42), 0, 100),
      color: '#7c3aed'
    }
  ]
})

onMounted(() => {
  restoreSnapshot()
})

function createDefaultState() {
  return {
    scenario: 'balanced',
    updatedAt: '',
    simulationMode: 'shadow',
    global: {
      personalization: 62,
      exploration: 12,
      halfLifeHours: 18,
      sourceCap: 35
    },
    factors: [
      { key: 'recency', label: '时效衰减', group: '实时性', weight: 78, desc: '发布时间、热度窗口与突发事件加速' },
      { key: 'locality', label: '城市相关', group: '地域', weight: 86, desc: '城市名、区县、交通与社区语义匹配' },
      { key: 'accessibility', label: '无障碍价值', group: '安全', weight: 72, desc: '路口、施工、电梯、天气等行动建议价值' },
      { key: 'sourceReliability', label: '来源可信', group: '质量', weight: 64, desc: '新闻源历史稳定性、抓取状态与权威等级' },
      { key: 'userFeedback', label: '用户反馈', group: '画像', weight: 58, desc: '喜欢、收藏、跳过与不感兴趣反馈' },
      { key: 'diversity', label: '主题多样', group: '探索', weight: 46, desc: '避免同质内容连续出现' },
      { key: 'negativeFilter', label: '负反馈抑制', group: '安全', weight: 54, desc: '屏蔽规则、低质摘要与重复标题惩罚' },
      { key: 'longTailBoost', label: '长尾补偿', group: '探索', weight: 28, desc: '给低曝光但高相关候选少量试探流量' }
    ],
    channels: [
      { key: 'citySearch', name: '城市搜索新闻池', type: '实时', enabled: true, quota: 36, priority: 1, cooldown: 15 },
      { key: 'rssAuthority', name: '权威 RSS 池', type: '权威', enabled: true, quota: 28, priority: 2, cooldown: 30 },
      { key: 'userProfile', name: '用户画像召回', type: '画像', enabled: true, quota: 18, priority: 3, cooldown: 45 },
      { key: 'weatherSafety', name: '天气出行安全池', type: '实时', enabled: true, quota: 12, priority: 2, cooldown: 20 },
      { key: 'provinceExpand', name: '同省扩展池', type: '扩展', enabled: true, quota: 10, priority: 5, cooldown: 60 },
      { key: 'nationalFallback', name: '全国兜底池', type: '扩展', enabled: false, quota: 6, priority: 8, cooldown: 120 }
    ],
    cityBias: [
      { key: 'traffic', label: '交通出行', value: 24 },
      { key: 'weather', label: '天气风险', value: 18 },
      { key: 'community', label: '社区服务', value: 10 },
      { key: 'metro', label: '地铁公交', value: 15 },
      { key: 'policy', label: '政务公告', value: -4 },
      { key: 'social', label: '社会热点', value: -8 }
    ],
    safeguards: [
      { key: 'dedupe', label: '标题去重阈值', desc: '越高越容易判定重复', threshold: 82, min: 40, max: 98 },
      { key: 'lowQuality', label: '低质摘要拦截', desc: '过滤空摘要、广告化标题', threshold: 76, min: 20, max: 95 },
      { key: 'sourceRisk', label: '来源风险门槛', desc: '抓取异常和不稳定来源惩罚', threshold: 68, min: 20, max: 95 },
      { key: 'topicFatigue', label: '主题疲劳阈值', desc: '同主题连续曝光抑制', threshold: 72, min: 20, max: 95 }
    ],
    experiments: [
      { key: 'twoTower', name: '双塔向量召回模拟', enabled: true, ratio: 18 },
      { key: 'bandit', name: '多臂老虎机探索模拟', enabled: false, ratio: 8 },
      { key: 'rerank', name: '二阶段重排模拟', enabled: true, ratio: 24 },
      { key: 'safetyBoost', name: '安全类内容提升模拟', enabled: true, ratio: 35 }
    ],
    reports: []
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
    global: { ...fallback.global, ...(nextState.global || {}) },
    factors: Array.isArray(nextState.factors) ? nextState.factors : fallback.factors,
    channels: Array.isArray(nextState.channels) ? nextState.channels : fallback.channels,
    cityBias: Array.isArray(nextState.cityBias) ? nextState.cityBias : fallback.cityBias,
    safeguards: Array.isArray(nextState.safeguards) ? nextState.safeguards : fallback.safeguards,
    experiments: Array.isArray(nextState.experiments) ? nextState.experiments : fallback.experiments,
    reports: Array.isArray(nextState.reports) ? nextState.reports : fallback.reports
  })
}

function saveSnapshot() {
  state.updatedAt = formatNow()
  localStorage.setItem(STORAGE_KEY, JSON.stringify(toPlain(state)))
  ElMessage.success('策略快照已保存到管理端本地')
}

async function handleReset() {
  await ElMessageBox.confirm('恢复算法权重沙盒默认值？当前本地快照会被清除。', '确认操作', {
    type: 'warning',
    confirmButtonText: '恢复默认',
    cancelButtonText: '取消'
  })
  localStorage.removeItem(STORAGE_KEY)
  replaceState(createDefaultState())
  ElMessage.success('已恢复默认策略模板')
}

function applyScenario(value) {
  const presets = {
    balanced: {
      personalization: 62,
      exploration: 12,
      factor: { recency: 78, locality: 86, accessibility: 72, userFeedback: 58, diversity: 46 }
    },
    citySafety: {
      personalization: 54,
      exploration: 8,
      factor: { recency: 70, locality: 92, accessibility: 94, sourceReliability: 72, negativeFilter: 72 }
    },
    realtimeHot: {
      personalization: 48,
      exploration: 16,
      factor: { recency: 95, locality: 76, accessibility: 60, sourceReliability: 62, diversity: 52 }
    },
    personalExplore: {
      personalization: 82,
      exploration: 26,
      factor: { userFeedback: 86, diversity: 72, longTailBoost: 54, recency: 64, locality: 76 }
    }
  }
  const preset = presets[value]
  if (!preset) return
  state.global.personalization = preset.personalization
  state.global.exploration = preset.exploration
  for (const factor of state.factors) {
    if (preset.factor[factor.key] !== undefined) {
      factor.weight = preset.factor[factor.key]
    }
  }
  ElMessage.success('已套用策略场景')
}

function randomizeFineTune() {
  for (const factor of state.factors) {
    const delta = Math.round(Math.random() * 10) - 5
    factor.weight = clamp(factor.weight + delta, 0, 100)
  }
  ElMessage.success('已生成一组离线微调参数')
}

function generateReport() {
  const metrics = Object.fromEntries(simulationMetrics.value.map((item) => [item.label, item.value]))
  state.reports.unshift({
    time: formatNow(),
    scenario: scenarioOptions.find((item) => item.value === state.scenario)?.label || state.scenario,
    recall: `${metrics['召回覆盖']}%`,
    precision: `${metrics['排序稳定']}%`,
    diversity: `${metrics['多样性']}%`,
    note: buildReportNote(metrics)
  })
  state.reports = state.reports.slice(0, 8)
  saveSnapshot()
}

function buildReportNote(metrics) {
  if (metrics['本地相关'] >= 82 && metrics['排序稳定'] >= 78) {
    return '城市相关性与排序稳定性较高，适合放入下一轮灰度观察。'
  }
  if (metrics['内容新鲜'] >= 82) {
    return '实时热点能力增强，建议观察重复标题和来源集中度。'
  }
  if (metrics['多样性'] >= 75) {
    return '探索流量较高，推荐搭配更严格的低质摘要拦截。'
  }
  return '整体策略处于保守区间，可继续调整召回预算和画像权重。'
}

function factorType(group) {
  if (group === '安全') return 'danger'
  if (group === '画像') return 'success'
  if (group === '探索') return 'warning'
  if (group === '质量') return 'info'
  return 'primary'
}

function normalizedPercent(value) {
  if (!factorTotal.value) return 0
  return Math.round((Number(value || 0) / factorTotal.value) * 100)
}

function findFactor(key) {
  return Number(state.factors.find((item) => item.key === key)?.weight || 0)
}

function clamp(value, min, max) {
  return Math.max(min, Math.min(max, value))
}

function formatNow() {
  const now = new Date()
  const pad = (value) => String(value).padStart(2, '0')
  return `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())} ${pad(now.getHours())}:${pad(now.getMinutes())}:${pad(now.getSeconds())}`
}

function toPlain(value) {
  return JSON.parse(JSON.stringify(value))
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
  background:
    linear-gradient(135deg, rgba(14, 165, 233, 0.1), rgba(20, 184, 166, 0.1)),
    #ffffff;
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

.scenario-select {
  width: 170px;
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

.compact {
  padding-bottom: 8px;
}

.factor-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(260px, 1fr));
  gap: 14px;
  padding: 0 16px 16px;
}

.factor-card,
.guard-card {
  border: 1px solid #dbe4ef;
  border-radius: 8px;
  background: #fbfdff;
  padding: 14px;
}

.factor-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 12px;
}

.factor-head strong,
.gate-item strong,
.guard-card strong {
  display: block;
  margin-bottom: 4px;
  color: #111827;
}

.factor-head span,
.gate-item span,
.guard-card span {
  display: block;
  color: #64748b;
  font-size: 12px;
  line-height: 1.45;
}

.factor-control {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 118px;
  align-items: center;
  gap: 14px;
}

.factor-foot {
  display: grid;
  grid-template-columns: 64px minmax(0, 1fr) 44px;
  align-items: center;
  gap: 10px;
  color: #64748b;
  font-size: 12px;
}

.factor-foot b {
  color: #334155;
  text-align: right;
}

.side-panel {
  height: 100%;
}

.gate-list {
  display: flex;
  flex-direction: column;
  gap: 14px;
  padding: 0 16px;
}

.gate-item {
  border-bottom: 1px solid #eef2f7;
  padding-bottom: 12px;
}

.simulation-box {
  padding: 0 16px 4px;
}

.simulation-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
  color: #111827;
  font-weight: 700;
}

.sim-row {
  display: grid;
  grid-template-columns: 76px minmax(0, 1fr);
  align-items: center;
  gap: 10px;
  margin-bottom: 12px;
  color: #64748b;
  font-size: 13px;
}

.city-bias-list {
  padding: 0 16px 10px;
}

.city-bias-item {
  padding: 8px 0 4px;
  border-bottom: 1px solid #eef2f7;
}

.city-bias-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  color: #334155;
}

.guard-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(220px, 1fr));
  gap: 14px;
  padding: 0 16px 16px;
}

.guard-card {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

@media (max-width: 1100px) {
  .algo-summary,
  .factor-grid,
  .guard-grid {
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
