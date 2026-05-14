const TAB_PREVIOUS_KEY = 'bishe10-demo-dock-prev-index'
const TAB_TRANSITION_KEY = 'bishe10-demo-dock-transition-v1'
const TRANSITION_TTL = 1600
const PREVIOUS_INDEX_TTL = 5000
const SWIPE_DISTANCE = 68
const SWIPE_DIRECTION_RATIO = 1.15
let navigationLocked = false

export const demoTabs = [
  { id: 'home', url: '/pages/home/index' },
  { id: 'vision', url: '/pages/vision/index' },
  { id: 'profile', url: '/pages/profile/index' },
]

function readStorage(key) {
  try {
    return uni.getStorageSync(key)
  } catch (error) {
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

function removeStorage(key) {
  try {
    uni.removeStorageSync(key)
  } catch (error) {
    console.warn('remove storage failed', key, error)
  }
}

export function getTabIndexById(id) {
  return demoTabs.findIndex((item) => item.id === id)
}

export function readPreviousTabIndex(fallback = 0) {
  const raw = readStorage(TAB_PREVIOUS_KEY)
  if (typeof raw === 'number' && Number.isFinite(raw)) {
    return raw
  }

  const index = Number(raw?.index)
  const savedAt = Number(raw?.savedAt)
  if (!Number.isFinite(index)) {
    return fallback
  }
  if (Number.isFinite(savedAt) && Date.now() - savedAt > PREVIOUS_INDEX_TTL) {
    return fallback
  }
  return index
}

function storePreviousTabIndex(index) {
  writeStorage(TAB_PREVIOUS_KEY, {
    index,
    savedAt: Date.now(),
  })
}

export function prepareTabTransition(fromIndex, toIndex) {
  if (!Number.isFinite(fromIndex) || !Number.isFinite(toIndex)) return

  storePreviousTabIndex(fromIndex)

  if (fromIndex === toIndex) return

  writeStorage(TAB_TRANSITION_KEY, {
    toIndex,
    direction: toIndex > fromIndex ? 'forward' : 'backward',
    savedAt: Date.now(),
  })
}

export function consumeTabTransition(targetIndex) {
  const payload = readStorage(TAB_TRANSITION_KEY)
  if (!payload) return ''

  const toIndex = Number(payload.toIndex)
  const savedAt = Number(payload.savedAt)
  const isExpired = !Number.isFinite(savedAt) || Date.now() - savedAt > TRANSITION_TTL

  if (isExpired || toIndex !== targetIndex) {
    if (isExpired) {
      removeStorage(TAB_TRANSITION_KEY)
    }
    return ''
  }

  removeStorage(TAB_TRANSITION_KEY)
  return payload.direction === 'forward'
    ? 'page-shell--enter page-shell--enter-from-right'
    : 'page-shell--enter page-shell--enter-from-left'
}

export function navigateToTab(currentIndex, nextIndex) {
  const target = demoTabs[nextIndex]
  if (!target || nextIndex === currentIndex) {
    return false
  }
  if (navigationLocked) {
    return false
  }

  navigationLocked = true
  prepareTabTransition(currentIndex, nextIndex)
  uni.redirectTo({
    url: target.url,
    success: () => {
      setTimeout(() => {
        navigationLocked = false
      }, 300)
    },
    fail: (error) => {
      console.warn('redirect tab failed, retry with reLaunch', error)
      uni.reLaunch({
        url: target.url,
        complete: () => {
          setTimeout(() => {
            navigationLocked = false
          }, 300)
        },
      })
    },
  })
  return true
}

export function getSwipeTargetIndex(currentIndex, startPoint, endPoint) {
  if (!startPoint || !endPoint) return -1

  const deltaX = endPoint.x - startPoint.x
  const deltaY = endPoint.y - startPoint.y
  if (Math.abs(deltaX) < SWIPE_DISTANCE) return -1
  if (Math.abs(deltaX) < Math.abs(deltaY) * SWIPE_DIRECTION_RATIO) return -1

  return deltaX < 0 ? currentIndex + 1 : currentIndex - 1
}
