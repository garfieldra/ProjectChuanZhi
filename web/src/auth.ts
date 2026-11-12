import { apiFetch } from './apiClient'

export type AuthSession = {
  authenticated: boolean
  displayName?: string | null
  email?: string | null
  avatarUrl?: string | null
  userId?: string | null
  emailVerified?: boolean
  loginMethods?: string[]
  githubEnabled: boolean
}

export const AUTH_SESSION_CHANGED_EVENT = 'soundseek:auth-session-changed'

type AuthAction = 'session' | 'login' | 'logout'

export async function requestAuth(action: AuthAction, body?: object): Promise<AuthSession> {
  const controller = new AbortController()
  const timeout = setTimeout(() => controller.abort(), 15_000)
  try {
    const response = await apiFetch(`/api/v1/auth/${action}`, {
      method: action === 'session' ? 'GET' : 'POST',
      ...(body ? { headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) } : {}),
      signal: controller.signal,
    })
    const data = await response.json().catch(() => null) as (AuthSession & { message?: string }) | null
    if (!response.ok) {
      if (response.status === 401) throw new Error(action === 'login' ? '邮箱或密码不正确，请再试一次。' : '登录状态已失效，请重新登录。')
      if (response.status === 429) throw new Error('操作有些频繁，请稍后再试。')
      throw new Error(data?.message || '账户服务暂时不可用，请稍后重试。')
    }
    if (!data || typeof data.authenticated !== 'boolean' || typeof data.githubEnabled !== 'boolean') throw new Error('未能确认账户状态，请稍后重试。')
    if (action !== 'session') { await apiFetch('/api/v1/auth/session'); if (typeof window !== 'undefined') notifyAuthChanged() }
    return data
  } catch (error) {
    if (error instanceof Error && error.name === 'AbortError') throw new Error('账户连接超时，请稍后重试。')
    if (error instanceof TypeError) throw new Error('暂时无法连接账户服务，请检查网络后重试。')
    throw error
  } finally { clearTimeout(timeout) }
}

export function requireNewPassword(password: string) {
  if (Array.from(password).length < 10 || new TextEncoder().encode(password).length > 72) throw new Error('密码至少 10 个字符，UTF-8 编码不能超过 72 字节。')
}
const pendingAuthKeys = new Map<string, string>()
export async function accountRequest<T = { message: string }>(path: string, body?: object): Promise<T> {
  if (['/auth/register', '/auth/password-reset/confirm', '/me/password'].includes(path)) requireNewPassword((body as { password?: string })?.password || '')
  const signature = path + JSON.stringify(body || {})
  const key = pendingAuthKeys.get(signature) || crypto.randomUUID()
  pendingAuthKeys.set(signature, key)
  const controller = new AbortController(), timeout = setTimeout(() => controller.abort(), 15000)
  try {
    const response = await apiFetch('/api/v1' + path, { method: 'POST', headers: { 'Content-Type': 'application/json', 'Idempotency-Key': key }, body: JSON.stringify(body || {}), signal: controller.signal })
    const data = await response.json().catch(() => ({}))
    if (!response.ok) throw new Error(data.message || (response.status === 429 ? '操作频繁，请稍后再试。' : '账号操作暂时无法完成。'))
    pendingAuthKeys.delete(signature)
    await apiFetch('/api/v1/auth/session', { signal: controller.signal })
    return data as T
  } catch (error) {
    if (error instanceof Error && error.name === 'AbortError') throw new Error('账号连接超时，请稍后重试。')
    throw error
  } finally { clearTimeout(timeout) }
}
export function notifyAuthChanged() { window.dispatchEvent(new Event(AUTH_SESSION_CHANGED_EVENT)); try { localStorage.setItem('soundseek:auth-revision', crypto.randomUUID()) } catch { /* storage may be disabled */ } }
if (typeof window !== 'undefined') window.addEventListener('storage', event => { if (event.key === 'soundseek:auth-revision') { window.dispatchEvent(new Event(AUTH_SESSION_CHANGED_EVENT)); location.reload() } })

if (typeof window !== 'undefined') window.addEventListener('soundseek:session-expired', () => {
  // Reload discards account-specific React state, active streams and pending private responses.
  window.dispatchEvent(new Event(AUTH_SESSION_CHANGED_EVENT))
  location.reload()
})
