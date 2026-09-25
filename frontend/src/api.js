export class ApiError extends Error {
  constructor(status, code) { super(code); this.status = status; this.code = code }
}
let expectedAccount = null
export function bindAccount(user) { expectedAccount = user || null }

export async function api(path, { method = 'GET', body, csrf, signal } = {}) {
  const response = await fetch(`/v2${path}`, {
    method, credentials: 'same-origin',
    headers: { ...(body ? { 'Content-Type': 'application/json' } : {}), ...(csrf ? { 'X-CSRF-Token': csrf } : {}), ...(expectedAccount && !path.startsWith('/auth/') ? { 'X-Account-ID': expectedAccount } : {}) },
    body: body ? JSON.stringify(body) : undefined,
    signal: signal ? AbortSignal.any([signal, AbortSignal.timeout(10000)]) : AbortSignal.timeout(10000),
  })
  if (response.status === 204) return null
  const data = await response.json().catch(() => ({}))
  if (!response.ok) throw new ApiError(response.status, data.error || 'SERVICE_UNAVAILABLE')
  return data
}

const messages = {
  BAD_CREDENTIALS: '账号或密码不正确。', ACCOUNT_UNAVAILABLE: '这个用户名已被使用。',
  INVALID_USERNAME: '用户名需为 4–24 位小写字母、数字或下划线，以字母开头。',
  INVALID_PASSWORD: '密码至少 12 个字符，UTF-8 编码不超过 72 字节。',
  INVALID_DISPLAY_NAME: '昵称需为 1–24 个字符。',
  AUTH_RATE_LIMITED: '登录尝试过于频繁，请稍后再试。',
  UNAUTHORIZED: '登录已过期，请重新登录。', CSRF_REJECTED: '会话已更新，请刷新页面后重试。',
  SESSION_CHANGED: '其他页面已切换账号，请重新确认登录。',
  ALREADY_PURCHASED: '此活动每人限购一次，取消或过期后也不能再次购买。',
  SOLD_OUT: '本场活动已售罄。', ACTIVITY_CLOSED: '本场活动已结束。',
  STILL_AVAILABLE: '当前仍有名额，可以直接抢券。', WAITLIST_NOT_FOUND: '未找到这条候补记录。',
  WAITLIST_TERMINAL: '这条候补记录已结束，无法再次操作。',
  ACTIVITY_NOT_FOUND: '活动不存在或已下架。', INVALID_REQUEST: '请求参数不正确。',
  PROCESS_DEADLINE: '处理已超时，本次请求不会再生成订单。',
  REQUEST_NOT_FOUND: '暂未查到这笔请求，可使用原请求重试。',
  INVALID_PLAN: '规划条件不正确，请检查时间、预算和人数。',
  PLANNING_DISABLED: '智能规划尚未配置模型服务。', PLAN_KEY_CONFLICT: '规划请求标识已被其他内容使用。',
  TOO_MANY_PLANS: '已有规划任务正在执行，请稍后再试。', PLANNING_QUEUE_FULL: '规划任务较多，请稍后再试。',
  PLAN_NOT_FOUND: '规划任务不存在或已过期。', PLAN_TERMINAL: '规划任务已经结束。',
}
export function explain(error) {
  return messages[error.code] || (error.status === 429 ? '请求较多，请稍后再试。' : error.status >= 500 ? '服务暂时繁忙，请稍后重试。' : '连接未完成，请检查网络后重试。')
}

const storageKey = user => `life.pending.v1.${user}`
export function readPending(user) {
  if (!user) return []
  try {
    const data = JSON.parse(localStorage.getItem(storageKey(user)) || '[]')
    return Array.isArray(data) ? data.filter(x => typeof x.key === 'string' && /^[a-zA-Z0-9-]{8,80}$/.test(x.key) && Number.isSafeInteger(x.activityId)).slice(0, 30) : []
  } catch { return [] }
}
// Persist before sending: an ambiguous response must never generate a second intent.
export function remember(user, intent) {
  const values = readPending(user).filter(x => x.key !== intent.key)
  if (values.length >= 30) throw new Error('请先处理待确认的请求。')
  localStorage.setItem(storageKey(user), JSON.stringify([...values, intent]))
}
export function forget(user, key) {
  localStorage.setItem(storageKey(user), JSON.stringify(readPending(user).filter(x => x.key !== key)))
}
