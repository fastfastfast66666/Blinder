<template>
  <view
    class="page-shell paper-texture"
    @touchstart="handleTouchStart"
    @touchend="handleTouchEnd"
    @touchcancel="resetTouch"
  >
    <view class="page-content" :class="pageMotionClass">

    <!-- Masthead (editorial magazine header) -->
    <view class="masthead">
      <view class="masthead__bar">
        <text class="masthead__brand">B L I N D E R</text>
        <text v-if="headline.date" class="masthead__date">{{ headline.date }}</text>
      </view>
      <view class="masthead__rule"></view>
      <text class="masthead__kicker">本期 · 无障碍视角的城市</text>
      <text class="masthead__title">{{ headline.title || '本地资讯' }}</text>
      <text v-if="headline.subtitle" class="masthead__subtitle">— {{ headline.subtitle }}</text>

      <view class="masthead__actions">
        <view
          class="pencil-btn"
          :class="{ 'pencil-btn--speaking': speakingTarget === 'headline' }"
          @tap="speakHeadline"
        >
          <text class="pencil-btn__icon">♪</text>
          <text class="pencil-btn__label">{{ speakingTarget === 'headline' ? '停止播报' : '朗读摘要' }}</text>
        </view>
        <text class="masthead__tag">{{ feedModeLabel }}</text>
      </view>
    </view>

    <!-- Location strip (minimal city selector) -->
    <view class="strip" @tap="openCityPicker">
      <view class="strip__left">
        <text class="strip__label">城市</text>
        <text class="strip__city">{{ locationContext.city }}</text>
      </view>
      <view class="strip__mid">
        <text class="strip__meta">{{ locationSourceLabel }} · {{ locationUpdatedLabel }}</text>
      </view>
      <view class="strip__right">
        <view class="strip__action" :class="{ 'strip__action--busy': loading }" @tap.stop="hardRefresh">
          {{ loading ? '…' : '刷新' }}
        </view>
        <view class="strip__action strip__action--ghost" @tap.stop="refreshLocation">定位</view>
        <text class="strip__chevron">切换 ›</text>
      </view>
    </view>

    <!-- City picker sheet -->
    <view v-if="showCityPicker" class="city-mask" @tap="closeCityPicker">
      <view class="city-sheet" @tap.stop>
        <view class="city-sheet__head">
          <text class="city-sheet__title">选择城市</text>
          <view class="city-sheet__close" @tap="closeCityPicker">取消</view>
        </view>
        <view class="city-input-row">
          <input
            v-model="cityInput"
            class="city-input"
            placeholder="输入城市名，比如 杭州"
            confirm-type="done"
            @confirm="submitCustomCity"
          />
          <view class="city-input-btn" @tap="submitCustomCity">切换</view>
        </view>
        <view class="city-grid">
          <view
            v-for="c in presetCities"
            :key="c"
            class="city-chip"
            :class="{ 'city-chip--active': locationContext.city === c }"
            @tap="selectPresetCity(c)"
          >
            {{ c }}
          </view>
        </view>
      </view>
    </view>

    <view v-if="loading && !newsList.length" class="status-line">正在汇总天气、出行建议和本地快讯…</view>
    <view v-if="loadError" class="status-line status-line--error">首页推荐接口暂不可用</view>

    <!-- Quick entry — editorial index style -->
    <view class="entry-block">
      <view class="section-head">
        <text class="section-no">§01</text>
        <text class="section-title">本地优选</text>
      </view>
      <view class="entry-cols">
        <view v-for="item in quickEntries" :key="item.id" class="entry-col">
          <view class="entry-col__head">
            <text class="entry-col__label">{{ item.label }}</text>
            <view
              class="entry-col__btn"
              :class="{ 'entry-col__btn--speaking': speakingTarget === ('entry-' + item.id) }"
              @tap.stop="speakEntry(item)"
            >
              {{ speakingTarget === ('entry-' + item.id) ? '■' : '♪' }}
            </view>
          </view>
          <view class="entry-col__dot"></view>
          <text v-if="item.meta" class="entry-col__meta">{{ item.meta }}</text>
          <text class="entry-col__hint">{{ item.hint }}</text>
          <text v-if="item.detail" class="entry-col__detail">{{ item.detail }}</text>
        </view>
      </view>
    </view>

    <!-- News feed — calendar-grouped editorial list -->
    <view class="news-block">
      <view class="section-head">
        <text class="section-no">§02</text>
        <text class="section-title">资讯 · 日历</text>
        <text v-if="pagination.total" class="section-count">{{ newsList.length }} / {{ pagination.total }}</text>
      </view>

      <view
        v-for="group in dayGroups"
        :key="group.date"
        class="day-group"
      >
        <view class="day-head" @tap="toggleDay(group.date)">
          <view class="day-head__left">
            <text class="day-head__label">{{ group.label }}</text>
            <text class="day-head__date">{{ formatDate(group.date) }}</text>
          </view>
          <view class="day-head__right">
            <text class="day-head__count">{{ group.count }} 条</text>
            <text class="day-head__chevron">{{ collapsedDays[group.date] ? '▸' : '▾' }}</text>
          </view>
        </view>

        <view v-if="!collapsedDays[group.date]" class="day-body">
          <view
            v-for="(item, idx) in group.items"
            :key="item.id"
            class="article"
            :hover-class="'article--hover'"
            :hover-stay-time="120"
            @tap="openDetail(item)"
          >
            <text class="article__index">{{ String(idx + 1).padStart(2, '0') }}</text>
            <view class="article__body">
              <view class="article__meta">
                <text class="article__tag">{{ item.category }}</text>
                <text v-if="item.synthetic" class="article__tag article__tag--ai">AI</text>
                <text class="article__dot">·</text>
                <text class="article__time">{{ item.time }}</text>
              </view>
              <text class="article__title">{{ item.title }}</text>
              <text v-if="item.summary" class="article__summary">{{ item.summary }}</text>
              <view v-if="item.recommendReasons && item.recommendReasons.length" class="article__reasons">
                <text
                  v-for="reason in item.recommendReasons"
                  :key="reason"
                  class="article__reason"
                >
                  {{ reason }}
                </text>
              </view>
              <view class="article__foot">
                <text class="article__source">{{ item.source }}</text>
                <view class="article__actions">
                  <view
                    class="mini-btn"
                    :class="{ 'mini-btn--speaking': speakingTarget === ('news-' + item.id) }"
                    @tap.stop="speakNews(item)"
                  >
                    {{ speakingTarget === ('news-' + item.id) ? '■ 停止' : '♪ 朗读' }}
                  </view>
                  <view class="mini-btn mini-btn--primary" @tap.stop="openDetail(item)">详情 →</view>
                </view>
              </view>
              <view class="article__feedback">
                <view
                  class="feedback-btn"
                  :class="{ 'feedback-btn--active': item.userActions && item.userActions.liked }"
                  @tap.stop="handleFeedback(item, 'LIKE')"
                >喜欢</view>
                <view
                  class="feedback-btn"
                  :class="{ 'feedback-btn--active': item.userActions && item.userActions.disliked }"
                  @tap.stop="handleFeedback(item, 'DISLIKE')"
                >不喜欢</view>
                <view
                  class="feedback-btn"
                  :class="{ 'feedback-btn--active': item.userActions && item.userActions.favorited }"
                  @tap.stop="handleFeedback(item, 'FAVORITE')"
                >收藏</view>
                <view class="feedback-btn" @tap.stop="handleFeedback(item, 'SKIP')">跳过</view>
                <view class="feedback-btn feedback-btn--danger" @tap.stop="handleFeedback(item, 'BLOCK_SIMILAR')">不再推荐类似</view>
              </view>
            </view>
          </view>
        </view>
      </view>
    </view>

    <view
      v-if="pagination.hasMore"
      class="footer-btn"
      :class="{ 'footer-btn--loading': loadingMore }"
      @tap="loadMore"
    >
      <text class="footer-btn__label">{{ loadingMore ? '加载中…' : '加载更多' }}</text>
      <text class="footer-btn__arrow">↓</text>
    </view>
    <view v-else-if="newsList.length && pagination.total" class="footer-end">
      <view class="footer-end__rule"></view>
      <text class="footer-end__text">END · 共 {{ pagination.total }} 条</text>
      <view class="footer-end__rule"></view>
    </view>

    </view>
    <!-- FAB: play prioritized home feed -->
    <view
      class="fab-play"
      :class="{ 'fab-play--active': autoPlayState.running }"
      @tap="toggleAutoPlay"
    >
      <text class="fab-play__icon">{{ autoPlayState.running ? '■' : '▶' }}</text>
      <view class="fab-play__body">
        <text class="fab-play__label">{{ autoPlayState.running ? '停止连播' : '连播首页' }}</text>
        <text v-if="autoPlayState.running" class="fab-play__progress">
          {{ autoPlayState.index + 1 }} / {{ autoPlayState.total }}
        </text>
        <text v-else class="fab-play__progress">先播出行，再播快讯</text>
      </view>
    </view>

    <bottom-dock active="home" />
  </view>
</template>

<script>
import BottomDock from '../../components/BottomDock.vue'
import { playSpeech, stopSpeech, request, saveHistory } from '../../utils/api'
import { encodePayload } from '../../utils/demo'
import {
  consumeTabTransition,
  getSwipeTargetIndex,
  navigateToTab,
} from '../../utils/tabNavigation'

const DEFAULT_CITY = '上海'
const LOCATION_CACHE_KEY = 'bishe10_home_location_cache_v1'
const FEED_CACHE_PREFIX = 'bishe10_home_feed_cache_v4_'
const USER_ID_STORAGE_KEY = 'bishe10_guest_user_id_v1'
const LOCATION_TTL = 30 * 60 * 1000
const FEED_TTL = 15 * 60 * 1000
const DEFAULT_PAGE_SIZE = 20
const LOCATION_AUTO_TIMEOUT_MS = 8000
const LOCATION_MANUAL_TIMEOUT_MS = 12000
const RECOMMEND_REQUEST_TIMEOUT_MS = 18000
const PRESET_CITIES = [
  '上海', '北京', '广州', '深圳', '杭州',
  '南京', '成都', '重庆', '武汉', '西安',
  '苏州', '天津', '厦门', '青岛', '长沙',
  '沈阳', '大连', '济南', '郑州', '合肥',
]

const fallbackHeadline = {
  title: `${DEFAULT_CITY} · 本地资讯`,
  subtitle: '',
  date: '',
}

const fallbackEntries = [
  {
    id: 'travel',
    label: '出行',
    meta: '天气待确认',
    hint: '建议优先确认实时天气与常用路线',
    detail: '语音播报会先读这一块，再读本地快讯。',
    spokenText: '当前天气还在同步中。建议出门前先确认实时天气与常用路线，再决定是否外出。',
  },
  {
    id: 'bulletin',
    label: '快讯',
    meta: '本地快讯',
    hint: '社区服务和交通资讯正在整理中',
    detail: '稍后会补充适合直接播报的本地快讯。',
    spokenText: '本地快讯正在整理中，稍后会补充社区服务和交通资讯。',
  },
]

const fallbackNews = [
  {
    id: 1,
    category: '出行提醒',
    title: '上海 重点路口与施工绕行提示',
    summary: '优先整理斑马线、施工围挡和地铁无障碍电梯等高频出行信息。',
    time: '今天 09:20',
    source: '上海 出行服务',
    content: '系统会把本地出行和障碍信息改写成适合直接朗读的行动建议。',
    spokenText: '上海出行提醒已更新，请优先确认路口红绿灯、盲道连续性和施工绕行提示。',
  },
]

function nowIso() {
  return new Date().toISOString()
}

function isFresh(savedAt, ttl) {
  return typeof savedAt === 'number' && Date.now() - savedAt <= ttl
}

function getFeedCacheKey(city, userId = '') {
  return `${FEED_CACHE_PREFIX}${userId || 'anonymous'}_${city || DEFAULT_CITY}`
}

function readStorage(key) {
  try {
    return uni.getStorageSync(key)
  } catch (error) {
    console.warn('read storage failed', key, error)
    return null
  }
}

function writeStorage(key, value) {
  try {
    uni.setStorageSync(key, value)
  } catch (error) {
    console.warn('write storage failed', key, error)
  }
}

function readLocationCache() {
  return readStorage(LOCATION_CACHE_KEY) || null
}

function saveLocationCache(value) {
  writeStorage(LOCATION_CACHE_KEY, {
    ...value,
    savedAt: Date.now(),
  })
}

function readFeedCache(city, userId = '') {
  return readStorage(getFeedCacheKey(city, userId)) || null
}

function saveFeedCache(city, payload, userId = '') {
  writeStorage(getFeedCacheKey(city, userId), {
    payload,
    savedAt: Date.now(),
  })
}

function createGuestUserId() {
  return `guest_${Date.now()}_${Math.floor(Math.random() * 100000)}`
}

function requestDeviceLocation(timeoutMs = LOCATION_AUTO_TIMEOUT_MS, highAccuracy = false) {
  return new Promise((resolve, reject) => {
    let settled = false
    const timer = setTimeout(() => {
      if (settled) return
      settled = true
      reject({ errMsg: 'getLocation:fail timeout' })
    }, timeoutMs)

    uni.getLocation({
      type: 'wgs84',
      isHighAccuracy: highAccuracy,
      highAccuracyExpireTime: timeoutMs,
      success(res) {
        if (settled) return
        settled = true
        clearTimeout(timer)
        resolve(res)
      },
      fail(error) {
        if (settled) return
        settled = true
        clearTimeout(timer)
        reject(error)
      },
    })
  })
}

function normalizeLocationContext(raw = {}, override = {}) {
  const source = override.source || raw.source || 'default'
  return {
    city: override.city || raw.city || DEFAULT_CITY,
    source,
    permission: override.permission || raw.permission || (source === 'gps' ? 'granted' : 'unknown'),
    updatedAt: override.updatedAt || raw.updatedAt || nowIso(),
  }
}

function sourceLabel(source) {
  switch (source) {
    case 'gps':
      return 'GPS 定位'
    case 'cache':
      return '缓存城市'
    case 'manual':
      return '手动城市'
    default:
      return '默认城市'
  }
}

function formatUpdatedAt(updatedAt) {
  if (!updatedAt) return '刚刚更新'
  const date = new Date(updatedAt)
  if (Number.isNaN(date.getTime())) return '刚刚更新'

  const diff = Date.now() - date.getTime()
  if (diff < 60 * 1000) return '刚刚更新'
  if (diff < 60 * 60 * 1000) return `${Math.max(1, Math.floor(diff / (60 * 1000)))} 分钟前`

  const hour = `${date.getHours()}`.padStart(2, '0')
  const minute = `${date.getMinutes()}`.padStart(2, '0')
  return `${hour}:${minute} 更新`
}

function feedModeLabel(feedMode) {
  if (feedMode === 'personalized-news') {
    return '个性化推荐'
  }
  if (feedMode === 'city-contextual-digest') {
    return '城市定位推送'
  }
  return '资讯推荐'
}

export default {
  components: {
    BottomDock,
  },
  data() {
    return {
      headline: fallbackHeadline,
      quickEntries: fallbackEntries,
      newsList: fallbackNews,
      dayGroups: [],
      collapsedDays: {},
      pagination: { page: 1, pageSize: DEFAULT_PAGE_SIZE, total: 0, totalPages: 1, hasMore: false },
      loadingMore: false,
      systemMode: '线上服务',
      feedMode: 'city-contextual-digest',
      feedModeLabel: '城市定位推送',
      loading: false,
      loadError: false,
      userId: '',
      locationContext: {
        city: DEFAULT_CITY,
        source: 'default',
        permission: 'unknown',
        updatedAt: nowIso(),
      },
      locationSourceLabel: '默认城市',
      locationUpdatedLabel: '刚刚更新',
      pageMotionClass: '',
      touchStartPoint: null,
      speakingTarget: null,
      autoPlayState: {
        running: false,
        index: 0,
        total: 0,
        abort: false,
      },
      showCityPicker: false,
      cityInput: '',
      presetCities: PRESET_CITIES,
    }
  },
  onLoad() {
    this.pageMotionClass = consumeTabTransition(0)
    this.initUserId()
    this.loadMeta()
    this.bootstrapDashboard()
  },
  async onPullDownRefresh() {
    try {
      await this.hardRefresh()
    } finally {
      uni.stopPullDownRefresh()
    }
  },
  onReachBottom() {
    this.loadMore()
  },
  methods: {
    initUserId() {
      let userId = readStorage(USER_ID_STORAGE_KEY)
      if (!userId) {
        userId = createGuestUserId()
        writeStorage(USER_ID_STORAGE_KEY, userId)
      }
      this.userId = userId
    },
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
      const nextIndex = getSwipeTargetIndex(0, this.touchStartPoint, touch ? {
        x: touch.clientX,
        y: touch.clientY,
      } : null)
      this.resetTouch()
      navigateToTab(0, nextIndex)
    },
    resetTouch() {
      this.touchStartPoint = null
    },
    async loadMeta() {
      try {
        const metaRes = await request('/api/meta')
        this.systemMode = metaRes.data.deployment || this.systemMode
      } catch (error) {
        console.warn('load meta failed', error)
      }
    },
    async bootstrapDashboard(forceRelocate = false) {
      this.loading = true
      this.loadError = false

      const cachedLocation = readLocationCache()
      const freshCachedLocation = cachedLocation && isFresh(cachedLocation.savedAt, LOCATION_TTL)
      const cachedCity = cachedLocation && cachedLocation.city

      try {
        if (!forceRelocate && freshCachedLocation) {
          const feedCache = readFeedCache(cachedCity, this.userId)
          if (feedCache && isFresh(feedCache.savedAt, FEED_TTL)) {
            const feedLocationContext = feedCache.payload && feedCache.payload.locationContext
            this.applyDashboard(
              feedCache.payload,
              normalizeLocationContext(feedLocationContext, {
                city: cachedCity,
                source: 'cache',
                updatedAt: (feedLocationContext && feedLocationContext.updatedAt) || nowIso(),
              }),
              'replace',
            )
            return
          }

          await this.fetchNewsFeed(
            { city: cachedCity, page: 1, pageSize: DEFAULT_PAGE_SIZE },
            { city: cachedCity, source: 'cache', permission: 'granted' },
          )
          return
        }

        if (!forceRelocate) {
          const startupCity = cachedCity || DEFAULT_CITY
        await this.fetchNewsFeed(
          { city: startupCity, page: 1, pageSize: DEFAULT_PAGE_SIZE },
          { city: startupCity, source: cachedCity ? 'cache' : 'default', permission: 'unknown' },
        )
        return
        }

        const location = await requestDeviceLocation(LOCATION_MANUAL_TIMEOUT_MS, true)
        await this.fetchNewsFeed(
          { lat: location.latitude, lng: location.longitude, page: 1, pageSize: DEFAULT_PAGE_SIZE },
          { source: 'gps', permission: 'granted' },
          { latitude: location.latitude, longitude: location.longitude },
        )
      } catch (locationError) {
        console.info('location unavailable, using city feed', locationError)
        if (cachedCity) {
          await this.fetchNewsFeed(
            { city: cachedCity, page: 1, pageSize: DEFAULT_PAGE_SIZE },
            { city: cachedCity, source: 'cache', permission: 'granted' },
          )
          uni.showToast({ title: '未获取定位，已按缓存城市', icon: 'none' })
          return
        }
        try {
          await this.fetchNewsFeed(
            { page: 1, pageSize: DEFAULT_PAGE_SIZE },
            { city: DEFAULT_CITY, source: 'default', permission: 'unknown' },
          )
          uni.showToast({ title: '未获取定位，已按默认城市', icon: 'none' })
        } catch (error) {
          console.warn('load default city feed failed', error)
          this.loadError = true
        }
      } finally {
        this.loading = false
      }
    },
    refreshLocationInBackground() {
      requestDeviceLocation(LOCATION_AUTO_TIMEOUT_MS, false)
        .then((location) => this.fetchNewsFeed(
          { lat: location.latitude, lng: location.longitude, page: 1, pageSize: DEFAULT_PAGE_SIZE },
          { source: 'gps', permission: 'granted' },
          { latitude: location.latitude, longitude: location.longitude },
        ))
        .catch(() => {})
    },
    async fetchNewsFeed(params = {}, locationOverride = {}, coordinates = null, appendMode = 'replace') {
      const requestParams = this.buildRecommendParams(params, appendMode)
      const newsRes = await request('/api/news/recommend', 'GET', requestParams, {
        timeout: RECOMMEND_REQUEST_TIMEOUT_MS,
      })
      const payload = this.normalizeRecommendPayload(newsRes.data || {}, requestParams, locationOverride)
      const locationContext = normalizeLocationContext(payload.locationContext, locationOverride)

      this.applyDashboard(payload, locationContext, appendMode)

      if (coordinates && locationContext.city) {
        saveLocationCache({
          city: locationContext.city,
          latitude: coordinates.latitude,
          longitude: coordinates.longitude,
          updatedAt: locationContext.updatedAt,
        })
      }

      if (locationContext.city && appendMode === 'replace') {
        saveFeedCache(locationContext.city, payload, this.userId)
      }
    },
    buildRecommendParams(params = {}, appendMode = 'replace') {
      const next = {
        userId: this.userId,
        size: params.size || params.pageSize || DEFAULT_PAGE_SIZE,
      }
      if (params.city) next.city = params.city
      if (params.province) next.province = params.province
      if (params.lat) next.lat = params.lat
      if (params.lng) next.lng = params.lng
      if (params.force) next.force = 1
      if (appendMode === 'append' && this.newsList.length) {
        next.cursor = this.newsList
          .map(item => item.articleId)
          .filter(Boolean)
          .join(',')
      }
      return next
    },
    normalizeRecommendPayload(payload = {}, params = {}, locationOverride = {}) {
      if (!Array.isArray(payload.items)) {
        return payload
      }

      const serverLocation = payload.locationContext || {}
      const city = serverLocation.city || locationOverride.city || params.city || this.locationContext.city || DEFAULT_CITY
      const today = new Date().toISOString().slice(0, 10)
      const items = payload.items.map((item, index) => {
        const publishDate = item.publishTime ? String(item.publishTime).slice(0, 10) : today
        const reasons = Array.isArray(item.recommendReasons) ? item.recommendReasons : []
        return {
          id: item.articleId || `${Date.now()}_${index}`,
          articleId: item.articleId,
          category: item.category || '资讯',
          title: item.title || '本地资讯',
          summary: item.summary || '',
          time: item.time || '',
          source: item.source || '资讯推送',
          content: item.content || item.summary || '',
          spokenText: item.summary || item.title || '',
          url: item.url || '',
          tags: item.tags || [],
          recommendReasons: reasons,
          userActions: item.userActions || {},
          score: item.score || 0,
          fetchScope: item.fetchScope || '',
          synthetic: item.synthetic || item.fetchScope === 'FALLBACK',
          publishedDate: publishDate,
          dayLabel: publishDate === today ? '今天' : publishDate,
        }
      })

      return {
        headline: {
          title: `${city} · 个性化资讯`,
          subtitle: '根据城市、时间和你的反馈动态调整',
          date: today,
          spokenText: `${city}个性化资讯已更新，系统会根据你的喜欢、不喜欢和收藏继续优化推荐。`,
        },
        quickEntries: Array.isArray(payload.quickEntries) && payload.quickEntries.length
          ? payload.quickEntries
          : this.buildQuickEntriesFromItems(city, items, payload.weather || {}),
        newsList: items,
        dayGroups: [],
        pagination: {
          page: 1,
          pageSize: params.size || DEFAULT_PAGE_SIZE,
          total: items.length,
          totalPages: payload.hasMore ? 2 : 1,
          hasMore: Boolean(payload.hasMore),
        },
        feedMode: 'personalized-news',
        locationContext: {
          city,
          source: locationOverride.source || serverLocation.source || (params.lat && params.lng ? 'gps' : 'manual'),
          permission: locationOverride.permission || serverLocation.permission || 'unknown',
          updatedAt: serverLocation.updatedAt || nowIso(),
        },
      }
    },
    buildQuickEntriesFromItems(city, items = [], weather = {}) {
      const weatherText = weather.weather_text || '天气待确认'
      const temperature = weather.temperature !== undefined ? ` · ${weather.temperature}℃` : ''
      const alert = weather.alert ? ` · ${weather.alert}` : ''
      const first = items[0] || {}
      const second = items[1] || {}
      return [
        {
          id: 'travel',
          label: '出行',
          meta: weather.update_time ? `${weather.update_time} 更新` : '天气与出行建议',
          hint: `${weatherText}${temperature}${alert}`,
          detail: weather.travel_advice || '当前出行建议正在整理，请优先确认天气、路面和常用路线。',
          spokenText: weather.spoken_text || weather.travel_advice || '当前出行建议正在整理，请优先确认天气、路面和常用路线。',
        },
        {
          id: 'bulletin',
          label: '快讯',
          meta: `${city} 个性化快讯`,
          hint: first.title || '正在扩展同省和全国热点新闻',
          detail: [first.title, second.title].filter(Boolean).join('；') || '暂时没有更多本地快讯，系统会继续扩展同省和全国热点。',
          spokenText: items.slice(0, 3).map(item => item.summary || item.title).filter(Boolean).join('。'),
        },
      ]
    },
    applyDashboard(payload, locationContext, mode = 'replace') {
      this.headline = payload.headline || this.headline
      this.quickEntries = payload.quickEntries || this.quickEntries
      const incoming = payload.newsList || []
      if (mode === 'append') {
        const existingIds = new Set(this.newsList.map(n => n.id))
        const appended = incoming.filter(n => !existingIds.has(n.id))
        this.newsList = this.newsList.concat(appended)
      } else {
        this.newsList = incoming.length ? incoming : this.newsList
      }
      // Rebuild day-grouped structure. Prefer server-provided dayGroups when available
      // (page 1), otherwise regroup client-side for loadMore appends.
      if (mode === 'replace' && Array.isArray(payload.dayGroups) && payload.dayGroups.length) {
        this.dayGroups = payload.dayGroups
        this.seedCollapsedDays(payload.dayGroups)
      } else {
        this.dayGroups = this.regroupClientSide(this.newsList)
        this.seedCollapsedDays(this.dayGroups)
      }
      this.pagination = payload.pagination || this.pagination
      this.feedMode = payload.feedMode || this.feedMode
      this.feedModeLabel = feedModeLabel(this.feedMode)
      this.locationContext = locationContext
      this.locationSourceLabel = sourceLabel(locationContext.source)
      this.locationUpdatedLabel = formatUpdatedAt(locationContext.updatedAt)
    },
    regroupClientSide(list) {
      const groupsByDate = new Map()
      for (const item of list) {
        const date = item.publishedDate || 'unknown'
        if (!groupsByDate.has(date)) {
          groupsByDate.set(date, { date, label: item.dayLabel || '', items: [] })
        }
        groupsByDate.get(date).items.push(item)
      }
      const arr = Array.from(groupsByDate.values())
      arr.sort((a, b) => b.date.localeCompare(a.date))
      for (const g of arr) g.count = g.items.length
      return arr
    },
    seedCollapsedDays(groups) {
      // Keep user's current collapse state; only expand today/yesterday by default for new groups.
      const next = { ...this.collapsedDays }
      groups.forEach((g, idx) => {
        if (!(g.date in next)) {
          // Collapse older days by default to keep the feed tidy.
          next[g.date] = idx >= 2
        }
      })
      this.collapsedDays = next
    },
    toggleDay(date) {
      this.collapsedDays = { ...this.collapsedDays, [date]: !this.collapsedDays[date] }
    },
    formatDate(date) {
      if (!date) return ''
      // Show MM-DD (or localized). Strip leading year for compactness.
      const parts = String(date).split('-')
      if (parts.length >= 3) return `${parts[1]}-${parts[2]}`
      return date
    },
    entrySpeechText(item) {
      const parts = [
        item.label,
        item.meta,
        item.hint,
        item.detail,
      ].filter(Boolean)
      return item.spokenText || parts.join('。')
    },
    buildAutoPlayQueue() {
      const priorityEntries = ['travel', 'bulletin']
        .map((id) => this.quickEntries.find(item => item.id === id))
        .filter(Boolean)
        .map(item => ({
          kind: 'entry',
          key: 'entry-' + item.id,
          title: item.label,
          source: '本地优选',
          text: this.entrySpeechText(item),
        }))

      const newsQueue = this.newsList.map(item => ({
        kind: 'news',
        key: 'news-' + item.id,
        title: item.title,
        source: item.source || '资讯推送',
        text: item.spokenText || item.summary || item.content || item.title,
      }))

      return priorityEntries.concat(newsQueue)
    },
    async loadMore() {
      if (this.loadingMore || !this.pagination.hasMore) return
      this.loadingMore = true
      try {
        const nextPage = (this.pagination.page || 1) + 1
        await this.fetchNewsFeed(
          { city: this.locationContext.city, page: nextPage, pageSize: this.pagination.pageSize || DEFAULT_PAGE_SIZE },
          this.locationContext,
          null,
          'append',
        )
      } catch (err) {
        uni.showToast({ title: '加载更多失败', icon: 'none' })
      } finally {
        this.loadingMore = false
      }
    },
    async handleFeedback(item, action) {
      if (!item || !item.articleId) {
        uni.showToast({ title: '这条新闻暂不能反馈', icon: 'none' })
        return
      }
      try {
        await request(`/api/news/${item.articleId}/feedback`, 'POST', {
          userId: this.userId,
          action,
        })
        if (action === 'LIKE') {
          this.updateLocalAction(item.articleId, { liked: true, disliked: false })
          uni.showToast({ title: '已记录喜好', icon: 'none' })
          return
        }
        if (action === 'FAVORITE') {
          this.updateLocalAction(item.articleId, { favorited: true })
          uni.showToast({ title: '已收藏', icon: 'none' })
          return
        }

        this.removeArticle(item.articleId)
        const title = action === 'SKIP' ? '已跳过' : '将优化后续推荐'
        uni.showToast({ title, icon: 'none' })
        this.ensureEnoughNews()
      } catch (error) {
        console.warn('save feedback failed', error)
        uni.showToast({ title: '反馈保存失败', icon: 'none' })
      }
    },
    updateLocalAction(articleId, patch) {
      this.newsList = this.newsList.map(item => {
        if (item.articleId !== articleId) return item
        return {
          ...item,
          userActions: {
            ...(item.userActions || {}),
            ...patch,
          },
        }
      })
      this.dayGroups = this.regroupClientSide(this.newsList)
      this.seedCollapsedDays(this.dayGroups)
    },
    removeArticle(articleId) {
      this.newsList = this.newsList.filter(item => item.articleId !== articleId)
      this.dayGroups = this.regroupClientSide(this.newsList)
      this.seedCollapsedDays(this.dayGroups)
    },
    ensureEnoughNews() {
      if (this.newsList.length >= 5 || this.loadingMore) {
        return
      }
      if (this.pagination.hasMore) {
        this.loadMore()
        return
      }
      this.fetchNewsFeed(
        { size: DEFAULT_PAGE_SIZE },
        { city: this.locationContext.city || DEFAULT_CITY, source: 'manual', permission: 'unknown' },
        null,
        'append',
      ).catch(() => {
        uni.showToast({ title: '暂时没有更多新闻，为你保留当前内容', icon: 'none' })
      })
    },
    refreshLocation() {
      this.bootstrapDashboard(true)
    },
    async hardRefresh() {
      const city = this.locationContext.city
      this.loadError = false
      this.loading = true
      this.newsList = []
      this.pagination = { page: 1, pageSize: DEFAULT_PAGE_SIZE, total: 0, totalPages: 1, hasMore: false }
      try {
        await this.fetchNewsFeed(
          { city, page: 1, pageSize: DEFAULT_PAGE_SIZE, force: 1 },
          this.locationContext,
        )
        uni.showToast({ title: '已刷新到最新', icon: 'none' })
      } catch (err) {
        this.loadError = true
        uni.showToast({ title: '刷新失败', icon: 'none' })
      } finally {
        this.loading = false
      }
    },
    openCityPicker() {
      this.cityInput = ''
      this.showCityPicker = true
    },
    closeCityPicker() {
      this.showCityPicker = false
    },
    async selectPresetCity(city) {
      this.showCityPicker = false
      await this.switchToCity(city)
    },
    async submitCustomCity() {
      const city = (this.cityInput || '').trim()
      if (!city) {
        uni.showToast({ title: '请输入城市名', icon: 'none' })
        return
      }
      this.showCityPicker = false
      await this.switchToCity(city)
    },
    async switchToCity(city) {
      this.loading = true
      this.loadError = false
      const hadNews = this.newsList.length > 0
      try {
        await this.fetchNewsFeed(
          { city, page: 1, pageSize: DEFAULT_PAGE_SIZE },
          { city, source: 'manual', permission: 'granted', updatedAt: nowIso() },
        )
      } catch (err) {
        console.warn('switch city feed failed', err)
        this.loadError = !hadNews
        uni.showToast({ title: hadNews ? '切换失败，已保留当前内容' : '首页推荐接口暂不可用', icon: 'none' })
      } finally {
        this.loading = false
      }
    },
    async speakHeadline() {
      if (this.speakingTarget === 'headline') {
        stopSpeech()
        this.speakingTarget = null
        return
      }
      try {
        this.speakingTarget = 'headline'
        uni.showLoading({ title: '生成语音…', mask: true })
        await playSpeech({
          text: this.headline.spokenText || `${this.headline.title}。${this.headline.subtitle}`,
          title: this.headline.title,
          source: '首页资讯',
        })
      } catch (error) {
        uni.showToast({ title: '语音播放失败', icon: 'none' })
      } finally {
        uni.hideLoading()
        this.speakingTarget = null
      }
    },
    async speakNews(item) {
      const key = 'news-' + item.id
      if (this.speakingTarget === key) {
        stopSpeech()
        this.speakingTarget = null
        return
      }
      try {
        this.speakingTarget = key
        uni.showLoading({ title: '生成语音…', mask: true })
        await playSpeech({
          text: item.spokenText || item.summary || item.content || item.title,
          title: item.title,
          source: item.source || '资讯推送',
        })
      } catch (error) {
        uni.showToast({ title: '语音播放失败', icon: 'none' })
      } finally {
        uni.hideLoading()
        this.speakingTarget = null
      }
    },
    async speakEntry(item) {
      const key = 'entry-' + item.id
      if (this.speakingTarget === key) {
        stopSpeech()
        this.speakingTarget = null
        return
      }
      try {
        this.speakingTarget = key
        uni.showLoading({ title: '生成语音…', mask: true })
        await playSpeech({
          text: this.entrySpeechText(item),
          title: item.label,
          source: '本地优选',
        })
      } catch (error) {
        uni.showToast({ title: '语音播放失败', icon: 'none' })
      } finally {
        uni.hideLoading()
        this.speakingTarget = null
      }
    },
    async openDetail(item) {
      try {
        await saveHistory({
          type: 'news',
          title: item.title,
          summary: item.summary,
          source: item.source,
          spokenText: item.spokenText || item.summary,
        })
      } catch (error) {
        console.warn('save history failed', error)
      }
      if (item.articleId && this.userId) {
        request(`/api/news/${item.articleId}/feedback`, 'POST', {
          userId: this.userId,
          action: 'VIEW',
        }).catch(error => console.warn('save view feedback failed', error))
      }
      uni.navigateTo({
        url: `/pages/news-detail/index?payload=${encodePayload(item)}`,
      })
    },
    async toggleAutoPlay() {
      if (this.autoPlayState.running) {
        this.autoPlayState.abort = true
        stopSpeech()
        this.autoPlayState = { running: false, index: 0, total: 0, abort: false }
        this.speakingTarget = null
        return
      }
      const queue = this.buildAutoPlayQueue()
      if (!queue.length) {
        uni.showToast({ title: '暂无内容可播报', icon: 'none' })
        return
      }
      this.autoPlayState = {
        running: true,
        index: 0,
        total: queue.length,
        abort: false,
      }
      for (let i = 0; i < queue.length; i++) {
        if (this.autoPlayState.abort) break
        const item = queue[i]
        this.autoPlayState.index = i
        this.speakingTarget = item.key
        try {
          const payload = await playSpeech({
            text: item.text,
            title: item.title,
            source: item.source,
          })
          if (payload && payload.ended) {
            await payload.ended
          }
        } catch (error) {
          console.warn('auto-play home feed failed', error)
        }
        if (this.autoPlayState.abort) break
        await new Promise((r) => setTimeout(r, 320))
      }
      this.speakingTarget = null
      this.autoPlayState = { running: false, index: 0, total: 0, abort: false }
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
  margin-bottom: 28rpx;
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
  letter-spacing: 10rpx;
  color: var(--ink);
}

.masthead__date {
  font-size: 22rpx;
  letter-spacing: 2rpx;
  color: var(--ink-muted);
  font-variant-numeric: tabular-nums;
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
  font-size: 64rpx;
  font-weight: 900;
  line-height: 1.12;
  color: var(--ink);
  letter-spacing: -1rpx;
  margin-bottom: 18rpx;
}

.masthead__subtitle {
  display: block;
  font-size: 28rpx;
  line-height: 1.58;
  color: var(--ink-soft);
  margin-bottom: 26rpx;
  font-style: italic;
}

.masthead__actions {
  display: flex;
  align-items: center;
  gap: 20rpx;
  flex-wrap: wrap;
  margin-top: 8rpx;
}

.pencil-btn {
  display: inline-flex;
  align-items: center;
  gap: 10rpx;
  padding: 14rpx 28rpx;
  background: var(--ink);
  color: var(--paper-bg);
  border-radius: 4rpx;
  border: 1rpx solid var(--ink);
  font-size: 24rpx;
  font-weight: 700;
  letter-spacing: 1rpx;
}

.pencil-btn--speaking {
  background: var(--accent-warm);
  border-color: var(--accent-warm);
}

.pencil-btn__icon {
  font-size: 24rpx;
}

.pencil-btn__label {
  font-size: 24rpx;
}

.masthead__tag {
  font-size: 22rpx;
  color: var(--ink-muted);
  letter-spacing: 2rpx;
  padding: 12rpx 20rpx;
  border: 1rpx dashed var(--rule);
  border-radius: 4rpx;
}

/* === Strip (city) === */
.strip {
  display: flex;
  align-items: center;
  padding: 22rpx 0;
  border-top: 1rpx solid var(--ink);
  border-bottom: 1rpx solid var(--ink);
  margin-bottom: 42rpx;
}

.strip__left {
  display: flex;
  align-items: baseline;
  gap: 16rpx;
}

.strip__label {
  font-size: 20rpx;
  letter-spacing: 4rpx;
  color: var(--ink-muted);
  text-transform: uppercase;
  font-weight: 700;
}

.strip__city {
  font-size: 32rpx;
  font-weight: 800;
  color: var(--ink);
  letter-spacing: 1rpx;
}

.strip__mid {
  flex: 1;
  text-align: center;
  padding: 0 12rpx;
  overflow: hidden;
}

.strip__meta {
  display: block;
  font-size: 20rpx;
  color: var(--ink-muted);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.strip__right {
  display: flex;
  align-items: center;
  gap: 14rpx;
}

.strip__action {
  padding: 8rpx 18rpx;
  font-size: 22rpx;
  font-weight: 700;
  color: var(--paper-bg);
  background: var(--accent);
  border: 1rpx solid var(--accent);
  border-radius: 4rpx;
  letter-spacing: 1rpx;
}

.strip__action--ghost {
  color: var(--accent);
  background: transparent;
}

.strip__action--busy {
  opacity: 0.55;
}

.strip__chevron {
  font-size: 22rpx;
  font-weight: 700;
  color: var(--ink-soft);
  letter-spacing: 1rpx;
}

/* === Status line === */
.status-line {
  padding: 16rpx 0;
  text-align: center;
  font-size: 24rpx;
  color: var(--ink-muted);
  letter-spacing: 1rpx;
  margin-bottom: 24rpx;
}

.status-line--error {
  color: var(--danger);
}

/* === Section head === */
.section-head {
  display: flex;
  align-items: baseline;
  gap: 16rpx;
  padding-bottom: 18rpx;
  margin-bottom: 24rpx;
  border-bottom: 1rpx solid var(--ink);
}

.section-no {
  font-size: 22rpx;
  font-weight: 900;
  color: var(--accent-warm);
  letter-spacing: 2rpx;
}

.section-title {
  font-size: 36rpx;
  font-weight: 900;
  color: var(--ink);
  letter-spacing: 2rpx;
  flex: 1;
}

.section-count {
  font-size: 22rpx;
  color: var(--ink-muted);
  font-variant-numeric: tabular-nums;
  letter-spacing: 1rpx;
}

/* === Entry block === */
.entry-block {
  margin-bottom: 52rpx;
}

.entry-cols {
  display: flex;
  gap: 22rpx;
}

.entry-col {
  flex: 1;
  padding: 20rpx 18rpx;
  background: var(--paper-card);
  border: 1rpx solid var(--rule);
  border-radius: 6rpx;
  display: flex;
  flex-direction: column;
  gap: 10rpx;
}

.entry-col__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10rpx;
}

.entry-col__label {
  font-size: 30rpx;
  font-weight: 800;
  color: var(--ink);
  letter-spacing: 2rpx;
}

.entry-col__btn {
  width: 48rpx;
  height: 48rpx;
  line-height: 48rpx;
  text-align: center;
  border-radius: 999rpx;
  background: transparent;
  border: 1rpx solid var(--ink);
  color: var(--ink);
  font-size: 24rpx;
  font-weight: 700;
  flex-shrink: 0;
}

.entry-col__btn--speaking {
  background: var(--accent-warm);
  border-color: var(--accent-warm);
  color: var(--paper-bg);
}

.entry-col__dot {
  width: 12rpx;
  height: 12rpx;
  border-radius: 999rpx;
  background: var(--accent-warm);
  margin: 4rpx 0;
}

.entry-col__meta {
  font-size: 20rpx;
  line-height: 1.5;
  color: var(--ink-muted);
  letter-spacing: 1rpx;
}

.entry-col__hint {
  font-size: 22rpx;
  line-height: 1.55;
  color: var(--ink);
  font-weight: 700;
}

.entry-col__detail {
  font-size: 22rpx;
  line-height: 1.65;
  color: var(--ink-soft);
}

/* === News block (calendar-grouped list) === */
.news-block {
  margin-bottom: 32rpx;
}

.day-group {
  margin-bottom: 24rpx;
}

.day-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16rpx 0;
  border-top: 2rpx solid var(--ink);
  border-bottom: 1rpx solid var(--rule);
}

.day-head__left {
  display: flex;
  align-items: baseline;
  gap: 16rpx;
}

.day-head__label {
  font-size: 32rpx;
  font-weight: 900;
  color: var(--ink);
  letter-spacing: 2rpx;
}

.day-head__date {
  font-size: 22rpx;
  color: var(--ink-muted);
  font-variant-numeric: tabular-nums;
  letter-spacing: 1rpx;
}

.day-head__right {
  display: flex;
  align-items: center;
  gap: 14rpx;
}

.day-head__count {
  font-size: 22rpx;
  color: var(--ink-muted);
  letter-spacing: 1rpx;
  font-variant-numeric: tabular-nums;
}

.day-head__chevron {
  font-size: 26rpx;
  color: var(--ink);
  font-weight: 900;
  width: 36rpx;
  text-align: center;
}

.day-body {
  padding-top: 4rpx;
}

.article__tag--ai {
  color: var(--paper-bg);
  background: var(--accent-warm);
  padding: 0 8rpx;
  border-radius: 2rpx;
  margin-left: 6rpx;
}

.article {
  display: flex;
  gap: 24rpx;
  padding: 28rpx 0;
  border-bottom: 1rpx dashed var(--rule);
}

.article:last-child {
  border-bottom: 1rpx solid var(--ink);
}

.article--hover {
  background: var(--paper-card);
}

.article__index {
  font-size: 44rpx;
  font-weight: 900;
  color: var(--ink-faint);
  line-height: 1;
  letter-spacing: -1rpx;
  font-variant-numeric: tabular-nums;
  min-width: 80rpx;
  padding-top: 4rpx;
}

.article__body {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 10rpx;
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
  font-size: 22rpx;
}

.article__time {
  font-size: 20rpx;
  color: var(--ink-muted);
  font-variant-numeric: tabular-nums;
}

.article__title {
  display: block;
  font-size: 32rpx;
  font-weight: 800;
  color: var(--ink);
  line-height: 1.35;
  letter-spacing: 0.5rpx;
}

.article__summary {
  display: block;
  font-size: 24rpx;
  line-height: 1.65;
  color: var(--ink-soft);
  padding-left: 14rpx;
  border-left: 2rpx solid var(--rule);
  margin-top: 4rpx;
}

.article__reasons {
  display: flex;
  flex-wrap: wrap;
  gap: 8rpx;
  margin-top: 14rpx;
}

.article__reason {
  max-width: 100%;
  padding: 6rpx 10rpx;
  border: 1rpx solid var(--rule);
  border-radius: 3rpx;
  color: var(--ink-muted);
  font-size: 20rpx;
  line-height: 1.3;
}

.article__foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 10rpx;
  padding-top: 12rpx;
  border-top: 1rpx dotted var(--rule-soft);
  gap: 10rpx;
}

.article__source {
  font-size: 20rpx;
  color: var(--ink-muted);
  letter-spacing: 1rpx;
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.article__actions {
  display: flex;
  gap: 10rpx;
  flex-shrink: 0;
}

.mini-btn {
  padding: 8rpx 16rpx;
  font-size: 20rpx;
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

.mini-btn--speaking {
  background: var(--accent-warm);
  color: var(--paper-bg);
  border-color: var(--accent-warm);
}

.article__feedback {
  display: flex;
  flex-wrap: wrap;
  gap: 10rpx;
  margin-top: 14rpx;
}

.feedback-btn {
  padding: 8rpx 12rpx;
  border: 1rpx solid var(--rule);
  border-radius: 3rpx;
  color: var(--ink-soft);
  background: rgba(255, 255, 255, 0.48);
  font-size: 20rpx;
  font-weight: 700;
  line-height: 1.3;
}

.feedback-btn--active {
  color: var(--paper-bg);
  background: var(--ink);
  border-color: var(--ink);
}

.feedback-btn--danger {
  color: var(--accent-warm);
  border-color: var(--accent-warm);
}

/* === Footer buttons === */
.footer-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 12rpx;
  margin: 20rpx 0 0;
  padding: 26rpx;
  background: var(--ink);
  color: var(--paper-bg);
  border-radius: 4rpx;
  font-size: 26rpx;
  font-weight: 800;
  letter-spacing: 4rpx;
}

.footer-btn__label,
.footer-btn__arrow {
  color: var(--paper-bg);
  font-size: 26rpx;
}

.footer-btn--loading {
  opacity: 0.6;
}

.footer-end {
  display: flex;
  align-items: center;
  gap: 18rpx;
  margin: 28rpx 0 0;
}

.footer-end__rule {
  flex: 1;
  height: 1rpx;
  background: var(--rule);
}

.footer-end__text {
  font-size: 22rpx;
  color: var(--ink-muted);
  letter-spacing: 4rpx;
  font-weight: 700;
}

/* === City picker sheet === */
.city-mask {
  position: fixed;
  top: 0;
  right: 0;
  bottom: 0;
  left: 0;
  background: rgba(26, 26, 26, 0.45);
  z-index: 2000;
  display: flex;
  align-items: flex-end;
  justify-content: center;
}

.city-sheet {
  width: 100%;
  max-width: 680rpx;
  background: var(--paper-card);
  border-top: 3rpx solid var(--ink);
  padding: 28rpx 32rpx calc(env(safe-area-inset-bottom) + 36rpx);
}

.city-sheet__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 24rpx;
  padding-bottom: 16rpx;
  border-bottom: 1rpx solid var(--ink);
}

.city-sheet__title {
  font-size: 34rpx;
  font-weight: 900;
  color: var(--ink);
  letter-spacing: 2rpx;
}

.city-sheet__close {
  font-size: 24rpx;
  color: var(--ink-muted);
  padding: 8rpx 16rpx;
  border: 1rpx solid var(--rule);
  border-radius: 3rpx;
}

.city-input-row {
  display: flex;
  gap: 14rpx;
  margin-bottom: 22rpx;
}

.city-input {
  flex: 1;
  height: 72rpx;
  padding: 0 22rpx;
  border: 1rpx solid var(--ink);
  background: var(--paper-bg);
  font-size: 26rpx;
  color: var(--ink);
  border-radius: 3rpx;
}

.city-input-btn {
  padding: 0 30rpx;
  height: 72rpx;
  line-height: 72rpx;
  background: var(--ink);
  color: var(--paper-bg);
  font-size: 24rpx;
  font-weight: 800;
  letter-spacing: 2rpx;
  border-radius: 3rpx;
}

.city-grid {
  display: flex;
  flex-wrap: wrap;
  gap: 14rpx;
}

.city-chip {
  padding: 14rpx 24rpx;
  background: transparent;
  color: var(--ink);
  font-size: 24rpx;
  font-weight: 700;
  border: 1rpx solid var(--ink);
  border-radius: 3rpx;
  letter-spacing: 1rpx;
}

.city-chip--active {
  background: var(--ink);
  color: var(--paper-bg);
}

/* === FAB: play all news === */
.fab-play {
  position: fixed;
  left: 26rpx;
  bottom: calc(env(safe-area-inset-bottom) + 150rpx);
  z-index: 950;
  display: flex;
  align-items: center;
  gap: 14rpx;
  padding: 16rpx 22rpx 16rpx 18rpx;
  background: var(--ink);
  color: var(--paper-bg);
  border-radius: 999rpx;
  box-shadow: 0 16rpx 40rpx rgba(26, 26, 26, 0.28);
  transition: transform 180ms ease, background 180ms ease;
}

.fab-play:active {
  transform: scale(0.96);
}

.fab-play--active {
  background: var(--accent-warm);
}

.fab-play__icon {
  width: 52rpx;
  height: 52rpx;
  line-height: 52rpx;
  text-align: center;
  border-radius: 999rpx;
  background: rgba(255, 247, 237, 0.15);
  font-size: 26rpx;
  color: var(--paper-bg);
  font-weight: 900;
}

.fab-play__body {
  display: flex;
  flex-direction: column;
  gap: 2rpx;
  min-width: 0;
}

.fab-play__label {
  font-size: 24rpx;
  font-weight: 800;
  letter-spacing: 2rpx;
  color: var(--paper-bg);
}

.fab-play__progress {
  font-size: 18rpx;
  color: rgba(255, 247, 237, 0.7);
  letter-spacing: 1rpx;
  font-variant-numeric: tabular-nums;
}

@media (max-width: 420px) {
  .entry-cols { flex-direction: column; }
  .masthead__title { font-size: 54rpx; }
  .article__index { font-size: 36rpx; min-width: 68rpx; }
}
</style>
