<template>
  <view class="dock-shell">
    <view class="dock-panel">
      <view class="dock-rule"></view>
      <view class="dock-row">
        <view
          v-for="(item, index) in items"
          :key="item.id"
          class="dock-item"
          :class="{ 'dock-item--active': active === item.id }"
          @tap="go(item, index)"
        >
          <text class="dock-num">0{{ index + 1 }}</text>
          <text class="dock-label">{{ item.label }}</text>
          <view class="dock-underline"></view>
        </view>
      </view>
    </view>
  </view>
</template>

<script>
const STORAGE_KEY = 'bishe10-demo-dock-prev-index'

export default {
  props: {
    active: {
      type: String,
      default: 'home',
    },
  },
  data() {
    return {
      items: [
        { id: 'home', label: '资讯', url: '/pages/home/index' },
        { id: 'vision', label: '识图', url: '/pages/vision/index' },
        { id: 'profile', label: '我的', url: '/pages/profile/index' },
      ],
    }
  },
  computed: {
    activeIndex() {
      const idx = this.items.findIndex((item) => item.id === this.active)
      return idx >= 0 ? idx : 0
    },
  },
  methods: {
    go(item, index) {
      if (item.id === this.active) return
      try {
        uni.setStorageSync(STORAGE_KEY, this.activeIndex)
      } catch (_) {}
      uni.redirectTo({ url: item.url })
    },
  },
}
</script>

<style scoped>
.dock-shell {
  position: fixed;
  left: 0;
  right: 0;
  bottom: 0;
  z-index: 1000;
  background: var(--paper-bg);
  padding: 0 26rpx calc(env(safe-area-inset-bottom) + 18rpx);
  pointer-events: auto;
}

.dock-panel {
  padding-top: 10rpx;
}

.dock-rule {
  height: 2rpx;
  background: var(--ink);
  margin-bottom: 8rpx;
}

.dock-row {
  display: flex;
  gap: 6rpx;
}

.dock-item {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4rpx;
  padding: 18rpx 10rpx 14rpx;
  position: relative;
}

.dock-num {
  font-size: 20rpx;
  font-weight: 900;
  color: var(--ink-muted);
  letter-spacing: 1rpx;
  font-variant-numeric: tabular-nums;
}

.dock-label {
  font-size: 28rpx;
  font-weight: 800;
  color: var(--ink);
  letter-spacing: 4rpx;
}

.dock-underline {
  position: absolute;
  left: 50%;
  bottom: 4rpx;
  transform: translateX(-50%) scaleX(0);
  transform-origin: center;
  width: 48rpx;
  height: 3rpx;
  background: var(--ink);
  transition: transform 280ms cubic-bezier(0.22, 1, 0.36, 1);
}

.dock-item--active .dock-num {
  color: var(--accent-warm);
}

.dock-item--active .dock-underline {
  transform: translateX(-50%) scaleX(1);
  background: var(--accent-warm);
}
</style>
