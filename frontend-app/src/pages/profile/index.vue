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
        <text class="masthead__brand">PROFILE · 我的</text>
        <text class="masthead__status" :class="profile.isLoggedIn ? 'masthead__status--ready' : ''">
          {{ profile.isLoggedIn ? '已登录' : '访客' }}
        </text>
      </view>
      <view class="masthead__rule"></view>
      <text class="masthead__kicker">个人中心 · 账号与记录</text>
      <text class="masthead__title">{{ profile.name }}</text>
      <text v-if="profile.intro" class="masthead__subtitle">— {{ profile.intro }}</text>
      <view class="masthead__actions">
        <view class="pencil-btn" :class="{ 'pencil-btn--ghost': !profile.isLoggedIn }" @tap="toggleProfile">
          <text class="pencil-btn__label">{{ profile.isLoggedIn ? '退出账号' : '登录账号' }}</text>
        </view>
      </view>
    </view>

    <!-- Shortcuts -->
    <view class="block">
      <view class="block-head">
        <text class="block-no">§01</text>
        <text class="block-title">常用入口</text>
      </view>
      <view
        v-for="(item, index) in shortcuts"
        :key="item.id"
        class="shortcut-row"
        @tap="handleFeature(item.title)"
      >
        <text class="shortcut-row__idx">0{{ index + 1 }}</text>
        <view class="shortcut-row__body">
          <text class="shortcut-row__title">{{ item.title }}</text>
          <text class="shortcut-row__desc">{{ item.desc }}</text>
        </view>
        <text class="shortcut-row__arrow">→</text>
      </view>
    </view>

    <!-- History -->
    <view class="block">
      <view class="block-head">
        <text class="block-no">§02</text>
        <text class="block-title">历史记录</text>
        <text class="block-meta">{{ historyItems.length }} 条</text>
      </view>
      <view v-if="!historyItems.length" class="empty-line">
        <text class="empty-line__mark">—</text>
        <text class="empty-line__text">暂无记录</text>
      </view>
      <view v-for="(item, index) in historyItems" :key="item.id" class="article">
        <text class="article__index">{{ String(index + 1).padStart(2, '0') }}</text>
        <view class="article__body">
          <view class="article__meta">
            <text class="article__tag">{{ item.type }}</text>
            <text class="article__dot">·</text>
            <text class="article__time">{{ item.time }}</text>
          </view>
          <text class="article__title">{{ item.title }}</text>
          <text v-if="item.summary" class="article__summary">{{ item.summary }}</text>
          <view class="article__foot">
            <text class="article__source"></text>
            <view class="mini-btn" @tap="speakItem(item)">♪ 朗读</view>
          </view>
        </view>
      </view>
    </view>

    <!-- System info -->
    <view class="block">
      <view class="block-head">
        <text class="block-no">§03</text>
        <text class="block-title">系统</text>
      </view>
      <view class="meta-list">
        <view class="meta-list__line">
          <text class="meta-list__label">项目</text>
          <text class="meta-list__value">{{ projectTitle }}</text>
        </view>
        <view class="meta-list__line">
          <text class="meta-list__label">部署</text>
          <text class="meta-list__value">{{ deployment }}</text>
        </view>
      </view>
    </view>

    </view>

    <!-- Auth modal -->
    <view v-if="authModal.open" class="auth-mask" @tap="closeAuthModal">
      <view class="auth-sheet" @tap.stop>
        <view class="auth-sheet__head">
          <text class="auth-sheet__title">{{ authModal.mode === 'register' ? '邮箱注册' : '邮箱登录' }}</text>
          <view class="auth-sheet__close" @tap="closeAuthModal">取消</view>
        </view>

        <view class="auth-tabs">
          <view
            class="auth-tab"
            :class="{ 'auth-tab--active': authModal.mode === 'login' }"
            @tap="switchAuthMode('login')"
          >登录</view>
          <view
            class="auth-tab"
            :class="{ 'auth-tab--active': authModal.mode === 'register' }"
            @tap="switchAuthMode('register')"
          >注册</view>
        </view>

        <view class="auth-field">
          <text class="auth-field__label">邮箱</text>
          <input
            v-model="authForm.email"
            class="auth-field__input"
            placeholder="your@example.com"
            type="text"
            confirm-type="done"
          />
        </view>

        <view v-if="authModal.mode === 'register'" class="auth-field auth-field--row">
          <view class="auth-field__col">
            <text class="auth-field__label">验证码</text>
            <input
              v-model="authForm.code"
              class="auth-field__input"
              placeholder="6 位数字"
              confirm-type="done"
              maxlength="6"
            />
          </view>
          <view
            class="auth-send-btn"
            :class="{ 'auth-send-btn--disabled': authForm.cooldown > 0 || authForm.sending }"
            @tap="sendCode"
          >
            {{ authForm.sending ? '发送中' : authForm.cooldown > 0 ? `${authForm.cooldown}s` : '获取验证码' }}
          </view>
        </view>

        <view v-if="authModal.mode === 'register' && authForm.devCode" class="auth-note">
          演示模式验证码：<text class="auth-note__code">{{ authForm.devCode }}</text>
        </view>

        <view class="auth-field">
          <text class="auth-field__label">密码</text>
          <input
            v-model="authForm.password"
            class="auth-field__input"
            :placeholder="authModal.mode === 'register' ? '至少 6 位' : '请输入密码'"
            type="text"
            password
            confirm-type="done"
          />
        </view>

        <view v-if="authModal.mode === 'register'" class="auth-field">
          <text class="auth-field__label">昵称（可选）</text>
          <input
            v-model="authForm.nickname"
            class="auth-field__input"
            placeholder="显示在个人中心"
            type="text"
            confirm-type="done"
          />
        </view>

        <view v-if="authForm.error" class="auth-error">{{ authForm.error }}</view>

        <view
          class="auth-submit"
          :class="{ 'auth-submit--loading': authForm.submitting }"
          @tap="submitAuth"
        >
          {{ authForm.submitting ? '提交中…' : (authModal.mode === 'register' ? '注册并登录' : '登录') }}
        </view>

        <text class="auth-hint">
          {{ authModal.mode === 'register' ? '注册成功后自动登录。' : '使用注册邮箱和密码登录。' }}
        </text>
      </view>
    </view>

    <bottom-dock active="profile" />
  </view>
</template>

<script>
import BottomDock from '../../components/BottomDock.vue'
import {
  getApiBase,
  getDefaultApiBase,
  playSpeech,
  request,
  resetApiBase,
  setApiBase,
  authSendCode,
  authRegister,
  authLoginPassword,
  authLogout,
  authMe,
  setAuthToken,
  setStoredUser,
  getStoredUser,
} from '../../utils/api'
import { getDemoProfile } from '../../utils/demo'
import {
  consumeTabTransition,
  getSwipeTargetIndex,
  navigateToTab,
} from '../../utils/tabNavigation'

function describeRequestError(error) {
  if (typeof error === 'string' && error.trim()) {
    return error.trim()
  }
  if (error?.message) {
    return error.message
  }
  if (error?.msg) {
    return error.msg
  }
  if (error?.error) {
    return error.error
  }
  return '接口暂不可用，请确认后端地址和服务状态。'
}

export default {
  components: {
    BottomDock,
  },
  data() {
    const storedUser = getStoredUser()
    return {
      profile: storedUser
        ? { isLoggedIn: true, name: storedUser.nickname || storedUser.email, intro: storedUser.email }
        : getDemoProfile(),
      shortcuts: [
        { id: 'history', title: '历史记录', desc: '查看识图与资讯播报记录' },
        { id: 'favorites', title: '收藏内容', desc: '回看重要提醒' },
        { id: 'preferences', title: '偏好设置', desc: '调整字号与播报偏好' },
      ],
      historyItems: [],
      projectTitle: '面向视障人士的多模态情境感知资讯辅助系统',
      deployment: 'vultr-mvp',
      apiBase: getApiBase(),
      apiBaseDraft: getApiBase(),
      defaultApiBase: getDefaultApiBase(),
      backendStatus: 'idle',
      backendSummary: '',
      runtimeStatus: {
        mode: 'unknown',
        llmConfigured: false,
        ttsConfigured: false,
        historyCount: 0,
      },
      pageMotionClass: '',
      touchStartPoint: null,
      authModal: {
        open: false,
        mode: 'login', // 'login' | 'register'
      },
      authForm: {
        email: '',
        code: '',
        password: '',
        nickname: '',
        sending: false,
        submitting: false,
        cooldown: 0,
        cooldownTimer: null,
        devCode: '',
        error: '',
      },
    }
  },
  computed: {
    backendStatusLabel() {
      switch (this.backendStatus) {
        case 'checking':
          return '检测中'
        case 'success':
          return '已连接'
        case 'error':
          return '连接失败'
        default:
          return '待检测'
      }
    },
  },
  onLoad() {
    this.pageMotionClass = consumeTabTransition(2)
  },
  onShow() {
    this.refreshAuthFromServer()
    this.loadProfileData()
  },
  onUnload() {
    if (this.authForm.cooldownTimer) {
      clearInterval(this.authForm.cooldownTimer)
    }
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
      const nextIndex = getSwipeTargetIndex(2, this.touchStartPoint, touch ? {
        x: touch.clientX,
        y: touch.clientY,
      } : null)
      this.resetTouch()
      navigateToTab(2, nextIndex)
    },
    resetTouch() {
      this.touchStartPoint = null
    },
    syncApiBase() {
      this.apiBase = getApiBase()
      this.apiBaseDraft = this.apiBase
    },
    applyHealthData(data = {}) {
      this.runtimeStatus = {
        mode: data.mode || 'unknown',
        llmConfigured: Boolean(data.llmConfigured),
        ttsConfigured: Boolean(data.ttsConfigured),
        historyCount: Number(data.historyCount) || 0,
      }
      this.backendStatus = 'success'
      this.backendSummary = `接口已连接，当前模式 ${this.runtimeStatus.mode}。`
    },
    async loadProfileData() {
      this.syncApiBase()
      this.backendStatus = 'checking'
      this.backendSummary = '正在检查后端服务与接口状态。'

      try {
        const healthRes = await request('/api/health')
        this.applyHealthData(healthRes.data || {})

        const [historyResult, metaResult] = await Promise.allSettled([
          request('/api/history'),
          request('/api/meta'),
        ])

        if (historyResult.status === 'fulfilled') {
          this.historyItems = historyResult.value.data.items || []
        } else {
          this.historyItems = []
          this.backendSummary = `${this.backendSummary} 历史记录接口未返回。`
        }

        if (metaResult.status === 'fulfilled') {
          this.projectTitle = metaResult.value.data.project || this.projectTitle
          this.deployment = metaResult.value.data.deployment || this.deployment
        } else {
          this.backendSummary = `${this.backendSummary} 系统元信息未返回。`
        }
      } catch (error) {
        console.warn('load profile data failed', error)
        this.historyItems = []
        this.backendStatus = 'error'
        this.backendSummary = describeRequestError(error)
      }
    },
    async toggleProfile() {
      if (this.profile.isLoggedIn) {
        await authLogout()
        this.profile = getDemoProfile()
        uni.showToast({ title: '已退出登录', icon: 'none' })
        return
      }
      this.openAuthModal('login')
    },
    openAuthModal(mode = 'login') {
      this.authModal = { open: true, mode }
      this.authForm = {
        ...this.authForm,
        code: '',
        password: '',
        nickname: '',
        devCode: '',
        error: '',
        sending: false,
        submitting: false,
      }
    },
    closeAuthModal() {
      this.authModal.open = false
      if (this.authForm.cooldownTimer) {
        clearInterval(this.authForm.cooldownTimer)
        this.authForm.cooldownTimer = null
      }
    },
    switchAuthMode(mode) {
      this.authModal.mode = mode
      this.authForm.error = ''
      this.authForm.code = ''
      this.authForm.devCode = ''
    },
    async sendCode() {
      if (this.authModal.mode !== 'register') return
      if (this.authForm.cooldown > 0 || this.authForm.sending) return
      const email = (this.authForm.email || '').trim()
      if (!email || !email.includes('@')) {
        this.authForm.error = '请输入有效的邮箱地址'
        return
      }
      this.authForm.error = ''
      this.authForm.sending = true
      try {
        const res = await authSendCode(email)
        const data = res.data || {}
        this.authForm.devCode = data.devCode || ''
        this.startCooldown(Number(data.cooldownSec) || 45)
        uni.showToast({
          title: data.devMode ? '演示验证码已显示' : '验证码已发送',
          icon: 'none',
        })
      } catch (err) {
        this.authForm.error = this.describeError(err) || '验证码发送失败'
      } finally {
        this.authForm.sending = false
      }
    },
    startCooldown(sec) {
      this.authForm.cooldown = sec
      if (this.authForm.cooldownTimer) clearInterval(this.authForm.cooldownTimer)
      this.authForm.cooldownTimer = setInterval(() => {
        this.authForm.cooldown -= 1
        if (this.authForm.cooldown <= 0) {
          clearInterval(this.authForm.cooldownTimer)
          this.authForm.cooldownTimer = null
          this.authForm.cooldown = 0
        }
      }, 1000)
    },
    async submitAuth() {
      if (this.authForm.submitting) return
      const email = (this.authForm.email || '').trim()
      const code = (this.authForm.code || '').trim()
      const password = this.authForm.password || ''
      if (!email) {
        this.authForm.error = '请输入邮箱地址'
        return
      }
      if (this.authModal.mode === 'register' && !code) {
        this.authForm.error = '请输入验证码'
        return
      }
      if (!password) {
        this.authForm.error = '请输入密码'
        return
      }
      if (this.authModal.mode === 'register' && password.length < 6) {
        this.authForm.error = '密码至少 6 位'
        return
      }
      this.authForm.error = ''
      this.authForm.submitting = true
      try {
        const res = this.authModal.mode === 'register'
          ? await authRegister({ email, code, password, nickname: this.authForm.nickname })
          : await authLoginPassword({ email, password })
        const data = res.data || {}
        if (!data.token || !data.user) throw new Error('服务端未返回用户信息')
        setAuthToken(data.token)
        setStoredUser(data.user)
        this.profile = { isLoggedIn: true, name: data.user.nickname || data.user.email, intro: data.user.email }
        this.closeAuthModal()
        uni.showToast({
          title: this.authModal.mode === 'register' ? '注册成功' : '登录成功',
          icon: 'none',
        })
      } catch (err) {
        this.authForm.error = this.describeError(err) || '提交失败'
      } finally {
        this.authForm.submitting = false
      }
    },
    async refreshAuthFromServer() {
      const stored = getStoredUser()
      if (!stored) return
      try {
        const res = await authMe()
        const user = res.data && res.data.user
        if (user) {
          setStoredUser(user)
          this.profile = { isLoggedIn: true, name: user.nickname || user.email, intro: user.email }
        } else {
          setAuthToken('')
          setStoredUser(null)
          this.profile = getDemoProfile()
        }
      } catch {
        // network error - keep cached state
      }
    },
    describeError(err) {
      if (!err) return ''
      if (typeof err === 'string') return err
      if (err.message) return err.message
      if (err.data && err.data.message) return err.data.message
      return ''
    },
    handleFeature(label) {
      uni.showToast({
        title: `${label} 将在下一轮迭代接入`,
        icon: 'none',
      })
    },
    async saveApiBase() {
      this.apiBase = setApiBase(this.apiBaseDraft)
      this.apiBaseDraft = this.apiBase
      uni.showToast({
        title: '后端地址已保存',
        icon: 'none',
      })
      await this.loadProfileData()
    },
    async resetApiBaseValue() {
      this.apiBase = resetApiBase()
      this.apiBaseDraft = this.apiBase
      uni.showToast({
        title: '已恢复默认地址',
        icon: 'none',
      })
      await this.loadProfileData()
    },
    async probeBackend() {
      await this.loadProfileData()
    },
    async speakItem(item) {
      try {
        await playSpeech({
          text: item.spokenText || `${item.title}。${item.summary}`,
          title: item.title,
          source: item.source || '历史记录',
        })
      } catch (error) {
        uni.showToast({
          title: '语音生成失败',
          icon: 'none',
        })
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

.masthead__status--ready {
  color: var(--accent);
  border-color: var(--accent);
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
  font-size: 56rpx;
  font-weight: 900;
  line-height: 1.12;
  color: var(--ink);
  letter-spacing: -1rpx;
  margin-bottom: 14rpx;
}

.masthead__subtitle {
  display: block;
  font-size: 26rpx;
  line-height: 1.55;
  color: var(--ink-soft);
  font-style: italic;
  margin-bottom: 22rpx;
}

.masthead__actions {
  display: flex;
  gap: 14rpx;
  flex-wrap: wrap;
}

.pencil-btn {
  display: inline-flex;
  align-items: center;
  gap: 10rpx;
  padding: 16rpx 28rpx;
  background: var(--ink);
  color: var(--paper-bg);
  border: 1rpx solid var(--ink);
  border-radius: 4rpx;
  font-size: 24rpx;
  font-weight: 800;
  letter-spacing: 2rpx;
}

.pencil-btn--ghost {
  background: transparent;
  color: var(--ink);
}

.pencil-btn__label {
  color: inherit;
  font-size: 24rpx;
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

.meta--backend-healthy { color: var(--accent); }
.meta--backend-checking { color: var(--accent-warm); }
.meta--backend-error { color: var(--danger); }

/* === Shortcut rows === */
.shortcut-row {
  display: flex;
  align-items: center;
  gap: 20rpx;
  padding: 22rpx 0;
  border-bottom: 1rpx dashed var(--rule);
}

.shortcut-row:last-child {
  border-bottom: 1rpx solid var(--ink);
}

.shortcut-row__idx {
  font-size: 30rpx;
  font-weight: 900;
  color: var(--ink-faint);
  letter-spacing: 1rpx;
  min-width: 60rpx;
}

.shortcut-row__body {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 6rpx;
}

.shortcut-row__title {
  font-size: 28rpx;
  font-weight: 800;
  color: var(--ink);
}

.shortcut-row__desc {
  font-size: 22rpx;
  color: var(--ink-muted);
  line-height: 1.55;
}

.shortcut-row__arrow {
  font-size: 28rpx;
  color: var(--ink);
}

/* === Backend config === */
.backend-row {
  display: flex;
  align-items: baseline;
  gap: 18rpx;
  padding: 12rpx 0;
  border-bottom: 1rpx dotted var(--rule-soft);
}

.backend-row__label {
  font-size: 20rpx;
  font-weight: 700;
  color: var(--ink-muted);
  letter-spacing: 2rpx;
  min-width: 100rpx;
  text-transform: uppercase;
}

.backend-row__value {
  flex: 1;
  font-size: 24rpx;
  color: var(--ink);
  word-break: break-all;
  font-variant-numeric: tabular-nums;
}

.backend-summary {
  display: block;
  margin: 14rpx 0 20rpx;
  padding: 14rpx 18rpx;
  border-left: 3rpx solid var(--accent-warm);
  background: var(--paper-card);
  font-size: 22rpx;
  line-height: 1.6;
  color: var(--ink-soft);
}

.backend-input-wrap {
  margin-top: 14rpx;
  margin-bottom: 18rpx;
}

.backend-input-label {
  display: block;
  font-size: 20rpx;
  font-weight: 700;
  color: var(--ink-muted);
  letter-spacing: 2rpx;
  margin-bottom: 8rpx;
  text-transform: uppercase;
}

.backend-input {
  width: 100%;
  height: 76rpx;
  padding: 0 20rpx;
  border: 1rpx solid var(--ink);
  background: var(--paper-bg);
  font-size: 26rpx;
  color: var(--ink);
  border-radius: 3rpx;
}

.backend-actions {
  display: flex;
  gap: 12rpx;
  margin-bottom: 22rpx;
  flex-wrap: wrap;
}

.mini-btn {
  padding: 12rpx 22rpx;
  font-size: 22rpx;
  font-weight: 700;
  letter-spacing: 1rpx;
  color: var(--ink);
  background: transparent;
  border: 1rpx solid var(--ink);
  border-radius: 3rpx;
}

.mini-btn--primary {
  background: var(--ink);
  color: var(--paper-bg);
}

.runtime-grid {
  display: flex;
  flex-wrap: wrap;
  gap: 14rpx;
}

.runtime-cell {
  flex: 1 1 46%;
  min-width: 45%;
  padding: 16rpx 20rpx;
  background: var(--paper-card);
  border: 1rpx solid var(--rule);
  border-radius: 3rpx;
  display: flex;
  flex-direction: column;
  gap: 6rpx;
}

.runtime-cell__label {
  font-size: 20rpx;
  font-weight: 700;
  color: var(--ink-muted);
  letter-spacing: 2rpx;
  text-transform: uppercase;
}

.runtime-cell__value {
  font-size: 26rpx;
  font-weight: 800;
  color: var(--ink);
}

/* === Article (history) === */
.article {
  display: flex;
  gap: 20rpx;
  padding: 24rpx 0;
  border-bottom: 1rpx dashed var(--rule);
}

.article:last-child {
  border-bottom: 1rpx solid var(--ink);
}

.article__index {
  font-size: 36rpx;
  font-weight: 900;
  color: var(--ink-faint);
  letter-spacing: -1rpx;
  min-width: 72rpx;
  padding-top: 4rpx;
}

.article__body {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 8rpx;
  min-width: 0;
}

.article__meta {
  display: flex;
  align-items: baseline;
  gap: 10rpx;
}

.article__tag {
  font-size: 20rpx;
  font-weight: 800;
  color: var(--accent-warm);
  letter-spacing: 2rpx;
  text-transform: uppercase;
}

.article__dot {
  color: var(--ink-muted);
  font-size: 20rpx;
}

.article__time {
  font-size: 20rpx;
  color: var(--ink-muted);
  font-variant-numeric: tabular-nums;
}

.article__title {
  font-size: 28rpx;
  font-weight: 800;
  color: var(--ink);
  line-height: 1.4;
}

.article__summary {
  font-size: 22rpx;
  line-height: 1.6;
  color: var(--ink-soft);
  padding-left: 12rpx;
  border-left: 2rpx solid var(--rule);
}

.article__foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 6rpx;
}

.article__source {
  flex: 1;
}

/* === Empty state === */
.empty-line {
  display: flex;
  align-items: center;
  gap: 14rpx;
  padding: 24rpx 0;
}

.empty-line__mark {
  font-size: 30rpx;
  color: var(--ink-faint);
  font-weight: 300;
}

.empty-line__text {
  font-size: 24rpx;
  color: var(--ink-muted);
  letter-spacing: 2rpx;
}

/* === Meta list === */
.meta-list {
  display: flex;
  flex-direction: column;
}

.meta-list__line {
  display: flex;
  align-items: baseline;
  gap: 18rpx;
  padding: 14rpx 0;
  border-bottom: 1rpx dotted var(--rule-soft);
}

.meta-list__line:last-child {
  border-bottom: none;
}

.meta-list__label {
  font-size: 20rpx;
  font-weight: 700;
  color: var(--ink-muted);
  letter-spacing: 2rpx;
  min-width: 80rpx;
  text-transform: uppercase;
}

.meta-list__value {
  flex: 1;
  font-size: 24rpx;
  color: var(--ink);
  line-height: 1.55;
}

/* === Auth modal === */
.auth-mask {
  position: fixed;
  inset: 0;
  background: rgba(26, 26, 26, 0.55);
  z-index: 950;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 40rpx 32rpx;
}

.auth-sheet {
  width: 100%;
  max-width: 640rpx;
  background: var(--paper-card);
  border: 3rpx solid var(--ink);
  padding: 32rpx 34rpx 36rpx;
  max-height: 90vh;
  overflow-y: auto;
}

.auth-sheet__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 20rpx;
  padding-bottom: 14rpx;
  border-bottom: 2rpx solid var(--ink);
}

.auth-sheet__title {
  font-size: 34rpx;
  font-weight: 900;
  color: var(--ink);
  letter-spacing: 2rpx;
}

.auth-sheet__close {
  font-size: 24rpx;
  color: var(--ink-muted);
  padding: 6rpx 16rpx;
  border: 1rpx solid var(--rule);
  border-radius: 3rpx;
}

.auth-tabs {
  display: flex;
  gap: 10rpx;
  margin-bottom: 24rpx;
}

.auth-tab {
  flex: 1;
  padding: 14rpx 0;
  text-align: center;
  font-size: 26rpx;
  font-weight: 800;
  letter-spacing: 2rpx;
  border: 1rpx solid var(--ink);
  color: var(--ink);
  border-radius: 3rpx;
}

.auth-tab--active {
  background: var(--ink);
  color: var(--paper-bg);
}

.auth-field {
  margin-bottom: 18rpx;
}

.auth-field--row {
  display: flex;
  align-items: flex-end;
  gap: 12rpx;
}

.auth-field__col {
  flex: 1;
}

.auth-field__label {
  display: block;
  font-size: 20rpx;
  font-weight: 700;
  color: var(--ink-muted);
  letter-spacing: 2rpx;
  margin-bottom: 8rpx;
  text-transform: uppercase;
}

.auth-field__input {
  width: 100%;
  height: 72rpx;
  padding: 0 20rpx;
  border: 1rpx solid var(--ink);
  background: var(--paper-bg);
  font-size: 26rpx;
  color: var(--ink);
  border-radius: 3rpx;
}

.auth-send-btn {
  height: 72rpx;
  padding: 0 20rpx;
  line-height: 72rpx;
  background: var(--ink);
  color: var(--paper-bg);
  font-size: 22rpx;
  font-weight: 800;
  letter-spacing: 1rpx;
  border-radius: 3rpx;
  white-space: nowrap;
}

.auth-send-btn--disabled {
  background: var(--ink-faint);
  color: var(--paper-card);
}

.auth-note {
  padding: 12rpx 16rpx;
  margin-bottom: 18rpx;
  background: rgba(163, 79, 0, 0.08);
  border-left: 3rpx solid var(--accent-warm);
  font-size: 22rpx;
  color: var(--ink-soft);
  letter-spacing: 1rpx;
}

.auth-note__code {
  font-size: 28rpx;
  font-weight: 900;
  color: var(--accent-warm);
  letter-spacing: 4rpx;
  font-variant-numeric: tabular-nums;
  margin-left: 6rpx;
}

.auth-error {
  margin-bottom: 16rpx;
  padding: 10rpx 14rpx;
  background: rgba(139, 42, 10, 0.08);
  border-left: 3rpx solid var(--danger);
  color: var(--danger);
  font-size: 22rpx;
  font-weight: 700;
  letter-spacing: 1rpx;
}

.auth-submit {
  padding: 22rpx;
  background: var(--ink);
  color: var(--paper-bg);
  text-align: center;
  font-size: 28rpx;
  font-weight: 800;
  letter-spacing: 4rpx;
  border-radius: 3rpx;
  margin-top: 10rpx;
}

.auth-submit--loading {
  opacity: 0.6;
}

.auth-hint {
  display: block;
  margin-top: 16rpx;
  font-size: 22rpx;
  color: var(--ink-muted);
  text-align: center;
  letter-spacing: 1rpx;
}
</style>
