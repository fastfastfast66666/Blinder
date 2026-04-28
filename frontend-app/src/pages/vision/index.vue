<template>
  <view
    class="page-shell paper-texture"
    @touchstart="handleTouchStart"
    @touchend="handleTouchEnd"
    @touchcancel="resetTouch"
  >
    <view class="page-content" :class="pageMotionClass">

    <!-- Masthead -->
    <view class="masthead">
      <view class="masthead__bar">
        <text class="masthead__brand">VISION · 识图</text>
        <text class="masthead__status" :class="`masthead__status--${stageTone}`">{{ stageStatusText }}</text>
      </view>
      <view class="masthead__rule"></view>
      <text class="masthead__kicker">多模态情境分析 · GLM</text>
      <text class="masthead__title">拍照识别前方场景</text>
    </view>

    <!-- Scene selector (stacked label + options) -->
    <view class="block">
      <view class="block-head">
        <text class="block-no">§01</text>
        <text class="block-title">场景</text>
        <text class="block-meta">{{ currentSceneLabel }}</text>
      </view>
      <view class="scene-row">
        <view
          v-for="item in sceneOptions"
          :key="item.value"
          class="scene-tab"
          :class="{ 'scene-tab--active': selectedScene === item.value }"
          @tap="changeScene(item.value)"
        >
          {{ item.label }}
        </view>
      </view>
    </view>

    <!-- Reference samples (horizontal scroll) -->
    <view class="block">
      <view class="block-head">
        <text class="block-no">§02</text>
        <text class="block-title">参考图</text>
      </view>
      <scroll-view v-if="visibleSampleItems.length" class="sample-strip" scroll-x show-scrollbar="false">
        <view class="sample-strip__row">
          <view
            v-for="item in visibleSampleItems"
            :key="item.key"
            class="sample-item"
            :class="{ 'sample-item--active': activeSampleKey === item.key }"
            @tap="useSample(item)"
          >
            <image class="sample-thumb" :src="item.imageUrl" mode="aspectFill" @error="onSampleImageError(item)" />
            <view class="sample-caption">
              <text class="sample-caption__title">{{ item.title }}</text>
              <text class="sample-caption__hint">{{ item.hint }}</text>
            </view>
          </view>
        </view>
      </scroll-view>
      <view v-else class="sample-empty">
        <text class="sample-empty__title">当前模式不提供参考图</text>
        <text class="sample-empty__hint">请直接拍摄书页、小说、菜单或说明书图片后开始文本阅读</text>
      </view>
    </view>

    <!-- Stage (viewfinder) -->
    <view class="block">
      <view class="block-head">
        <text class="block-no">§03</text>
        <text class="block-title">取景</text>
        <text class="block-meta">{{ currentInputLabel }}</text>
      </view>

      <view
        class="stage-screen"
        :class="{
          'stage-screen--active': isAnalyzing,
          'stage-screen--ready': imageUrl,
        }"
      >
        <image v-if="imageUrl" class="preview-image" :src="imageUrl" mode="aspectFill" />
        <view v-else class="preview-empty">
          <text class="preview-empty__mark">×</text>
          <text class="preview-empty__title">暂无图像</text>
          <text class="preview-empty__hint">选择参考图 · 或拍照 / 选图</text>
        </view>

        <view v-if="isAnalyzing" class="stage-beam"></view>
        <view class="stage-corner stage-corner--tl"></view>
        <view class="stage-corner stage-corner--tr"></view>
        <view class="stage-corner stage-corner--bl"></view>
        <view class="stage-corner stage-corner--br"></view>
      </view>

      <view class="action-row">
        <view
          class="pencil-btn pencil-btn--primary"
          :class="{ 'pencil-btn--disabled': isAnalyzing }"
          @tap="chooseImage"
        >
          <text class="pencil-btn__icon">◎</text>
          <text class="pencil-btn__label">拍照 / 选图</text>
        </view>
        <view
          class="pencil-btn pencil-btn--ghost"
          :class="{ 'pencil-btn--disabled': isAnalyzing }"
          @tap="runDemo"
        >
          <text class="pencil-btn__icon">▣</text>
          <text class="pencil-btn__label">{{ selectedScene === 'text-reading' ? '阅读提示' : '参考图片' }}</text>
        </view>
      </view>
    </view>

    <!-- Streaming console -->
    <view class="block">
      <view class="block-head">
        <text class="block-no">§04</text>
        <text class="block-title">实时分析</text>
        <text class="block-meta">{{ streamState.badgeText || '待识别' }}</text>
      </view>
      <view class="stream-box">
        <text class="stream-text">{{ displayedPreview || '等待分析结果' }}</text><text v-if="showCaret" class="stream-caret">▍</text>
      </view>
    </view>

    <view v-if="lastError" class="notice notice--error">
      <text class="notice__tag">异常</text>
      <text class="notice__copy">{{ lastError }}</text>
    </view>

    <!-- Insight -->
    <view class="block">
      <view class="block-head">
        <text class="block-no">§05</text>
        <text class="block-title">{{ isTextReadingResult ? '识别文本' : '行动建议' }}</text>
        <text v-if="!isTextReadingResult" class="block-meta meta--risk" :class="`meta--risk-${result.safetyLevel}`">
          风险 · {{ riskTextMap[result.safetyLevel] || '提示' }}
        </text>
        <text v-else class="block-meta">全文 · {{ readingMetaText }}</text>
      </view>
      <view class="insight-body">
        <text class="insight-title">{{ isTextReadingResult ? '识别全文' : result.sceneTitle || currentSceneLabel }}</text>
        <text class="insight-text">{{ result.recognizedText }}</text>
      </view>
      <view class="safety-line" :class="`safety-line--${result.safetyLevel}`">
        <text class="safety-line__bar"></text>
        <text class="safety-line__copy">{{ safetyCopy }}</text>
      </view>
    </view>

    <!-- Broadcast -->
    <view class="block">
      <view class="block-head">
        <text class="block-no">§06</text>
        <text class="block-title">{{ isTextReadingResult ? '朗读文稿' : '播报' }}</text>
        <view
          class="mini-btn"
          :class="{ 'mini-btn--speaking': speakingVoice }"
          @tap="speakResult"
        >
          {{ speakingVoice ? '■ 停止' : isTextReadingResult ? '♪ 朗读全文' : '♪ 朗读' }}
        </view>
      </view>
      <text class="broadcast-quote">{{ readingScriptText }}</text>
    </view>

    <!-- Tips -->
    <view v-if="result.sceneTips && result.sceneTips.length" class="block">
      <view class="block-head">
        <text class="block-no">§07</text>
        <text class="block-title">{{ isTextReadingResult ? '拍摄建议' : '重点' }}</text>
      </view>
      <view class="tips-list">
        <view v-for="(item, index) in result.sceneTips" :key="index" class="tip-line">
          <text class="tip-line__idx">0{{ index + 1 }}</text>
          <text class="tip-line__text">{{ item }}</text>
        </view>
      </view>
    </view>

    </view>
    <bottom-dock active="vision" />
  </view>
</template>

<script>
import BottomDock from '../../components/BottomDock.vue'
import {
  canStreamVision,
  getVisionSamples,
  playSpeech,
  stopSpeech,
  request,
  resolveUrl,
  stopVisionStream,
  streamVisionJson,
  streamVisionUpload,
  uploadVisionImage,
} from '../../utils/api'
import {
  consumeTabTransition,
  getSwipeTargetIndex,
  navigateToTab,
} from '../../utils/tabNavigation'

const sceneLabelMap = {
  general: '通用场景',
  crossroad: '十字路口',
  supermarket: '超市货架',
  'text-reading': '文本阅读',
}

const safetyCopyMap = {
  low: '当前画面以路径引导为主，优先保持直线行进并留意周边动态变化。',
  medium: '建议放慢脚步，先确认左右环境和前方障碍，再按照播报内容逐步行动。',
  high: '当前场景存在较高风险，请优先停步确认环境，再决定是否继续移动。',
}

const sceneFallbackMap = {
  general: {
    recognizedText: '识别结果：前方道路基本可通行，右前方可能存在台阶或门槛，建议放慢速度。',
    readingText: '前方道路基本可通行，右前方可能有台阶，请减速并留意脚下。',
    sceneTips: [
      '优先提示障碍物、门口和台阶',
      '尽量给出相对方向和距离感',
      '可继续接入语音播报与多轮问答',
    ],
    voiceBroadcast: '前方道路基本可通行，右前方可能有台阶，请减速并留意脚下。',
    safetyLevel: 'medium',
    sceneTitle: '通用环境识别',
  },
  crossroad: {
    recognizedText: '识别结果：前方为路口与斑马线区域，请先确认红绿灯和车流方向，再判断是否直行通过。',
    readingText: '前方是路口和斑马线，请先确认红绿灯和车流，再安全通过。',
    sceneTips: [
      '优先播报红绿灯和车流方向',
      '强调围挡、转弯和路口风险',
      '建议句式保持简短直接',
    ],
    voiceBroadcast: '前方是路口和斑马线，请先确认红绿灯和车流，再安全通过。',
    safetyLevel: 'high',
    sceneTitle: '十字路口识别',
  },
  supermarket: {
    recognizedText: '识别结果：当前位置位于货架通道，左侧为饮料货架，右侧保留一人宽通道，可缓慢继续前进。',
    readingText: '前方是货架区域，左侧为饮料货架，右侧通道可继续前进。',
    sceneTips: [
      '先说明货架类别和通行方向',
      '优先播报购物篮和促销台',
      '尽量避免无关装饰描述',
    ],
    voiceBroadcast: '前方是货架区域，左侧为饮料货架，右侧通道可继续前进。',
    safetyLevel: 'medium',
    sceneTitle: '超市货架识别',
  },
  'text-reading': {
    recognizedText: '当前是文本阅读模式。请拍摄书页、小说、说明书或告示牌，并尽量让文字完整、端正、清晰地进入画面。',
    readingText: '请上传需要朗读的文字图片，例如书页、菜单、说明书或小说页面。我会先提取全文，再整理断句后进行语音朗读。',
    sceneTips: [
      '尽量单页拍摄减少遮挡',
      '保持画面端正并避免反光',
      '识别后可直接语音朗读',
    ],
    voiceBroadcast: '请上传需要朗读的文字图片。我会先提取全文，再整理断句后进行语音朗读。',
    safetyLevel: 'low',
    sceneTitle: '文本阅读',
  },
}

const manualOnlyScenes = new Set(['text-reading'])

function buildFallbackResult(scene = 'general') {
  const base = sceneFallbackMap[scene] || sceneFallbackMap.general
  const recognizedText = base.recognizedText || ''
  return {
    scene,
    recognizedText,
    readingText: base.readingText || base.voiceBroadcast || recognizedText,
    sceneTips: base.sceneTips || [],
    voiceBroadcast: base.voiceBroadcast || base.readingText || recognizedText,
    safetyLevel: base.safetyLevel || 'medium',
    analysisMode: 'template-fallback',
    provider: 'LLM API',
    model: 'generic',
    sceneTitle: base.sceneTitle || sceneLabelMap[scene] || sceneLabelMap.general,
    textLength: recognizedText.replace(/\s+/g, '').length,
  }
}

function buildSamplePlaceholder(title = '参考图') {
  const safeTitle = String(title || '参考图')
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="720" height="480" viewBox="0 0 720 480"><rect width="720" height="480" fill="#f1ede3"/><rect x="24" y="24" width="672" height="432" fill="none" stroke="#2b2b2b" stroke-width="4"/><text x="360" y="220" text-anchor="middle" font-size="42" font-weight="700" fill="#2b2b2b">${safeTitle}</text><text x="360" y="278" text-anchor="middle" font-size="24" fill="#7a756a">图片加载失败，已使用占位图</text></svg>`
  return `data:image/svg+xml;utf8,${encodeURIComponent(svg)}`
}

const fallbackSamples = [
  {
    key: 'crosswalk-demo',
    title: '十字路口参考图',
    scene: 'crossroad',
    hint: '适合识别路口、斑马线与风险播报。',
    imageUrl: resolveUrl('/api/vision/samples/crosswalk-demo/image'),
    fallbackImageUrl: buildSamplePlaceholder('十字路口参考图'),
  },
  {
    key: 'supermarket-demo',
    title: '超市货架参考图',
    scene: 'supermarket',
    hint: '适合识别货架通道、购物车和绕行提示。',
    imageUrl: resolveUrl('/api/vision/samples/supermarket-demo/image'),
    fallbackImageUrl: buildSamplePlaceholder('超市货架参考图'),
  },
]

function extractErrorText(error) {
  if (!error) return '识图请求失败，请稍后重试'
  if (typeof error === 'string') return error
  if (typeof error.errMsg === 'string' && error.errMsg) return error.errMsg
  if (typeof error.message === 'string' && error.message) return error.message
  if (typeof error.msg === 'string' && error.msg) return error.msg
  return '识图请求失败，请检查网络、图片上传权限或运行环境域名配置'
}

function createInitialStreamState(scene = 'general') {
  return {
    badgeText: scene === 'text-reading' ? '流式识文' : '流式识图',
    phase: 'idle',
    phaseLabel: '等待开始',
    phaseMessage: '等待开始',
    preview: '',
  }
}

function resolvePhaseLabel(phase) {
  switch (phase) {
    case 'queued':
      return '已接收'
    case 'compress':
      return '压缩中'
    case 'cached':
      return '缓存返回'
    case 'model':
      return '分析中'
    case 'polish':
      return '整理中'
    case 'fallback':
      return '参考结果'
    default:
      return '已完成'
  }
}

function createPhaseSteps(phase, hasError) {
  const currentKey = hasError ? 'fallback' : phase || 'idle'
  const order = {
    idle: 0,
    queued: 1,
    compress: 1,
    model: 2,
    polish: 3,
    cached: 3,
    done: 3,
    fallback: 3,
  }
  const currentIndex = order[currentKey] ?? 0

  return [
    { key: 'queued', label: '接收任务' },
    { key: 'model', label: '流式分析' },
    { key: 'done', label: hasError ? '参考结果' : '生成结果' },
  ].map((item, index) => {
    let state = 'idle'
    if (index + 1 < currentIndex) {
      state = 'done'
    } else if (index + 1 === currentIndex) {
      state = currentKey === 'fallback' ? 'fallback' : 'active'
    }
    if (currentKey === 'done' && item.key === 'done') {
      state = 'done'
    }
    if (currentKey === 'fallback' && item.key === 'done') {
      state = 'fallback'
    }
    return {
      ...item,
      state,
    }
  })
}

export default {
  components: {
    BottomDock,
  },
  data() {
    return {
      sceneOptions: [
        { label: '通用场景', value: 'general' },
        { label: '十字路口', value: 'crossroad' },
        { label: '超市货架', value: 'supermarket' },
        { label: '文本阅读', value: 'text-reading' },
      ],
      selectedScene: 'general',
      imageUrl: '',
      selectedUploadFile: null,
      selectedUploadScene: '',
      sampleItems: fallbackSamples,
      activeSampleKey: '',
      result: buildFallbackResult('general'),
      lastError: '',
      isAnalyzing: false,
      speakingVoice: false,
      speechLoadingVisible: false,
      streamState: createInitialStreamState('general'),
      riskTextMap: {
        low: '低风险',
        medium: '中风险',
        high: '高风险',
      },
      pageMotionClass: '',
      touchStartPoint: null,
      displayedPreview: '',
      typingTimer: null,
      targetPreview: '',
      caretBlinkTimer: null,
      caretVisible: true,
      analysisRunId: 0,
    }
  },
  computed: {
    currentSceneLabel() {
      return sceneLabelMap[this.selectedScene] || sceneLabelMap.general
    },
    visibleSampleItems() {
      if (this.selectedScene === 'general') return this.sampleItems
      return this.sampleItems.filter((item) => item.scene === this.selectedScene)
    },
    currentSample() {
      return this.sampleItems.find((item) => item.key === this.activeSampleKey) || null
    },
    isTextReadingResult() {
      return (this.result.scene || this.selectedScene) === 'text-reading'
    },
    stageTitle() {
      if (this.currentSample) return this.currentSample.title
      if (this.imageUrl) return '当前图像已载入，可直接开始识图分析'
      return '等待上传现场图片或选择参考图片'
    },
    stageStatusText() {
      if (this.isAnalyzing) return '实时处理中'
      if (this.lastError) return '已切换到参考结果'
      if (this.selectedScene === 'text-reading' && !this.imageUrl) return '等待文字图片'
      if (this.imageUrl) return '准备完成'
      return '等待输入'
    },
    stageTone() {
      if (this.lastError) return 'fallback'
      if (this.isAnalyzing) return 'live'
      if (this.imageUrl) return 'ready'
      return 'idle'
    },
    currentInputLabel() {
      if (this.currentSample) return `参考图片 · ${this.currentSample.title}`
      if (this.imageUrl) return '现场图片'
      if (this.selectedScene === 'text-reading') return '等待文字图片'
      return '等待图片'
    },
    outputModeLabel() {
      return this.result.analysisMode === 'llm-vision' ? '流式识图' : '参考结果'
    },
    voiceReadyLabel() {
      return this.result.voiceBroadcast ? '可朗读' : '待生成'
    },
    consoleTone() {
      if (this.lastError) return 'fallback'
      if (this.isAnalyzing) return 'live'
      if (this.result.analysisMode === 'llm-vision') return 'done'
      return 'idle'
    },
    streamSteps() {
      return createPhaseSteps(this.streamState.phase, Boolean(this.lastError))
    },
    safetyCopy() {
      if (this.isTextReadingResult) {
        return '系统会尽量按阅读顺序提取全文，并整理断句后再朗读。开始播放前，可先快速检查是否存在漏字或反光区域。'
      }
      return safetyCopyMap[this.result.safetyLevel] || safetyCopyMap.medium
    },
    readingScriptText() {
      return this.result.readingText || this.result.voiceBroadcast || this.result.recognizedText || ''
    },
    readingMetaText() {
      const length = Number(this.result.textLength) || 0
      return length > 0 ? `${length} 字` : '待识别'
    },
    showCaret() {
      // Show blinking caret during streaming, or when preview has content that's still being typed.
      if (this.isAnalyzing) return this.caretVisible
      if (this.displayedPreview && this.displayedPreview !== this.targetPreview) return this.caretVisible
      return false
    },
  },
  onLoad() {
    this.pageMotionClass = consumeTabTransition(1)
    this.loadSamples()
  },
  onUnload() {
    stopVisionStream()
    this.resetTypewriter()
    this.stopCaretBlink()
  },
  methods: {
    handleTouchStart(event) {
      const touch = event.touches?.[0]
      if (!touch) return
      this.touchStartPoint = {
        x: touch.clientX,
        y: touch.clientY,
      }
    },
    handleTouchEnd(event) {
      const touch = event.changedTouches?.[0]
      const nextIndex = getSwipeTargetIndex(1, this.touchStartPoint, touch ? {
        x: touch.clientX,
        y: touch.clientY,
      } : null)
      this.resetTouch()
      navigateToTab(1, nextIndex)
    },
    resetTouch() {
      this.touchStartPoint = null
    },
    async loadSamples() {
      try {
        const res = await getVisionSamples()
        const items = (res.data?.items || []).map((item) => ({
          ...item,
          imageUrl: resolveUrl(item.imageUrl),
          fallbackImageUrl: buildSamplePlaceholder(item.title || '参考图'),
        }))
        if (items.length) {
          this.sampleItems = items
        }
      } catch (error) {
        console.warn('load vision samples failed', error)
      }
    },
    resetStreamState() {
      this.streamState = createInitialStreamState(this.selectedScene)
    },
    prepareAnalysis() {
      stopVisionStream()
      const runId = ++this.analysisRunId
      this.isAnalyzing = true
      this.lastError = ''
      this.streamState = {
        ...createInitialStreamState(this.selectedScene),
        phase: 'queued',
        phaseLabel: '已接收',
        phaseMessage: '已接收',
      }
      this.resetTypewriter()
      this.ensureCaretBlink()
      return runId
    },
    isCurrentAnalysis(runId) {
      return runId === this.analysisRunId
    },
    clearCurrentImage() {
      stopVisionStream()
      this.analysisRunId += 1
      this.isAnalyzing = false
      this.imageUrl = ''
      this.activeSampleKey = ''
      this.selectedUploadFile = null
      this.selectedUploadScene = ''
      this.lastError = ''
    },
    handleStreamMeta(meta) {
      const scene = meta?.scene || this.selectedScene
      this.streamState = {
        ...this.streamState,
        badgeText: meta ? (scene === 'text-reading' ? '流式识文' : '流式识图') : this.streamState.badgeText,
      }
    },
    handleStreamStatus(status) {
      const phase = status?.phase || 'done'
      this.streamState = {
        ...this.streamState,
        phase,
        phaseLabel: resolvePhaseLabel(phase),
        phaseMessage: status?.message || this.streamState.phaseMessage,
        badgeText: phase === 'fallback' ? '参考结果' : this.streamState.badgeText,
      }
    },
    handleStreamPreview(payload) {
      const text = payload?.text || ''
      if (!text || text === this.streamState.preview) return
      this.streamState = {
        ...this.streamState,
        preview: text,
      }
      this.queueTypewriter(text)
    },
    queueTypewriter(fullText) {
      this.targetPreview = fullText
      this.ensureCaretBlink()
      if (this.typingTimer) return
      const step = () => {
        if (this.displayedPreview === this.targetPreview) {
          this.typingTimer = null
          if (!this.isAnalyzing) {
            // Finish; stop caret a moment later so the last char stays visible.
            setTimeout(() => this.stopCaretBlink(), 400)
          }
          return
        }
        // Append one character at a time, ~70 chars/sec for a snappier feel.
        const nextLen = Math.min(this.targetPreview.length, this.displayedPreview.length + 1)
        this.displayedPreview = this.targetPreview.slice(0, nextLen)
        this.typingTimer = setTimeout(step, 14)
      }
      step()
    },
    ensureCaretBlink() {
      if (this.caretBlinkTimer) return
      this.caretBlinkTimer = setInterval(() => {
        this.caretVisible = !this.caretVisible
      }, 520)
    },
    stopCaretBlink() {
      if (this.caretBlinkTimer) {
        clearInterval(this.caretBlinkTimer)
        this.caretBlinkTimer = null
      }
      this.caretVisible = true
    },
    resetTypewriter() {
      if (this.typingTimer) {
        clearTimeout(this.typingTimer)
        this.typingTimer = null
      }
      this.displayedPreview = ''
      this.targetPreview = ''
    },
    applyResultPayload(payload) {
      if (!payload) return
      this.result = payload
      const finalText = payload.recognizedText || payload.readingText || payload.voiceBroadcast || this.streamState.preview
      this.streamState = {
        ...this.streamState,
        preview: finalText,
        phase: 'done',
        phaseLabel: '分析完成',
        phaseMessage:
          payload.analysisMode === 'llm-vision'
            ? '已完成'
            : '参考结果',
        badgeText:
          payload.analysisMode === 'llm-vision'
            ? payload.scene === 'text-reading'
              ? '文本阅读'
              : '多模态识图'
            : '参考结果',
      }
      if (finalText) {
        this.queueTypewriter(finalText)
      }
    },
    markAnalyzing(badge = '识图分析', message = '正在分析图像…') {
      this.streamState = {
        ...this.streamState,
        phase: 'model',
        phaseLabel: resolvePhaseLabel('model'),
        phaseMessage: message,
        badgeText: badge,
      }
      this.queueTypewriter(message)
    },
    async startJsonStreaming(payload) {
      const runId = this.prepareAnalysis()
      try {
        if (canStreamVision()) {
          const result = await streamVisionJson(payload, {
            onMeta: (meta) => this.isCurrentAnalysis(runId) && this.handleStreamMeta(meta),
            onStatus: (status) => this.isCurrentAnalysis(runId) && this.handleStreamStatus(status),
            onPreview: (preview) => this.isCurrentAnalysis(runId) && this.handleStreamPreview(preview),
            onResult: (data) => this.isCurrentAnalysis(runId) && this.applyResultPayload(data),
          })
          if (!this.isCurrentAnalysis(runId)) return
          this.applyResultPayload(result)
        } else {
          // 小程序 / App 等不支持 fetch 流的环境：直接调用非流式接口。
          this.markAnalyzing('识图分析', '正在分析图像，请稍候…')
          const res = await request('/api/vision/analyze', 'POST', payload || {})
          if (!this.isCurrentAnalysis(runId)) return
          this.applyResultPayload(res?.data || res || this.result)
        }
        if (!this.isCurrentAnalysis(runId)) return
        await this.speakResult()
      } catch (error) {
        if (!this.isCurrentAnalysis(runId)) return
        console.warn('vision json analyze failed', error)
        this.lastError = extractErrorText(error)
        this.streamState = {
          ...this.streamState,
          phase: 'fallback',
          phaseLabel: '参考结果',
          phaseMessage: '参考结果',
          badgeText: '参考结果',
        }
        throw error
      } finally {
        if (this.isCurrentAnalysis(runId)) {
          this.isAnalyzing = false
        }
      }
    },
    async startUploadStreaming(file) {
      if (!this.imageUrl || this.selectedUploadScene !== this.selectedScene) {
        this.showSceneGuide(this.selectedScene)
        return
      }
      const runId = this.prepareAnalysis()
      try {
        if (canStreamVision() && file) {
          const result = await streamVisionUpload(file, this.selectedScene, {
            onMeta: (meta) => this.isCurrentAnalysis(runId) && this.handleStreamMeta(meta),
            onStatus: (status) => this.isCurrentAnalysis(runId) && this.handleStreamStatus(status),
            onPreview: (preview) => this.isCurrentAnalysis(runId) && this.handleStreamPreview(preview),
            onResult: (data) => this.isCurrentAnalysis(runId) && this.applyResultPayload(data),
          })
          if (!this.isCurrentAnalysis(runId)) return
          this.applyResultPayload(result)
        } else {
          // 小程序：走 uni.uploadFile 的非流式上传接口。
          this.markAnalyzing('识图分析', '正在上传并分析图像…')
          const res = await uploadVisionImage(this.imageUrl, this.selectedScene)
          if (!this.isCurrentAnalysis(runId)) return
          this.applyResultPayload(res?.data || res || this.result)
        }
        if (!this.isCurrentAnalysis(runId)) return
        await this.speakResult()
      } catch (error) {
        if (!this.isCurrentAnalysis(runId)) return
        console.warn('vision upload analyze failed', error)
        this.lastError = extractErrorText(error)
        this.streamState = {
          ...this.streamState,
          phase: 'fallback',
          phaseLabel: '参考结果',
          phaseMessage: '参考结果',
          badgeText: '参考结果',
        }
      } finally {
        if (this.isCurrentAnalysis(runId)) {
          this.isAnalyzing = false
        }
      }
    },
    async changeScene(scene) {
      if (scene === this.selectedScene) return
      this.selectedScene = scene
      this.clearCurrentImage()
      this.showSceneGuide(scene)
    },
    onSampleImageError(item) {
      if (!item) return
      if (!item.fallbackImageUrl) {
        item.fallbackImageUrl = buildSamplePlaceholder(item.title || '参考图')
      }
      if (item.imageUrl !== item.fallbackImageUrl) {
        item.imageUrl = item.fallbackImageUrl
      }
    },
    chooseImage() {
      if (this.isAnalyzing) return
      uni.chooseImage({
        count: 1,
        sizeType: ['compressed'],
        sourceType: ['album', 'camera'],
        success: async (res) => {
          this.imageUrl = res.tempFilePaths[0]
          this.activeSampleKey = ''
          this.selectedUploadFile = res.tempFiles?.[0]?.file || null
          this.selectedUploadScene = this.selectedScene
          try {
            await this.startUploadStreaming(this.selectedUploadFile)
          } catch (error) {
            console.warn('vision upload failed', error)
            uni.showToast({
              title: '识图失败，请重试',
              icon: 'none',
            })
          }
        },
      })
    },
    async useSample(item) {
      if (this.isAnalyzing) return
      this.selectedScene = item.scene || this.selectedScene
      this.activeSampleKey = item.key
      this.imageUrl = item.imageUrl
      this.selectedUploadFile = null
      this.selectedUploadScene = ''
      this.lastError = ''
      try {
        await this.startJsonStreaming({
          scene: item.scene,
          sampleKey: item.key,
        })
      } catch {}
    },
    async runDemo() {
      if (this.isAnalyzing) return
      if (this.imageUrl && !this.activeSampleKey && this.selectedUploadScene === this.selectedScene) {
        try {
          await this.startUploadStreaming(this.selectedUploadFile)
          return
        } catch (error) {
          console.warn('reanalyze current upload failed', error)
        }
      }
      if (manualOnlyScenes.has(this.selectedScene) && !this.currentSample && !this.imageUrl) {
        this.showSceneGuide(this.selectedScene)
        return
      }
      this.activeSampleKey = ''
      try {
        await this.startJsonStreaming({ scene: this.selectedScene })
      } catch {
        /* startJsonStreaming 内部已做非流式兜底；此处吞掉异常避免污染日志 */
      }
    },
    showSceneGuide(scene) {
      this.result = buildFallbackResult(scene)
      this.resetStreamState()
      this.resetTypewriter()
      this.displayedPreview = this.result.recognizedText || ''
      this.targetPreview = this.displayedPreview
      this.stopCaretBlink()
    },
    async speakResult() {
      if (this.speakingVoice) {
        stopSpeech()
        this.speakingVoice = false
        if (this.speechLoadingVisible) {
          uni.hideLoading()
          this.speechLoadingVisible = false
        }
        return
      }
      const spokenText = this.readingScriptText || this.result.voiceBroadcast || this.result.recognizedText
      if (!spokenText) return
      try {
        this.speakingVoice = true
        uni.showLoading({ title: '生成语音…', mask: true })
        this.speechLoadingVisible = true
        const payload = await playSpeech({
          text: spokenText,
          title: this.isTextReadingResult ? '文本阅读朗读' : '识图结果播报',
          source: this.isTextReadingResult ? '文本阅读' : '情境识别',
        })
        if (payload?.ended) {
          if (this.speechLoadingVisible) {
            uni.hideLoading()
            this.speechLoadingVisible = false
          }
          await payload.ended
        }
      } catch (error) {
        uni.showToast({ title: '语音播放失败', icon: 'none' })
      } finally {
        if (this.speechLoadingVisible) {
          uni.hideLoading()
          this.speechLoadingVisible = false
        }
        this.speakingVoice = false
      }
    },
  },
}
</script>

<style scoped>
.page-shell {
  min-height: 100vh;
  background: var(--paper-bg);
}

.page-content {
  padding: 48rpx 36rpx calc(env(safe-area-inset-bottom) + 260rpx);
}

/* === Masthead === */
.masthead {
  padding: 20rpx 0 32rpx;
  margin-bottom: 36rpx;
}

.masthead__bar {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  margin-bottom: 14rpx;
}

.masthead__brand {
  font-size: 24rpx;
  font-weight: 900;
  letter-spacing: 8rpx;
  color: var(--ink);
}

.masthead__status {
  font-size: 22rpx;
  letter-spacing: 2rpx;
  color: var(--ink-muted);
  padding: 4rpx 14rpx;
  border: 1rpx solid var(--rule);
  border-radius: 2rpx;
}

.masthead__status--live {
  color: var(--accent-warm);
  border-color: var(--accent-warm);
}

.masthead__status--ready {
  color: var(--accent);
  border-color: var(--accent);
}

.masthead__status--fallback {
  color: var(--danger);
  border-color: var(--danger);
}

.masthead__rule {
  height: 2rpx;
  background: var(--ink);
  margin: 0 0 28rpx;
}

.masthead__kicker {
  display: block;
  font-size: 22rpx;
  letter-spacing: 6rpx;
  color: var(--accent-warm);
  font-weight: 700;
  margin-bottom: 20rpx;
  text-transform: uppercase;
}

.masthead__title {
  display: block;
  font-size: 60rpx;
  font-weight: 900;
  line-height: 1.12;
  color: var(--ink);
  letter-spacing: -1rpx;
}

/* === Block layout === */
.block {
  margin-bottom: 52rpx;
}

.block-head {
  display: flex;
  align-items: baseline;
  gap: 16rpx;
  padding-bottom: 16rpx;
  margin-bottom: 22rpx;
  border-bottom: 1rpx solid var(--ink);
}

.block-no {
  font-size: 22rpx;
  font-weight: 900;
  color: var(--accent-warm);
  letter-spacing: 2rpx;
}

.block-title {
  font-size: 34rpx;
  font-weight: 900;
  color: var(--ink);
  letter-spacing: 2rpx;
  flex: 1;
}

.block-meta {
  font-size: 22rpx;
  color: var(--ink-muted);
  letter-spacing: 1rpx;
}

.meta--risk {
  font-weight: 700;
}

.meta--risk-low {
  color: var(--accent);
}

.meta--risk-medium {
  color: var(--accent-warm);
}

.meta--risk-high {
  color: var(--danger);
}

/* === Scene selector === */
.scene-row {
  display: flex;
  gap: 14rpx;
  flex-wrap: wrap;
}

.scene-tab {
  flex: 1 1 160rpx;
  padding: 18rpx 20rpx;
  font-size: 26rpx;
  font-weight: 700;
  color: var(--ink);
  background: transparent;
  border: 1rpx solid var(--ink);
  border-radius: 3rpx;
  text-align: center;
  letter-spacing: 1rpx;
}

.scene-tab--active {
  background: var(--ink);
  color: var(--paper-bg);
}

/* === Sample strip === */
.sample-strip {
  white-space: nowrap;
  margin: 0 -8rpx;
}

.sample-strip__row {
  display: inline-flex;
  gap: 18rpx;
  padding: 0 8rpx;
}

.sample-empty {
  padding: 28rpx 24rpx;
  background: var(--paper-card);
  border: 1rpx dashed var(--rule);
  border-radius: 3rpx;
}

.sample-empty__title {
  display: block;
  font-size: 26rpx;
  font-weight: 800;
  color: var(--ink);
  margin-bottom: 10rpx;
}

.sample-empty__hint {
  display: block;
  font-size: 22rpx;
  line-height: 1.7;
  color: var(--ink-soft);
}

.sample-item {
  width: 260rpx;
  flex-shrink: 0;
  border: 1rpx solid var(--rule);
  border-radius: 3rpx;
  overflow: hidden;
  background: var(--paper-card);
}

.sample-item--active {
  border: 2rpx solid var(--ink);
}

.sample-thumb {
  width: 100%;
  height: 180rpx;
  display: block;
  background: var(--paper-card-alt);
}

.sample-caption {
  padding: 14rpx 16rpx 18rpx;
  display: flex;
  flex-direction: column;
  gap: 6rpx;
}

.sample-caption__title {
  font-size: 24rpx;
  font-weight: 800;
  color: var(--ink);
  letter-spacing: 1rpx;
  white-space: normal;
}

.sample-caption__hint {
  font-size: 20rpx;
  line-height: 1.5;
  color: var(--ink-soft);
  white-space: normal;
}

/* === Stage (viewfinder) === */
.stage-screen {
  position: relative;
  margin-top: 6rpx;
  min-height: 560rpx;
  border: 2rpx solid var(--ink);
  border-radius: 3rpx;
  overflow: hidden;
  background: var(--paper-card-alt);
}

.stage-screen--active {
  border-color: var(--accent-warm);
}

.preview-image {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
}

.preview-empty {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 0 40rpx;
  gap: 14rpx;
}

.preview-empty__mark {
  font-size: 72rpx;
  color: var(--ink-faint);
  font-weight: 200;
  line-height: 1;
}

.preview-empty__title {
  font-size: 30rpx;
  font-weight: 800;
  color: var(--ink-soft);
  letter-spacing: 2rpx;
}

.preview-empty__hint {
  font-size: 22rpx;
  color: var(--ink-muted);
  letter-spacing: 1rpx;
}

.stage-beam {
  position: absolute;
  left: 0;
  right: 0;
  height: 4rpx;
  background: var(--accent-warm);
  box-shadow: 0 0 20rpx rgba(163, 79, 0, 0.6);
  animation: scan-beam 2.4s linear infinite;
}

.stage-corner {
  position: absolute;
  width: 36rpx;
  height: 36rpx;
  border: 3rpx solid var(--ink);
}

.stage-corner--tl { top: 16rpx; left: 16rpx; border-right: none; border-bottom: none; }
.stage-corner--tr { top: 16rpx; right: 16rpx; border-left: none; border-bottom: none; }
.stage-corner--bl { bottom: 16rpx; left: 16rpx; border-right: none; border-top: none; }
.stage-corner--br { bottom: 16rpx; right: 16rpx; border-left: none; border-top: none; }

@keyframes scan-beam {
  0% { top: 0%; opacity: 0; }
  10% { opacity: 1; }
  90% { opacity: 1; }
  100% { top: 100%; opacity: 0; }
}

/* === Action buttons === */
.action-row {
  display: flex;
  gap: 14rpx;
  margin-top: 22rpx;
}

.pencil-btn {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10rpx;
  padding: 22rpx 20rpx;
  border-radius: 4rpx;
  font-size: 26rpx;
  font-weight: 800;
  letter-spacing: 2rpx;
  border: 1rpx solid var(--ink);
  background: transparent;
  color: var(--ink);
}

.pencil-btn--primary {
  background: var(--ink);
  color: var(--paper-bg);
}

.pencil-btn--ghost {
  background: transparent;
  color: var(--ink);
}

.pencil-btn--speaking {
  background: var(--accent-warm);
  color: var(--paper-bg);
  border-color: var(--accent-warm);
}

.pencil-btn--disabled {
  opacity: 0.45;
}

.pencil-btn__icon,
.pencil-btn__label {
  color: inherit;
  font-size: 26rpx;
}

/* === Stream box === */
.stream-box {
  min-height: 160rpx;
  padding: 24rpx 26rpx;
  background: var(--paper-card);
  border: 1rpx solid var(--rule);
  border-radius: 3rpx;
  line-height: 1.8;
}

.stream-text {
  font-size: 28rpx;
  color: var(--ink);
  white-space: pre-wrap;
  word-break: break-word;
  letter-spacing: 0.5rpx;
}

.stream-caret {
  display: inline-block;
  margin-left: 4rpx;
  font-size: 26rpx;
  color: var(--accent-warm);
  transform: translateY(2rpx);
}

/* === Notice (error) === */
.notice {
  display: flex;
  align-items: baseline;
  gap: 14rpx;
  padding: 18rpx 22rpx;
  margin-bottom: 40rpx;
  border-left: 4rpx solid var(--danger);
  background: rgba(139, 42, 10, 0.06);
}

.notice__tag {
  font-size: 20rpx;
  font-weight: 900;
  color: var(--danger);
  letter-spacing: 2rpx;
}

.notice__copy {
  flex: 1;
  font-size: 24rpx;
  line-height: 1.6;
  color: var(--ink-soft);
}

/* === Insight === */
.insight-body {
  padding: 4rpx 0 18rpx;
}

.insight-title {
  display: block;
  font-size: 28rpx;
  font-weight: 800;
  color: var(--ink);
  margin-bottom: 10rpx;
  letter-spacing: 1rpx;
}

.insight-text {
  display: block;
  font-size: 28rpx;
  line-height: 1.78;
  color: var(--ink);
  letter-spacing: 0.5rpx;
  white-space: pre-wrap;
  word-break: break-word;
}

.safety-line {
  display: flex;
  align-items: flex-start;
  gap: 18rpx;
  padding: 20rpx 22rpx;
  background: var(--paper-card);
  border-left: 4rpx solid var(--ink-muted);
  margin-top: 16rpx;
}

.safety-line--low { border-left-color: var(--accent); }
.safety-line--medium { border-left-color: var(--accent-warm); }
.safety-line--high { border-left-color: var(--danger); }

.safety-line__bar { display: none; }

.safety-line__copy {
  flex: 1;
  font-size: 24rpx;
  line-height: 1.65;
  color: var(--ink-soft);
}

/* === Broadcast === */
.broadcast-quote {
  display: block;
  padding: 26rpx 28rpx;
  font-size: 30rpx;
  line-height: 1.7;
  color: var(--ink);
  background: var(--paper-card);
  border: 1rpx solid var(--rule);
  border-radius: 3rpx;
  font-style: italic;
  letter-spacing: 0.5rpx;
  white-space: pre-wrap;
  word-break: break-word;
}

/* === Mini btn (shared) === */
.mini-btn {
  padding: 8rpx 18rpx;
  font-size: 22rpx;
  font-weight: 700;
  letter-spacing: 1rpx;
  color: var(--ink);
  background: transparent;
  border: 1rpx solid var(--ink);
  border-radius: 3rpx;
}

.mini-btn--speaking {
  background: var(--accent-warm);
  color: var(--paper-bg);
  border-color: var(--accent-warm);
}

/* === Tips list === */
.tips-list {
  display: flex;
  flex-direction: column;
}

.tip-line {
  display: flex;
  gap: 18rpx;
  padding: 18rpx 0;
  border-bottom: 1rpx dashed var(--rule);
  align-items: flex-start;
}

.tip-line:last-child {
  border-bottom: none;
}

.tip-line__idx {
  font-size: 26rpx;
  font-weight: 900;
  color: var(--accent-warm);
  letter-spacing: 1rpx;
  min-width: 54rpx;
}

.tip-line__text {
  flex: 1;
  font-size: 26rpx;
  line-height: 1.65;
  color: var(--ink);
}

@media (max-width: 420px) {
  .masthead__title { font-size: 52rpx; }
  .scene-row { flex-wrap: wrap; }
  .scene-tab { flex: 1 1 30%; }
  .sample-item { width: 220rpx; }
}
</style>
