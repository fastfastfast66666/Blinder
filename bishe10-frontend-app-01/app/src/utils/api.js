const DEFAULT_API_BASE = 'http://127.0.0.1:8080'
const REQUEST_TIMEOUT_MS = 10000
const API_BASE_STORAGE_KEY = 'bishe10_api_base_override_v1'
const LEGACY_API_BASES = new Set([
  'https://158-247-192-25.sslip.io',
  'http://192.168.31.174:8080',
  'http://127.0.0.1:8080',
  'http://localhost:8080',
  '127.0.0.1:8080',
  'localhost:8080',
  'http://127.0.0.1:8081',
  'http://localhost:8081',
  '127.0.0.1:8081',
  'localhost:8081'
])
let activeAudio = null
let activeVisionController = null

function isLocalLikeHost(value) {
  return /^(localhost|127(?:\.\d{1,3}){3}|10(?:\.\d{1,3}){3}|192\.168(?:\.\d{1,3}){2}|172\.(1[6-9]|2\d|3[0-1])(?:\.\d{1,3}){2})($|[:/])/.test(value)
}

function normalizeApiBase(raw, fallback = DEFAULT_API_BASE) {
  const trimmed = typeof raw === 'string' ? raw.trim() : ''
  if (!trimmed) {
    return fallback
  }

  const withProtocol = /^https?:\/\//i.test(trimmed)
    ? trimmed
    : `${isLocalLikeHost(trimmed) ? 'http' : 'https'}://${trimmed}`

  return withProtocol.replace(/\/+$/, '')
}

function readStoredApiBase() {
  try {
    const stored = uni.getStorageSync(API_BASE_STORAGE_KEY) || ''
    const normalized = normalizeApiBase(stored, '')
    if (normalized && LEGACY_API_BASES.has(normalized)) {
      uni.removeStorageSync(API_BASE_STORAGE_KEY)
      return ''
    }
    return stored
  } catch (error) {
    console.warn('read api base failed', error)
    return ''
  }
}

export function getDefaultApiBase() {
  return DEFAULT_API_BASE
}

export function getApiBase() {
  return normalizeApiBase(readStoredApiBase(), DEFAULT_API_BASE)
}

export function setApiBase(nextBase) {
  const normalized = normalizeApiBase(nextBase, '')
  if (!normalized) {
    return resetApiBase()
  }

  try {
    uni.setStorageSync(API_BASE_STORAGE_KEY, normalized)
  } catch (error) {
    console.warn('save api base failed', error)
  }
  return normalized
}

export function resetApiBase() {
  try {
    uni.removeStorageSync(API_BASE_STORAGE_KEY)
  } catch (error) {
    console.warn('reset api base failed', error)
  }
  return DEFAULT_API_BASE
}

export function resolveUrl(path) {
  const apiBase = getApiBase()
  if (!path) return apiBase
  if (/^https?:\/\//.test(path)) return path
  return `${apiBase}${path}`
}

const AUTH_TOKEN_KEY = 'bishe10_auth_token_v1'
const AUTH_USER_KEY = 'bishe10_auth_user_v1'

export function getAuthToken() {
  try {
    return uni.getStorageSync(AUTH_TOKEN_KEY) || ''
  } catch {
    return ''
  }
}

export function setAuthToken(token) {
  try {
    if (token) uni.setStorageSync(AUTH_TOKEN_KEY, token)
    else uni.removeStorageSync(AUTH_TOKEN_KEY)
  } catch {}
}

export function getStoredUser() {
  try {
    return uni.getStorageSync(AUTH_USER_KEY) || null
  } catch {
    return null
  }
}

export function setStoredUser(user) {
  try {
    if (user) uni.setStorageSync(AUTH_USER_KEY, user)
    else uni.removeStorageSync(AUTH_USER_KEY)
  } catch {}
}

export function request(path, method = 'GET', data = {}, options = {}) {
  return new Promise((resolve, reject) => {
    const header = { 'content-type': 'application/json' }
    const token = getAuthToken()
    if (token) {
      header['Authorization'] = 'Bearer ' + token
    }
    uni.request({
      url: resolveUrl(path),
      method,
      data,
      header,
      timeout: options.timeout || REQUEST_TIMEOUT_MS,
      success(res) {
        const payload = res.data || {}
        if (res.statusCode >= 200 && res.statusCode < 300 && (payload.success === true || payload.code === 200)) {
          resolve(payload)
          return
        }
        reject(payload)
      },
      fail(error) {
        reject(error)
      },
    })
  })
}

// === Auth helpers ===
export function authSendCode(email) {
  return request('/api/auth/send-code', 'POST', { email })
}

export function authRegister({ email, code, password, nickname }) {
  return request('/api/auth/register', 'POST', { email, code, password, nickname })
}

export function authLoginPassword({ email, password }) {
  return request('/api/auth/login', 'POST', { email, password })
}

export function authLoginCode({ email, code }) {
  return request('/api/auth/login-code', 'POST', { email, code })
}

export function authLogout() {
  const token = getAuthToken()
  setAuthToken('')
  setStoredUser(null)
  if (!token) return Promise.resolve({ ok: true })
  return request('/api/auth/logout', 'POST', { token }).catch(() => ({ ok: true }))
}

export function authMe() {
  return request('/api/auth/me')
}

export function uploadVisionImage(filePath, scene = 'general') {
  return new Promise((resolve, reject) => {
    uni.uploadFile({
      url: resolveUrl('/api/vision/analyze'),
      filePath,
      name: 'image',
      formData: {
        scene,
      },
      success(res) {
        try {
          const payload = typeof res.data === 'string' ? JSON.parse(res.data) : res.data
          if (res.statusCode >= 200 && res.statusCode < 300 && (payload.success === true || payload.code === 200)) {
            resolve(payload)
            return
          }
          reject(payload)
        } catch (error) {
          reject(error)
        }
      },
      fail(error) {
        reject(error)
      },
    })
  })
}

export function getVisionSamples() {
  return request('/api/vision/samples')
}

function canUseStreamingFetch() {
  const isBrowserLikeRuntime =
    typeof window !== 'undefined' &&
    typeof document !== 'undefined'

  return (
    isBrowserLikeRuntime &&
    typeof fetch === 'function' &&
    typeof AbortController !== 'undefined' &&
    typeof FormData !== 'undefined' &&
    typeof TextDecoder !== 'undefined' &&
    typeof ReadableStream !== 'undefined'
  )
}

export function canStreamVision() {
  return canUseStreamingFetch()
}

function wait(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms))
}

function parseSseEventBlock(block) {
  const lines = block.split(/\r?\n/)
  let event = 'message'
  const dataLines = []

  for (const line of lines) {
    if (line.startsWith('event:')) {
      event = line.slice(6).trim()
      continue
    }
    if (line.startsWith('data:')) {
      dataLines.push(line.slice(5).trim())
    }
  }

  const payloadText = dataLines.join('\n')
  if (!payloadText) {
    return null
  }

  try {
    return {
      event,
      data: JSON.parse(payloadText),
    }
  } catch {
    return {
      event,
      data: payloadText,
    }
  }
}

async function consumeVisionStream({ body, headers = {}, onMeta, onStatus, onPreview, onResult }) {
  if (!canUseStreamingFetch()) {
    throw new Error('当前环境不支持流式识图')
  }

  if (activeVisionController) {
    activeVisionController.abort()
  }

  const controller = new AbortController()
  activeVisionController = controller

  const response = await fetch(resolveUrl('/api/vision/analyze/stream'), {
    method: 'POST',
    headers,
    body,
    signal: controller.signal,
  })

  if (!response.ok || !response.body) {
    throw new Error(`流式识图接口异常：${response.status}`)
  }

  const reader = response.body.getReader()
  const decoder = new TextDecoder('utf-8')
  let buffer = ''
  let finalResult = null

  try {
    while (true) {
      const { done, value } = await reader.read()
      if (done) break

      buffer += decoder.decode(value, { stream: true })
      let boundary = buffer.indexOf('\n\n')
      while (boundary >= 0) {
        const block = buffer.slice(0, boundary)
        buffer = buffer.slice(boundary + 2)
        boundary = buffer.indexOf('\n\n')

        const parsed = parseSseEventBlock(block)
        if (!parsed) continue

        if (parsed.event === 'meta' && onMeta) {
          await onMeta(parsed.data)
        }
        if (parsed.event === 'status' && onStatus) {
          await onStatus(parsed.data)
        }
        if (parsed.event === 'preview' && onPreview) {
          await onPreview(parsed.data)
          // Keep each chunk visible long enough to avoid batching into a single paint.
          await wait(120)
        }
        if (parsed.event === 'result') {
          finalResult = parsed.data
          if (onResult) {
            await onResult(parsed.data)
          }
        }
        if (parsed.event === 'error') {
          throw new Error(parsed.data?.message || '流式识图失败')
        }
      }
    }

    if (!finalResult) {
      throw new Error('流式识图未返回最终结果')
    }
    return finalResult
  } finally {
    try {
      reader.releaseLock()
    } catch {}
    if (activeVisionController === controller) {
      activeVisionController = null
    }
  }
}

export function stopVisionStream() {
  if (!activeVisionController) return
  activeVisionController.abort()
  activeVisionController = null
}

export function streamVisionJson(payload, handlers = {}) {
  return consumeVisionStream({
    body: JSON.stringify(payload || {}),
    headers: {
      'content-type': 'application/json',
    },
    ...handlers,
  })
}

export function streamVisionUpload(file, scene = 'general', handlers = {}) {
  const formData = new FormData()
  formData.append('scene', scene)
  formData.append('image', file, file?.name || 'vision-upload.jpg')
  return consumeVisionStream({
    body: formData,
    headers: {},
    ...handlers,
  })
}

export function saveHistory(item) {
  return request('/api/history', 'POST', item)
}

export function interpretNews({ title = '', summary = '', content = '', source = '', category = '' } = {}) {
  return request('/api/news/interpret', 'POST', { title, summary, content, source, category })
}

export function recommendNews(params = {}) {
  return request('/api/news/recommend', 'GET', params)
}

export function sendNewsFeedback(articleId, { userId, action }) {
  return request(`/api/news/${articleId}/feedback`, 'POST', { userId, action })
}

export function getNewsProfile(userId) {
  return request(`/api/users/${userId}/news-profile`)
}

function stopAudio() {
  if (!activeAudio) return
  if (typeof activeAudio.pause === "function") {
    activeAudio.pause()
  }
  if (typeof activeAudio.stop === "function") {
    activeAudio.stop()
  }
  if (typeof activeAudio.destroy === "function") {
    activeAudio.destroy()
  }
  activeAudio = null
}

let isSpeaking = false
let speechGeneration = 0

export function getSpeakingState() {
  return isSpeaking
}

export async function playSpeech({ text, title = "语音播报", source = "系统播报", recordHistory = true }) {
  stopAudio()
  const generation = ++speechGeneration
  isSpeaking = true

  let audio = null
  const useWebAudio =
    typeof window !== "undefined" &&
    typeof document !== "undefined" &&
    typeof Audio !== "undefined"

  if (useWebAudio) {
    audio = new Audio()
    audio.preload = "auto"
  } else {
    audio = uni.createInnerAudioContext()
  }
  activeAudio = audio

  // Promise that resolves when playback truly ends (or errors/stops).
  let resolveEnd
  const endPromise = new Promise((resolve) => { resolveEnd = resolve })

  let res
  try {
    res = await request(
      "/api/voice/synthesize",
      "POST",
      {
        text,
        title,
        source,
        recordHistory,
      },
      { timeout: 45000 }
    )
  } catch (err) {
    if (generation === speechGeneration) {
      isSpeaking = false
    }
    resolveEnd()
    throw err
  }

  if (generation !== speechGeneration) {
    stopAudio()
    isSpeaking = false
    resolveEnd()
    throw new Error("speech stopped")
  }

  const payload = res.data || {}
  if (!payload.available || !payload.audioUrl) {
    if (generation === speechGeneration) {
      isSpeaking = false
    }
    resolveEnd()
    throw new Error("语音生成失败")
  }

  const audioUrl = resolveUrl(payload.audioUrl)

  const finish = () => {
    if (generation === speechGeneration) {
      isSpeaking = false
    }
    resolveEnd()
  }

  if (useWebAudio) {
    audio.src = audioUrl
    audio.onended = finish
    audio.onerror = finish
    audio.onpause = () => {
      // Only mark finished on explicit pause that looks like a stop (not mid-play).
      if (audio.currentTime === 0 || audio.ended) finish()
    }
    try {
      await audio.play()
    } catch (playErr) {
      audio.load()
      await new Promise(r => setTimeout(r, 100))
      try {
        await audio.play()
      } catch {
        finish()
        throw playErr
      }
    }
  } else {
    audio.src = audioUrl
    audio.onEnded(finish)
    audio.onError(finish)
    audio.onStop(finish)
    audio.play()
  }

  // Attach the end-promise to payload so callers can await playback completion.
  payload.ended = endPromise
  return payload
}

export function stopSpeech() {
  speechGeneration += 1
  stopAudio()
  isSpeaking = false
}
