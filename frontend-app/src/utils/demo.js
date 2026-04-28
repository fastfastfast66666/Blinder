export function encodePayload(payload) {
  return encodeURIComponent(JSON.stringify(payload))
}

export function parsePayload(raw) {
  if (!raw) return null
  try {
    return JSON.parse(decodeURIComponent(raw))
  } catch (error) {
    return null
  }
}

export function getDemoProfile() {
  const storedName = uni.getStorageSync('bishe10_demo_user_name')
  if (!storedName) {
    return {
      isLoggedIn: false,
      name: '晨星用户',
      intro: '关注无障碍资讯与环境识别',
    }
  }

  return {
    isLoggedIn: true,
    name: storedName,
    intro: '已登录账号，可查看历史记录与偏好入口',
  }
}

export function toggleDemoProfile(currentState) {
  if (currentState) {
    uni.removeStorageSync('bishe10_demo_user_name')
    return getDemoProfile()
  }

  uni.setStorageSync('bishe10_demo_user_name', '用户 13800000000')
  return getDemoProfile()
}
