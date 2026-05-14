<template>
  <view class="page-shell paper-texture">

    <!-- Masthead -->
    <view class="masthead">
      <view class="masthead__bar">
        <text class="masthead__tag">{{ news.category || '资讯' }}</text>
        <text class="masthead__date">{{ news.time }}</text>
      </view>
      <view class="masthead__rule"></view>
      <text class="masthead__title">{{ news.title }}</text>
      <text v-if="news.source" class="masthead__byline">— 来源 / {{ news.source }}</text>
    </view>

    <!-- AI 解读 -->
    <view class="block">
      <view class="block-head">
        <text class="block-no">§01</text>
        <text class="block-title">AI 解读</text>
        <text class="block-meta" :class="{ 'meta--llm': interpret.mode === 'llm' }">
          {{ interpretLoading ? '生成中…' : interpret.mode === 'llm' ? 'DeepSeek' : '参考解读' }}
        </text>
      </view>
      <view v-if="interpretLoading && !interpret.brief" class="skeleton">
        <view class="skeleton-line"></view>
        <view class="skeleton-line skeleton-line--short"></view>
        <view class="skeleton-line"></view>
      </view>
      <text v-if="interpret.brief" class="ai-brief">{{ interpret.brief }}</text>
      <view v-if="interpret.keyPoints && interpret.keyPoints.length" class="ai-points">
        <view v-for="(point, idx) in interpret.keyPoints" :key="idx" class="ai-point">
          <text class="ai-point__idx">0{{ idx + 1 }}</text>
          <text class="ai-point__text">{{ point }}</text>
        </view>
      </view>
      <view class="ai-foot">
        <view
          class="mini-btn"
          :class="{ 'mini-btn--speaking': speakingTarget === 'brief' }"
          @tap="speakBrief"
        >
          {{ speakingTarget === 'brief' ? '■ 停止' : '♪ 朗读解读' }}
        </view>
      </view>
    </view>

    <!-- 摘要 -->
    <view v-if="news.summary" class="block">
      <view class="block-head">
        <text class="block-no">§02</text>
        <text class="block-title">摘要</text>
        <view
          class="mini-btn"
          :class="{ 'mini-btn--speaking': speakingTarget === 'summary' }"
          @tap="speakSummary"
        >
          {{ speakingTarget === 'summary' ? '■ 停止' : '♪ 朗读' }}
        </view>
      </view>
      <text class="body-text">{{ news.summary }}</text>
    </view>

    <!-- 正文 -->
    <view v-if="news.content" class="block">
      <view class="block-head">
        <text class="block-no">§03</text>
        <text class="block-title">正文</text>
        <view
          class="mini-btn"
          :class="{ 'mini-btn--speaking': speakingTarget === 'content' }"
          @tap="speakFull"
        >
          {{ speakingTarget === 'content' ? '■ 停止' : '♪ 朗读全文' }}
        </view>
      </view>
      <text class="body-text body-text--content">{{ news.content }}</text>
    </view>

    <!-- 原文链接 -->
    <view v-if="news.url" class="source-link" @tap="openOriginal">
      <text class="source-link__label">查看原文 →</text>
      <text class="source-link__url">{{ displayUrl }}</text>
    </view>

  </view>
</template>

<script>
import { playSpeech, stopSpeech, interpretNews } from '../../utils/api'
import { parsePayload } from '../../utils/demo'

export default {
  data() {
    return {
      news: {
        title: '',
        category: '',
        time: '',
        source: '',
        content: '',
        summary: '',
        url: '',
      },
      interpret: {
        mode: '',
        brief: '',
        keyPoints: [],
        spokenText: '',
      },
      interpretLoading: false,
      speakingTarget: null,
    }
  },
  computed: {
    displayUrl() {
      const url = this.news.url || ''
      if (!url) return ''
      return url.length > 50 ? url.slice(0, 50) + '...' : url
    },
  },
  onLoad(options) {
    const payload = parsePayload(options.payload)
    if (!payload) {
      uni.showToast({ title: '内容加载失败', icon: 'none' })
      return
    }
    this.news = { url: '', ...payload }
    this.loadInterpretation()
  },
  onUnload() {
    stopSpeech()
  },
  methods: {
    async loadInterpretation() {
      this.interpretLoading = true
      try {
        const res = await interpretNews({
          title: this.news.title,
          summary: this.news.summary,
          content: this.news.content,
          source: this.news.source,
          category: this.news.category,
        })
        this.interpret = res.data || this.interpret
      } catch (err) {
        // silent fallback, keep default empty state
      } finally {
        this.interpretLoading = false
      }
    },
    async playWith(key, text, title) {
      if (this.speakingTarget === key) {
        stopSpeech()
        this.speakingTarget = null
        return
      }
      if (!text) {
        uni.showToast({ title: '无可朗读内容', icon: 'none' })
        return
      }
      try {
        this.speakingTarget = key
        uni.showLoading({ title: '生成语音…', mask: true })
        await playSpeech({ text, title, source: this.news.source || '资讯' })
      } catch (err) {
        uni.showToast({ title: '语音播放失败', icon: 'none' })
      } finally {
        uni.hideLoading()
        this.speakingTarget = null
      }
    },
    speakBrief() {
      const text = this.interpret.spokenText || this.interpret.brief
      this.playWith('brief', text, this.news.title || 'AI 解读')
    },
    speakSummary() {
      this.playWith('summary', this.news.summary, this.news.title || '资讯摘要')
    },
    speakFull() {
      // Prefer content only; don't read the title again.
      const text = this.news.content
        || this.news.summary
        || this.news.spokenText
        || this.news.title
      this.playWith('content', text, this.news.title || '资讯全文')
    },
    openOriginal() {
      const url = this.news.url
      if (!url) return
      // #ifdef H5
      if (typeof window !== 'undefined') {
        window.open(url, '_blank', 'noopener,noreferrer')
        return
      }
      // #endif
      uni.setClipboardData({
        data: url,
        success: () => {
          uni.showToast({ title: '链接已复制', icon: 'none' })
        },
      })
    },
  },
}
</script>

<style scoped>
.page-shell {
  min-height: 100vh;
  padding: 48rpx 36rpx 120rpx;
  background: var(--paper-bg);
  color: var(--ink);
}

/* Masthead */
.masthead {
  padding-bottom: 28rpx;
  margin-bottom: 36rpx;
}

.masthead__bar {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  margin-bottom: 14rpx;
}

.masthead__tag {
  font-size: 22rpx;
  font-weight: 900;
  color: var(--accent-warm);
  letter-spacing: 4rpx;
  text-transform: uppercase;
}

.masthead__date {
  font-size: 22rpx;
  color: var(--ink-muted);
  font-variant-numeric: tabular-nums;
}

.masthead__rule {
  height: 2rpx;
  background: var(--ink);
  margin-bottom: 22rpx;
}

.masthead__title {
  display: block;
  font-size: 46rpx;
  font-weight: 900;
  line-height: 1.25;
  color: var(--ink);
  letter-spacing: -0.5rpx;
  margin-bottom: 14rpx;
}

.masthead__byline {
  display: block;
  font-size: 22rpx;
  color: var(--ink-soft);
  font-style: italic;
  letter-spacing: 1rpx;
}

/* Block */
.block {
  margin-bottom: 44rpx;
}

.block-head {
  display: flex;
  align-items: baseline;
  gap: 16rpx;
  padding-bottom: 14rpx;
  margin-bottom: 18rpx;
  border-bottom: 1rpx solid var(--ink);
}

.block-no {
  font-size: 20rpx;
  font-weight: 900;
  color: var(--accent-warm);
  letter-spacing: 2rpx;
}

.block-title {
  font-size: 30rpx;
  font-weight: 900;
  color: var(--ink);
  letter-spacing: 2rpx;
  flex: 1;
}

.block-meta {
  font-size: 20rpx;
  color: var(--ink-muted);
  letter-spacing: 1rpx;
  padding: 4rpx 12rpx;
  border: 1rpx solid var(--rule);
  border-radius: 2rpx;
}

.block-meta.meta--llm {
  color: var(--accent);
  border-color: var(--accent);
}

/* AI brief */
.ai-brief {
  display: block;
  font-size: 30rpx;
  line-height: 1.78;
  color: var(--ink);
  padding-left: 18rpx;
  border-left: 3rpx solid var(--accent-warm);
  letter-spacing: 0.5rpx;
  margin-bottom: 22rpx;
}

.ai-points {
  display: flex;
  flex-direction: column;
}

.ai-point {
  display: flex;
  align-items: flex-start;
  gap: 16rpx;
  padding: 14rpx 0;
  border-bottom: 1rpx dashed var(--rule);
}

.ai-point:last-child {
  border-bottom: none;
}

.ai-point__idx {
  font-size: 22rpx;
  font-weight: 900;
  color: var(--accent-warm);
  letter-spacing: 1rpx;
  min-width: 50rpx;
  padding-top: 4rpx;
}

.ai-point__text {
  flex: 1;
  font-size: 26rpx;
  line-height: 1.65;
  color: var(--ink);
}

.ai-foot {
  margin-top: 18rpx;
  padding-top: 16rpx;
  border-top: 1rpx dotted var(--rule-soft);
  display: flex;
  justify-content: flex-end;
}

/* Body text */
.body-text {
  display: block;
  font-size: 28rpx;
  line-height: 1.82;
  color: var(--ink);
  letter-spacing: 0.5rpx;
}

.body-text--content {
  font-size: 26rpx;
}

/* Source link */
.source-link {
  margin-top: 20rpx;
  padding: 22rpx 24rpx;
  background: var(--paper-card);
  border: 1rpx solid var(--ink);
  border-radius: 3rpx;
  display: flex;
  flex-direction: column;
  gap: 8rpx;
}

.source-link__label {
  font-size: 24rpx;
  font-weight: 800;
  color: var(--ink);
  letter-spacing: 2rpx;
}

.source-link__url {
  font-size: 22rpx;
  color: var(--ink-muted);
  word-break: break-all;
}

/* Mini btn */
.mini-btn {
  padding: 6rpx 18rpx;
  font-size: 20rpx;
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

/* Skeleton */
.skeleton {
  display: flex;
  flex-direction: column;
  gap: 12rpx;
  padding: 10rpx 0;
}

.skeleton-line {
  height: 20rpx;
  border-radius: 4rpx;
  background: linear-gradient(90deg, var(--rule-soft), var(--rule), var(--rule-soft));
  background-size: 200% 100%;
  animation: shimmer 1.3s ease-in-out infinite;
}

.skeleton-line--short {
  width: 60%;
}

@keyframes shimmer {
  0% { background-position: 0% 50%; }
  100% { background-position: 200% 50%; }
}
</style>
