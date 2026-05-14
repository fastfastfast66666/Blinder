const ADMIN = {
  id: 1,
  username: 'admin',
  nickname: '系统管理员'
}

const USER_KEY = 'bishe10-manager-mock-users'
const NEWS_SOURCE_KEY = 'bishe10-manager-mock-news-sources'

const seedUsers = [
  {
    id: 1001,
    username: 'chenyu',
    email: 'chenyu@example.com',
    nickname: '陈宇',
    phone: '13800000001',
    status: 'enabled',
    createdAt: '2026-04-18 09:31:20',
    lastLoginAt: '2026-04-24 21:06:12'
  },
  {
    id: 1002,
    username: 'liqing',
    email: 'liqing@example.com',
    nickname: '李晴',
    phone: '13800000002',
    status: 'enabled',
    createdAt: '2026-04-19 14:17:45',
    lastLoginAt: '2026-04-25 08:15:36'
  },
  {
    id: 1003,
    username: 'wangnan',
    email: 'wangnan@example.com',
    nickname: '王楠',
    phone: '13800000003',
    status: 'disabled',
    createdAt: '2026-04-20 11:02:09',
    lastLoginAt: ''
  },
  {
    id: 1004,
    username: 'zhaolei',
    email: 'zhaolei@example.com',
    nickname: '赵磊',
    phone: '13800000004',
    status: 'enabled',
    createdAt: '2026-04-22 16:40:28',
    lastLoginAt: '2026-04-24 18:52:01'
  }
]

const seedNewsSources = [
  {
    sourceKey: 'BAIDU',
    sourceName: '百度新闻搜索',
    sourceType: 'BAIDU_SEARCH',
    endpoint: 'https://www.baidu.com/s',
    enabled: true,
    priority: 10,
    description: '通过百度新闻搜索页抓取城市、交通、民生等关键词新闻。',
    lastFetchAt: '',
    lastStatus: 'INIT',
    lastMessage: '系统默认新闻源',
    updatedAt: ''
  },
  {
    sourceKey: 'SOGOU',
    sourceName: '搜狗新闻搜索',
    sourceType: 'SOGOU_SEARCH',
    endpoint: 'https://news.sogou.com/news',
    enabled: true,
    priority: 20,
    description: '通过搜狗新闻搜索页补充城市和全国热点新闻。',
    lastFetchAt: '',
    lastStatus: 'INIT',
    lastMessage: '系统默认新闻源',
    updatedAt: ''
  },
  {
    sourceKey: 'CHINANEWS_SCROLL',
    sourceName: '中国新闻网滚动新闻',
    sourceType: 'RSS',
    endpoint: 'https://www.chinanews.com.cn/rss/scroll-news.xml',
    enabled: true,
    priority: 30,
    description: '中国新闻网滚动新闻 RSS。',
    lastFetchAt: '',
    lastStatus: 'INIT',
    lastMessage: '系统默认新闻源',
    updatedAt: ''
  },
  {
    sourceKey: 'CHINANEWS_CHINA',
    sourceName: '中国新闻网时政新闻',
    sourceType: 'RSS',
    endpoint: 'https://www.chinanews.com.cn/rss/china.xml',
    enabled: true,
    priority: 40,
    description: '中国新闻网时政频道 RSS。',
    lastFetchAt: '',
    lastStatus: 'INIT',
    lastMessage: '系统默认新闻源',
    updatedAt: ''
  },
  {
    sourceKey: 'CHINANEWS_SOCIETY',
    sourceName: '中国新闻网社会新闻',
    sourceType: 'RSS',
    endpoint: 'https://www.chinanews.com.cn/rss/society.xml',
    enabled: true,
    priority: 50,
    description: '中国新闻网社会频道 RSS。',
    lastFetchAt: '',
    lastStatus: 'INIT',
    lastMessage: '系统默认新闻源',
    updatedAt: ''
  },
  {
    sourceKey: 'PEOPLE_SOCIETY',
    sourceName: '人民网社会新闻',
    sourceType: 'RSS',
    endpoint: 'http://www.people.com.cn/rss/society.xml',
    enabled: true,
    priority: 60,
    description: '人民网社会新闻 RSS。',
    lastFetchAt: '',
    lastStatus: 'INIT',
    lastMessage: '系统默认新闻源',
    updatedAt: ''
  }
]

function wait(ms = 260) {
  return new Promise((resolve) => setTimeout(resolve, ms))
}

function readUsers() {
  const saved = localStorage.getItem(USER_KEY)
  if (!saved) {
    localStorage.setItem(USER_KEY, JSON.stringify(seedUsers))
    return [...seedUsers]
  }

  try {
    return JSON.parse(saved)
  } catch {
    localStorage.setItem(USER_KEY, JSON.stringify(seedUsers))
    return [...seedUsers]
  }
}

function writeUsers(users) {
  localStorage.setItem(USER_KEY, JSON.stringify(users))
}

function readNewsSources() {
  const saved = localStorage.getItem(NEWS_SOURCE_KEY)
  if (!saved) {
    localStorage.setItem(NEWS_SOURCE_KEY, JSON.stringify(seedNewsSources))
    return [...seedNewsSources]
  }

  try {
    return JSON.parse(saved)
  } catch {
    localStorage.setItem(NEWS_SOURCE_KEY, JSON.stringify(seedNewsSources))
    return [...seedNewsSources]
  }
}

function writeNewsSources(items) {
  localStorage.setItem(NEWS_SOURCE_KEY, JSON.stringify(items))
}

function nowText() {
  const date = new Date()
  const pad = (value) => String(value).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
}

export async function mockLogin(payload) {
  await wait()
  if (payload.username !== 'admin' || payload.password !== 'admin123') {
    throw new Error('管理员用户名或密码错误')
  }
  return {
    token: 'mock-admin-token',
    admin: ADMIN
  }
}

export async function mockCurrentAdmin() {
  await wait(120)
  return { admin: ADMIN }
}

export async function mockLogout() {
  await wait(120)
  return { ok: true }
}

export async function mockListUsers(params = {}) {
  await wait()
  const keyword = String(params.keyword || '').trim().toLowerCase()
  const status = params.status || ''
  const pageNum = Number(params.pageNum || params.page || 1)
  const pageSize = Number(params.pageSize || 10)

  let records = readUsers()
  if (keyword) {
    records = records.filter((user) => {
      return [user.username, user.email, user.nickname, user.phone]
        .filter(Boolean)
        .some((value) => String(value).toLowerCase().includes(keyword))
    })
  }
  if (status) records = records.filter((user) => user.status === status)

  const total = records.length
  const start = (pageNum - 1) * pageSize

  return {
    records: records.slice(start, start + pageSize),
    total
  }
}

export async function mockCreateUser(payload) {
  await wait()
  const users = readUsers()
  if (users.some((user) => user.username === payload.username)) {
    throw new Error('用户名已存在')
  }
  if (users.some((user) => user.email === payload.email)) {
    throw new Error('邮箱已存在')
  }

  const nextId = Math.max(1000, ...users.map((user) => Number(user.id) || 0)) + 1
  const user = {
    ...payload,
    id: nextId,
    status: payload.status || 'enabled',
    createdAt: nowText(),
    lastLoginAt: ''
  }
  users.unshift(user)
  writeUsers(users)
  return user
}

export async function mockUpdateUser(id, payload) {
  await wait()
  const users = readUsers()
  const targetIndex = users.findIndex((user) => String(user.id) === String(id))
  if (targetIndex < 0) throw new Error('用户不存在')

  users[targetIndex] = {
    ...users[targetIndex],
    ...payload,
    id: users[targetIndex].id
  }
  writeUsers(users)
  return users[targetIndex]
}

export async function mockDeleteUser(id) {
  await wait()
  const users = readUsers().filter((user) => String(user.id) !== String(id))
  writeUsers(users)
  return { ok: true }
}

export async function mockListNewsSources() {
  await wait()
  const items = readNewsSources().sort((a, b) => Number(a.priority) - Number(b.priority))
  return {
    items,
    total: items.length,
    enabledCount: items.filter((item) => item.enabled).length
  }
}

export async function mockUpdateNewsSource(sourceKey, payload = {}) {
  await wait()
  const items = readNewsSources()
  const index = items.findIndex((item) => item.sourceKey === sourceKey)
  if (index < 0) throw new Error('新闻源不存在')
  items[index] = {
    ...items[index],
    ...payload,
    enabled: payload.enabled === undefined ? items[index].enabled : Boolean(payload.enabled),
    updatedAt: nowText()
  }
  writeNewsSources(items)
  return items[index]
}

export async function mockResetNewsSources() {
  await wait()
  writeNewsSources(seedNewsSources)
  return mockListNewsSources()
}
